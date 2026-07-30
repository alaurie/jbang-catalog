///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.SimpleFileServer;
import com.sun.net.httpserver.SimpleFileServer.OutputLevel;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "serve", mixinStandardHelpOptions = true, version = "serve 1.0",
    description = "Simple HTTP file server inspired by python -m http.server")
class serve implements Callable<Integer> {

  @Option(names = {"-p", "--port"}, description = "Port to listen on (default: 8080)")
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

  @Parameters(arity = "0..2", paramLabel = "[dirOrPort]",
      description = "Optional directory path and/or port number")
  private List<String> positionalArgs = new ArrayList<>();

  void main(String... args) {
    int exitCode = new CommandLine(this).execute(args);
    System.exit(exitCode);
  }

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
    try {
      var handler = SimpleFileServer.createFileHandler(absDir);
      var logFilter = SimpleFileServer.createOutputFilter(System.out, outputLevel);

      server = HttpServer.create(addr, 0);
      var context = server.createContext("/", handler);
      context.getFilters().add(logFilter);

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

    var displayHost = "0.0.0.0".equals(bind) || "::".equals(bind) ? "localhost" : bind;
    System.out.printf("Serving HTTP on %s port %d (http://%s:%d/) ...%n", bind, port, displayHost,
        port);
    System.out.printf("Document root: %s%n", absDir);
    if (download) {
      System.out.println("Mode: Force download enabled (Content-Disposition: attachment)");
    }

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("\nStopping server...");
      server.stop(0);
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

  private void resolveArguments() {
    String posDir = null;
    Integer posPort = null;

    for (var arg : positionalArgs) {
      if (isInteger(arg)) {
        if (posPort == null) {
          posPort = Integer.parseInt(arg);
        } else if (posDir == null) {
          posDir = arg;
        }
      } else {
        if (posDir == null) {
          posDir = arg;
        }
      }
    }

    if (this.directory == null) {
      this.directory = posDir != null ? Path.of(posDir) : Path.of(".");
    }
    if (this.port == null) {
      this.port = posPort != null ? posPort : 8080;
    }
  }

  private static boolean isInteger(String s) {
    try {
      Integer.parseInt(s);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
