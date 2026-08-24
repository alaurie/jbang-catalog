///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//NATIVE_OPTIONS -O2 --no-fallback

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.concurrent.Callable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Reach is a network diagnostic CLI tool to test TCP connectivity, measure handshake latency,
 * inspect TLS/SSL certificates, and track connection stats.
 */
@Command(name = "reach", mixinStandardHelpOptions = true, version = "reach 1.0",
    description = "Network diagnostic CLI utility to test TCP reachability and inspect TLS certs.")
@SuppressWarnings("unused")
class Reach implements Callable<Integer> {

  @Parameters(index = "0", description = "Target host, host:port, or IP address.")
  private String target;

  @Parameters(index = "1", arity = "0..1", description = "Port number (default: 80 or 443).")
  private Integer portParam;

  @Option(names = {"-n", "--count"}, defaultValue = "4",
      description = "Number of probe attempts (default: 4).")
  private int count = 4;

  @Option(names = {"-c", "--continuous"},
      description = "Continuous probing until stopped via Ctrl+C.")
  private boolean continuous;

  @Option(names = {"-t", "--timeout"}, defaultValue = "2000",
      description = "Connection timeout in milliseconds (default: 2000).")
  private int timeout = 2000;

  @Option(names = {"-i", "--interval"}, defaultValue = "1000",
      description = "Interval between probes in milliseconds (default: 1000).")
  private int interval = 1000;

  @Option(names = {"-s", "--ssl", "--tls"}, description = "Force TLS/SSL certificate inspection.")
  private Boolean forceSsl;

  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

  /**
   * Main entry point using Java 25 instance main convention.
   *
   * @param args Command line arguments
   */
  void main(String... args) {
    int exitCode = new CommandLine(this).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws Exception {
    var host = target;
    var port = 80;

    if (target.contains(":")) {
      var parts = target.split(":", 2);
      host = parts[0];
      try {
        port = Integer.parseInt(parts[1]);
      } catch (NumberFormatException e) {
        System.err.println("Error: Invalid port in target specifier: " + parts[1]);
        return 1;
      }
    } else if (portParam != null) {
      port = portParam;
    } else if (Boolean.TRUE.equals(forceSsl)) {
      port = 443;
    }

    var isSsl = Boolean.TRUE.equals(forceSsl) || (forceSsl == null && port == 443);

    System.out.println("REACH " + host + ":" + port);

    // 1. DNS Resolution
    var dnsStart = System.nanoTime();
    InetAddress address;
    try {
      address = InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      System.err.println("Error: Could not resolve hostname '" + host + "'");
      return 1;
    }
    var dnsTimeMs = (System.nanoTime() - dnsStart) / 1_000_000.0;
    System.out.printf("DNS: Resolved %s -> %s in %.2f ms%n", host, address.getHostAddress(),
        dnsTimeMs);

    // 2. Optional TLS Cert Inspection
    if (isSsl) {
      inspectTls(host, port);
    }

    System.out.println();

    // 3. TCP Probing Loop
    List<Double> rtts = new ArrayList<>();
    var stats = new int[2]; // stats[0] = transmitted, stats[1] = received

    final var finalHost = host;
    final var finalPort = port;

    var shutdownHook =
        new Thread(() -> printSummary(finalHost, finalPort, rtts, stats[0], stats[1]));
    Runtime.getRuntime().addShutdownHook(shutdownHook);

    var attempts = continuous ? Integer.MAX_VALUE : count;

    try {
      for (var i = 0; i < attempts; i++) {
        stats[0]++;
        var connectStart = System.nanoTime();
        try (var socket = new Socket()) {
          socket.connect(new InetSocketAddress(address, port), timeout);
          var rttMs = (System.nanoTime() - connectStart) / 1_000_000.0;
          rtts.add(rttMs);
          stats[1]++;
          System.out.printf("Connected to %s:%d: tcp_seq=%d time=%.2f ms%n",
              address.getHostAddress(), port, i + 1, rttMs);
        } catch (IOException e) {
          System.out.printf("Connection to %s:%d: tcp_seq=%d timeout/refused (%s)%n",
              address.getHostAddress(), port, i + 1, e.getMessage());
        }

        if (i < attempts - 1) {
          try {
            Thread.sleep(interval);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // Hook is already running via Ctrl+C / SIGINT
      }
    }

    printSummary(finalHost, finalPort, rtts, stats[0], stats[1]);
    return stats[1] > 0 ? 0 : 1;
  }

  /**
   * Connects via TLS to inspect peer certificate issuer, expiration date, and negotiated protocol.
   *
   * @param host Target host name
   * @param port Target port number
   */
  private void inspectTls(String host, int port) {
    System.out.print("TLS: Inspecting certificate... ");
    try {
      TrustManager[] trustAll = new TrustManager[] {new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0];
        }

        public void checkClientTrusted(X509Certificate[] certs, String authType) {}

        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
      }};

      var sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, trustAll, new java.security.SecureRandom());
      SSLSocketFactory factory = sslContext.getSocketFactory();

      try (var sslSocket = (SSLSocket) factory.createSocket()) {
        sslSocket.connect(new InetSocketAddress(host, port), timeout);
        sslSocket.startHandshake();

        var certs = sslSocket.getSession().getPeerCertificates();
        if (certs.length > 0 && certs[0] instanceof X509Certificate cert) {
          System.out.println("OK");
          System.out.println(" ├─ Subject: " + cert.getSubjectX500Principal().getName());
          System.out.println(" ├─ Issuer:  " + cert.getIssuerX500Principal().getName());

          var notAfter = cert.getNotAfter().toInstant();
          var daysRemaining = Duration.between(Instant.now(), notAfter).toDays();

          System.out.println(" ├─ Valid Until: " + DATE_FORMATTER.format(notAfter) + " ("
              + daysRemaining + " days remaining)");
          System.out.println(" └─ Protocol: " + sslSocket.getSession().getProtocol() + " / Cipher: "
              + sslSocket.getSession().getCipherSuite());
        } else {
          System.out.println("No X509 Certificate found.");
        }
      }
    } catch (Exception e) {
      System.out.println("Failed (" + e.getMessage() + ")");
    }
  }

  /**
   * Prints summary statistics for transmitted and received TCP probes.
   *
   * @param host Target host name
   * @param port Target port number
   * @param rtts List of round-trip time measurements in milliseconds
   * @param transmitted Count of total probes sent
   * @param received Count of total successful probes
   */
  private static void printSummary(String host, int port, List<Double> rtts, int transmitted,
      int received) {
    if (transmitted == 0) {
      return;
    }
    System.out.println();
    System.out.printf("--- %s:%d reach statistics ---%n", host, port);
    var loss = ((transmitted - received) / (double) transmitted) * 100.0;
    System.out.printf("%d probes transmitted, %d received, %.1f%% packet loss%n", transmitted,
        received, loss);

    if (!rtts.isEmpty()) {
      DoubleSummaryStatistics stats =
          rtts.stream().mapToDouble(Double::doubleValue).summaryStatistics();
      System.out.printf("rtt min/avg/max = %.2f/%.2f/%.2f ms%n", stats.getMin(), stats.getAverage(),
          stats.getMax());
    }
  }
}
