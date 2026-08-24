///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//NATIVE_OPTIONS -O2 --no-fallback

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.SimpleFileServer;
import com.sun.net.httpserver.SimpleFileServer.OutputLevel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Lightweight HTTP/HTTPS file server utility inspired by {@code python -m http.server}.
 *
 * <p>
 * Built using Java's built-in {@link SimpleFileServer}, {@link HttpServer}, and {@link HttpsServer}
 * APIs. Supports on-the-fly SSL/TLS certificates.
 */
@Command(name = "serve", mixinStandardHelpOptions = true, version = "serve 1.2",
    description = "Simple HTTP/HTTPS file server inspired by python -m http.server")
@SuppressWarnings("unused")
class Serve implements Callable<Integer> {

  @Option(names = {"-p", "--port"}, description = "Port to listen on (default: 8080 or 8443)")
  private Integer port;

  @Option(names = {"-d", "--directory"},
      description = "Directory to serve (default: current directory)")
  private Path directory;

  @Option(names = {"-b", "--bind"}, description = "Address to bind to (default: 0.0.0.0)")
  private String bind = "0.0.0.0";

  @Option(names = {"-v", "--verbose"}, description = "Enable verbose request logging")
  private boolean verbose;

  @Option(names = {"-a", "--download"},
      description = "Force browser to download files instead of displaying inline")
  private boolean download;

  @Option(names = {"-s", "--ssl", "--tls"},
      description = "Enable HTTPS with an automatically generated on-the-fly self-signed certificate")
  private boolean ssl;

  @Option(names = {"--spa"},
      description = "Single Page Application mode: fallback 404 requests to index.html")
  private boolean spaMode;

  @Option(names = {"--auth"},
      description = "HTTP Basic Authentication credentials (format: user:password)")
  private String authCredentials;

  @Parameters(arity = "0..2", paramLabel = "[dirOrPort]",
      description = "Optional directory path and/or port number")
  private List<String> positionalArgs = new ArrayList<>();

  /**
   * Helper method checking whether a string represents a valid integer.
   *
   * @param s String to check.
   * @return {@code true} if string can be parsed as an integer, {@code false} otherwise.
   */
  private static boolean isInteger(String s) {
    try {
      Integer.parseInt(s);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * Main entry point for the JBang script execution.
   *
   * @param args Command-line arguments.
   */
  void main(String... args) {
    var exitCode = new CommandLine(this).execute(args);
    System.exit(exitCode);
  }

  /**
   * Resolves arguments, validates directory and port parameters, and launches the file server.
   *
   * @return Status code 0 for success, 1 for errors.
   */
  @SuppressWarnings("HttpUrlsUsage")
  @Override
  public Integer call() {
    resolveArguments();

    if (!Files.exists(directory)) {
      System.err.printf("Error: Directory '%s' does not exist.%n", directory);
      return 1;
    }
    if (!Files.isDirectory(directory)) {
      System.err.printf("Error: Path '%s' is not a directory.%n", directory);
      return 1;
    }

    if (port < 1 || port > 65535) {
      System.err.printf("Error: Invalid port %d. Port must be between 1 and 65535.%n", port);
      return 1;
    }

    var absDir = directory.toAbsolutePath().normalize();
    var addr = new InetSocketAddress(bind, port);
    var outputLevel = verbose ? OutputLevel.VERBOSE : OutputLevel.INFO;

    HttpServer server;
    Path tempKeystore = null;

    try {
      HttpHandler fileHandler = SimpleFileServer.createFileHandler(absDir);

      HttpHandler finalHandler;
      if (spaMode) {
        finalHandler = exchange -> {
          var reqPath = exchange.getRequestURI().getPath();
          var relPath = reqPath.startsWith("/") ? reqPath.substring(1) : reqPath;
          var targetFile = absDir.resolve(relPath).normalize();

          if (!targetFile.startsWith(absDir)
              || (!Files.exists(targetFile) && Files.exists(absDir.resolve("index.html")))) {
            var indexPath = absDir.resolve("index.html");
            var bytes = Files.readAllBytes(indexPath);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
              os.write(bytes);
            }
            return;
          }
          fileHandler.handle(exchange);
        };
      } else {
        finalHandler = fileHandler;
      }

      var logFilter = SimpleFileServer.createOutputFilter(System.out, outputLevel);

      if (ssl) {
        tempKeystore = Path.of(System.getProperty("java.io.tmpdir", "/tmp"),
            "serve_ks_" + System.currentTimeMillis() + "_" + System.nanoTime() + ".p12");
        if (!generateSelfSignedKeystore(tempKeystore)) {
          System.err
              .println("Error: Failed to generate on-the-fly SSL certificate using JDK keytool.");
          return 1;
        }

        var ks = KeyStore.getInstance("PKCS12");
        try (var is = Files.newInputStream(tempKeystore)) {
          ks.load(is, "changeit".toCharArray());
        }

        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());

        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        var httpsServer = HttpsServer.create(addr, 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        server = httpsServer;
      } else {
        server = HttpServer.create(addr, 0);
      }

      var context = server.createContext("/", finalHandler);
      context.getFilters().add(logFilter);

      if (authCredentials != null && authCredentials.contains(":")) {
        var expectedAuth = "Basic "
            + Base64.getEncoder().encodeToString(authCredentials.getBytes(StandardCharsets.UTF_8));
        Filter authFilter = new Filter() {
          @Override
          public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            var authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (authHeader == null || !authHeader.equals(expectedAuth)) {
              exchange.getResponseHeaders().set("WWW-Authenticate",
                  "Basic realm=\"Access to serve\"");
              exchange.sendResponseHeaders(401, -1);
              return;
            }
            chain.doFilter(exchange);
          }

          @Override
          public String description() {
            return "Basic Auth Filter";
          }
        };
        context.getFilters().add(authFilter);
      }

      if (download) {
        Filter downloadFilter = new Filter() {
          @Override
          public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            exchange.getResponseHeaders().set("Content-Disposition", "attachment");
            chain.doFilter(exchange);
          }

          @Override
          public String description() {
            return "Force Download Filter";
          }
        };
        context.getFilters().add(downloadFilter);
      }
    } catch (Exception e) {
      System.err.printf("Error creating file server: %s%n", e.getMessage());
      return 1;
    }

    final var finalTempKeystore = tempKeystore;
    var protocol = ssl ? "HTTPS" : "HTTP";
    var scheme = ssl ? "https" : "http";
    var displayHost = "0.0.0.0".equals(bind) || "::".equals(bind) ? "localhost" : bind;

    System.out.printf("Serving %s on %s port %d (%s://%s:%d/) ...%n", protocol, bind, port, scheme,
        displayHost, port);
    System.out.printf("Document root: %s%n", absDir);

    if (ssl) {
      System.out
          .println("Security: On-the-fly self-signed TLS/SSL certificate enabled (CN=localhost)");
    }
    if (download) {
      System.out.println("Mode: Force download enabled (Content-Disposition: attachment)");
    }
    if (spaMode) {
      System.out.println("Mode: Single Page Application (SPA) fallback to index.html enabled");
    }
    if (authCredentials != null && authCredentials.contains(":")) {
      System.out.println("Auth: Basic Authentication enabled");
    }

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("\nStopping server...");
      server.stop(0);
      if (finalTempKeystore != null) {
        try {
          Files.deleteIfExists(finalTempKeystore);
        } catch (IOException ignored) {
        }
      }
    }));

    try {
      server.start();
    } catch (UncheckedIOException e) {
      if (e.getCause() instanceof BindException) {
        System.err.printf("Error: Could not bind to port %d on %s (Address already in use).%n",
            port, bind);
      } else {
        System.err.printf("Error starting server: %s%n", e.getMessage());
      }
      return 1;
    } catch (Exception e) {
      System.err.printf("Error starting server: %s%n", e.getMessage());
      return 1;
    }

    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    return 0;
  }

  /**
   * Generates a temporary PKCS12 self-signed certificate keystore using JDK keytool.
   *
   * @param keystorePath Output keystore file path.
   * @return {@code true} if keytool execution succeeded, {@code false} otherwise.
   */
  private boolean generateSelfSignedKeystore(Path keystorePath) {
    try {
      Files.deleteIfExists(keystorePath);

      var javaHome = System.getProperty("java.home", "");
      var keytoolBin = Path.of(javaHome, "bin", "keytool").toString();
      if (!Files.exists(Path.of(keytoolBin)) && !Files.exists(Path.of(keytoolBin + ".exe"))) {
        keytoolBin = Path.of(javaHome, "keytool").toString();
      }
      if (!Files.exists(Path.of(keytoolBin)) && !Files.exists(Path.of(keytoolBin + ".exe"))) {
        keytoolBin = "keytool";
      }

      var pb = new ProcessBuilder(keytoolBin, "-genkeypair", "-alias", "selfsigned", "-keyalg",
          "RSA", "-keysize", "2048", "-validity", "365", "-keystore",
          keystorePath.toAbsolutePath().toString(), "-storepass", "changeit", "-noprompt", "-dname",
          "CN=localhost");

      var process = pb.start();
      return process.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /** Resolves positional arguments to determine directory and port options. */
  private void resolveArguments() {
    var defaultPort = ssl ? 8443 : 8080;

    if (directory == null && port == null) {
      if (positionalArgs.size() == 1) {
        String arg = positionalArgs.get(0);
        if (isInteger(arg)) {
          port = Integer.parseInt(arg);
          directory = Path.of(".");
        } else {
          directory = Path.of(arg);
          port = defaultPort;
        }
      } else if (positionalArgs.size() >= 2) {
        String arg1 = positionalArgs.get(0);
        String arg2 = positionalArgs.get(1);

        if (!isInteger(arg1) && isInteger(arg2)) {
          directory = Path.of(arg1);
          port = Integer.parseInt(arg2);
        } else if (isInteger(arg1) && !isInteger(arg2)) {
          port = Integer.parseInt(arg1);
          directory = Path.of(arg2);
        } else if (isInteger(arg1) && isInteger(arg2)) {
          port = Integer.parseInt(arg1);
          directory = Path.of(".");
        } else {
          directory = Path.of(arg1);
          port = defaultPort;
        }
      } else {
        directory = Path.of(".");
        port = defaultPort;
      }
    } else if (directory == null) {
      directory = Path.of(".");
    } else if (port == null) {
      port = defaultPort;
    }
  }
}
