///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//DEPS dev.tamboui:tamboui-tui:0.4.0
//DEPS dev.tamboui:tamboui-jline3-backend:0.4.0
//DEPS dev.tamboui:tamboui-widgets:0.4.0
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//NATIVE_OPTIONS -O2 --no-fallback

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.concurrent.Callable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.style.Style;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.paragraph.Paragraph;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Reach is an advanced network diagnostic CLI tool to test TCP connectivity, measure handshake
 * latency, inspect TLS/SSL certificates, probe HTTP/HTTPS status, and render an interactive TUI.
 */
@Command(name = "reach", mixinStandardHelpOptions = true, version = "reach 1.3",
    description = "Network diagnostic CLI utility to test TCP reachability and inspect TLS certs.")
@SuppressWarnings("unused")
class Reach implements Callable<Integer> {

  @Parameters(index = "0", description = "Target host, host:port, or IP address.")
  private String target;

  @Parameters(index = "1", arity = "0..1",
      description = "Port, list (80,443), or range (80-85). Default: 80 or 443.")
  private String portSpec;

  @Option(names = {"-4", "--ipv4"}, description = "Force IPv4 address resolution.")
  private boolean ipv4Only;

  @Option(names = {"-6", "--ipv6"}, description = "Force IPv6 address resolution.")
  private boolean ipv6Only;

  @Option(names = {"-n", "--count"}, defaultValue = "4",
      description = "Number of probe attempts per port (default: 4).")
  private int count = 4;

  @Option(names = {"-c", "--continuous"},
      description = "Continuous probing until stopped via Ctrl+C.")
  private boolean continuous;

  @Option(names = {"--tui"}, description = "Launch full-screen interactive TamboUI dashboard.")
  private boolean tuiMode;

  @Option(names = {"-t", "--timeout"}, defaultValue = "2000",
      description = "Connection timeout in milliseconds (default: 2000).")
  private int timeout = 2000;

  @Option(names = {"-i", "--interval"}, defaultValue = "1000",
      description = "Interval between probes in milliseconds (default: 1000).")
  private int interval = 1000;

  @Option(names = {"-s", "--ssl", "--tls"}, description = "Force TLS/SSL certificate inspection.")
  private Boolean forceSsl;

  @Option(names = {"-H", "--http"},
      description = "Probe Layer 7 HTTP/HTTPS status code and Time-To-First-Byte (TTFB).")
  private boolean checkHttp;

  @Option(names = {"-j", "--json"}, description = "Output diagnostic results in JSON format.")
  private boolean jsonOutput;

  @Option(names = {"-w", "--warn-days"},
      description = "Exit code 2 if SSL certificate expires within specified days threshold.")
  private Integer warnDaysThreshold;

  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

  /** Record carrying TLS inspection results. */
  private record TlsInfo(String subject, String issuer, Instant notAfter, long daysRemaining,
      String protocol, String cipherSuite, String error) {}

  /** Record carrying HTTP probe results. */
  private record HttpInfo(int statusCode, double ttfbMs, String serverHeader, String error) {}

  /** Record carrying overall single-port probe results. */
  private record PortResult(int port, boolean isSsl, TlsInfo tls, HttpInfo http, int transmitted,
      int received, double lossPercent, double minRtt, double avgRtt, double maxRtt) {}

  void main(String... args) {
    int exitCode = new CommandLine(this).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws Exception {
    var host = target;
    String rawPortStr = null;

    if (target.contains(":")) {
      var parts = target.split(":", 2);
      host = parts[0];
      rawPortStr = parts[1];
    } else if (portSpec != null && !portSpec.isBlank()) {
      rawPortStr = portSpec;
    }

    List<Integer> ports = parsePorts(rawPortStr, Boolean.TRUE.equals(forceSsl));
    if (ports.isEmpty()) {
      System.err.println("Error: No valid ports specified.");
      return 1;
    }

    // 1. DNS Resolution
    var dnsStart = System.nanoTime();
    InetAddress address;
    try {
      var allAddresses = InetAddress.getAllByName(host);
      address = Arrays.stream(allAddresses).filter(addr -> {
        if (ipv4Only)
          return addr instanceof Inet4Address;
        if (ipv6Only)
          return addr instanceof Inet6Address;
        return true;
      }).findFirst().orElse(null);

      if (address == null) {
        var mode = ipv4Only ? "IPv4" : "IPv6";
        System.err.println("Error: No " + mode + " address found for host '" + host + "'");
        return 1;
      }
    } catch (UnknownHostException e) {
      System.err.println("Error: Could not resolve hostname '" + host + "'");
      return 1;
    }
    var dnsTimeMs = (System.nanoTime() - dnsStart) / 1_000_000.0;

    // Interactive TamboUI Mode
    if (tuiMode) {
      runTuiDashboard(host, address, ports, dnsTimeMs);
      return 0;
    }

    var overallSuccess = true;
    var certWarningTriggered = false;
    List<PortResult> results = new ArrayList<>();

    if (!jsonOutput) {
      System.out.println("REACH " + host + " [" + address.getHostAddress() + "]");
      System.out.printf("DNS: Resolved %s -> %s in %.2f ms%n", host, address.getHostAddress(),
          dnsTimeMs);
    }

    for (var port : ports) {
      var isSsl = Boolean.TRUE.equals(forceSsl) || (forceSsl == null && port == 443);

      TlsInfo tlsInfo = null;
      if (isSsl) {
        tlsInfo = inspectTls(host, port);
        if (warnDaysThreshold != null && tlsInfo != null
            && tlsInfo.daysRemaining() < warnDaysThreshold) {
          certWarningTriggered = true;
        }
      }

      HttpInfo httpInfo = null;
      if (checkHttp) {
        httpInfo = inspectHttp(host, port, isSsl);
      }

      if (!jsonOutput) {
        if (isSsl && tlsInfo != null) {
          if (tlsInfo.error() == null) {
            System.out.println("\nTLS: Port " + port + " Certificate OK");
            System.out.println(" ├─ Subject: " + tlsInfo.subject());
            System.out.println(" ├─ Issuer:  " + tlsInfo.issuer());
            System.out.println(" ├─ Valid Until: " + DATE_FORMATTER.format(tlsInfo.notAfter())
                + " (" + tlsInfo.daysRemaining() + " days remaining)");
            System.out.println(
                " └─ Protocol: " + tlsInfo.protocol() + " / Cipher: " + tlsInfo.cipherSuite());
          } else {
            System.out
                .println("\nTLS: Port " + port + " Certificate Failed (" + tlsInfo.error() + ")");
          }
        }

        if (checkHttp && httpInfo != null) {
          if (httpInfo.error() == null) {
            var serverStr =
                httpInfo.serverHeader() != null ? " [Server: " + httpInfo.serverHeader() + "]" : "";
            System.out.printf("%nHTTP: Port %d -> %d (TTFB: %.2f ms)%s%n", port,
                httpInfo.statusCode(), httpInfo.ttfbMs(), serverStr);
          } else {
            System.out
                .println("\nHTTP: Port " + port + " Request Failed (" + httpInfo.error() + ")");
          }
        }

        System.out.println();
      }

      // TCP Probing Loop for this port
      List<Double> rtts = new ArrayList<>();
      var transmitted = 0;
      var received = 0;

      var attempts = continuous ? Integer.MAX_VALUE : count;

      for (var i = 0; i < attempts; i++) {
        transmitted++;
        var connectStart = System.nanoTime();
        try (var socket = new Socket()) {
          socket.connect(new InetSocketAddress(address, port), timeout);
          var rttMs = (System.nanoTime() - connectStart) / 1_000_000.0;
          rtts.add(rttMs);
          received++;
          if (!jsonOutput) {
            System.out.printf("Connected to %s:%d: tcp_seq=%d time=%.2f ms%n",
                address.getHostAddress(), port, i + 1, rttMs);
          }
        } catch (IOException e) {
          if (!jsonOutput) {
            System.out.printf("Connection to %s:%d: tcp_seq=%d timeout/refused (%s)%n",
                address.getHostAddress(), port, i + 1, e.getMessage());
          }
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

      var lossPercent =
          transmitted > 0 ? ((transmitted - received) / (double) transmitted) * 100.0 : 0.0;
      DoubleSummaryStatistics stats =
          rtts.stream().mapToDouble(Double::doubleValue).summaryStatistics();

      var minRtt = rtts.isEmpty() ? 0.0 : stats.getMin();
      var avgRtt = rtts.isEmpty() ? 0.0 : stats.getAverage();
      var maxRtt = rtts.isEmpty() ? 0.0 : stats.getMax();

      if (received == 0) {
        overallSuccess = false;
      }

      PortResult result = new PortResult(port, isSsl, tlsInfo, httpInfo, transmitted, received,
          lossPercent, minRtt, avgRtt, maxRtt);
      results.add(result);

      if (!jsonOutput) {
        System.out.printf("%n--- %s:%d reach statistics ---%n", host, port);
        System.out.printf("%d probes transmitted, %d received, %.1f%% packet loss%n", transmitted,
            received, lossPercent);
        if (!rtts.isEmpty()) {
          System.out.printf("rtt min/avg/max = %.2f/%.2f/%.2f ms%n", minRtt, avgRtt, maxRtt);
        }
      }
    }

    if (jsonOutput) {
      printJsonOutput(host, address.getHostAddress(), dnsTimeMs, results);
    }

    if (certWarningTriggered) {
      if (!jsonOutput) {
        System.err.println("\nWarning: One or more SSL certificates expire within "
            + warnDaysThreshold + " days!");
      }
      return 2;
    }

    return overallSuccess ? 0 : 1;
  }

  /**
   * Launches full-screen interactive TamboUI TUI dashboard.
   */
  private void runTuiDashboard(String host, InetAddress address, List<Integer> ports,
      double dnsTimeMs) throws Exception {

    var primaryPort = ports.get(0);
    var isSsl = Boolean.TRUE.equals(forceSsl) || (forceSsl == null && primaryPort == 443);
    var tlsInfo = isSsl ? inspectTls(host, primaryPort) : null;
    var httpInfo = checkHttp ? inspectHttp(host, primaryPort, isSsl) : null;

    List<String> probeLogs = new ArrayList<>();
    List<Double> rtts = new ArrayList<>();
    final var transmitted = new int[] {0};
    final var received = new int[] {0};
    final var isPaused = new boolean[] {false};

    var config = TuiConfig.builder().tickRate(Duration.ofMillis(500)).build();

    try (var tui = TuiRunner.create(config)) {
      tui.run((event, runner) -> {
        if (event instanceof KeyEvent k) {
          if (k.isQuit() || k.isChar('q')) {
            runner.quit();
            return true;
          }
          if (k.isChar(' ')) {
            isPaused[0] = !isPaused[0];
            return true;
          }
          if (k.isChar('r')) {
            probeLogs.clear();
            rtts.clear();
            transmitted[0] = 0;
            received[0] = 0;
            return true;
          }
        }
        return false;
      }, frame -> {
        if (!isPaused[0]) {
          transmitted[0]++;
          var seq = transmitted[0];
          var start = System.nanoTime();
          try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, primaryPort), timeout);
            var rttMs = (System.nanoTime() - start) / 1_000_000.0;
            rtts.add(rttMs);
            received[0]++;
            probeLogs.add(String.format("Connected to %s:%d: tcp_seq=%d time=%.2f ms",
                address.getHostAddress(), primaryPort, seq, rttMs));
          } catch (IOException e) {
            probeLogs.add(String.format("Connection to %s:%d: tcp_seq=%d timeout/refused (%s)",
                address.getHostAddress(), primaryPort, seq, e.getMessage()));
          }

          if (probeLogs.size() > 12) {
            probeLogs.remove(0);
          }
        }

        var chunks = Layout.vertical()
            .constraints(Constraint.length(3), Constraint.fill(1), Constraint.length(3))
            .split(frame.area());

        var headerText = String.format(" REACH: %s [%s:%d]  |  DNS: %.2f ms  |  Status: %s", host,
            address.getHostAddress(), primaryPort, dnsTimeMs, isPaused[0] ? "PAUSED" : "ACTIVE");

        var headerBlock =
            Block.builder().title(" Network Prober ").style(Style.create().cyan().bold()).build();

        frame.renderWidget(
            Paragraph.builder().text(Text.from(headerText)).block(headerBlock).build(),
            chunks.get(0));

        var bodyChunks = Layout.horizontal()
            .constraints(Constraint.percentage(45), Constraint.percentage(55)).split(chunks.get(1));

        var infoSb = new StringBuilder();
        infoSb.append("Target: ").append(host).append("\n");
        infoSb.append("IP: ").append(address.getHostAddress()).append("\n");
        infoSb.append("Port: ").append(primaryPort).append("\n\n");

        if (tlsInfo != null) {
          infoSb.append("=== TLS Certificate ===\n");
          if (tlsInfo.error() == null) {
            infoSb.append("Subject: ").append(tlsInfo.subject()).append("\n");
            infoSb.append("Issuer:  ").append(tlsInfo.issuer()).append("\n");
            infoSb.append("Expires: ").append(tlsInfo.daysRemaining()).append(" days remaining\n");
            infoSb.append("Cipher:  ").append(tlsInfo.cipherSuite()).append("\n");
          } else {
            infoSb.append("Error: ").append(tlsInfo.error()).append("\n");
          }
        }

        if (httpInfo != null) {
          infoSb.append("\n=== HTTP Probe ===\n");
          if (httpInfo.error() == null) {
            infoSb.append("Status: ").append(httpInfo.statusCode()).append("\n");
            infoSb.append("TTFB:   ").append(String.format("%.2f ms", httpInfo.ttfbMs()))
                .append("\n");
          } else {
            infoSb.append("Error: ").append(httpInfo.error()).append("\n");
          }
        }

        var infoBlock = Block.builder().title(" Connection & Security Details ")
            .style(Style.create().green()).build();

        frame.renderWidget(
            Paragraph.builder().text(Text.from(infoSb.toString())).block(infoBlock).build(),
            bodyChunks.get(0));

        var logSb = new StringBuilder();
        for (var logLine : probeLogs) {
          logSb.append(logLine).append("\n");
        }

        var lossPercent =
            transmitted[0] > 0 ? ((transmitted[0] - received[0]) / (double) transmitted[0]) * 100.0
                : 0.0;
        DoubleSummaryStatistics rttStats =
            rtts.stream().mapToDouble(Double::doubleValue).summaryStatistics();

        logSb.append("\n--- Stats ---\n");
        logSb.append(String.format("Tx/Rx: %d/%d (%.1f%% loss)\n", transmitted[0], received[0],
            lossPercent));
        if (!rtts.isEmpty()) {
          logSb.append(String.format("RTT min/avg/max: %.1f/%.1f/%.1f ms\n", rttStats.getMin(),
              rttStats.getAverage(), rttStats.getMax()));
        }

        var logBlock =
            Block.builder().title(" Live TCP Probes ").style(Style.create().yellow()).build();

        frame.renderWidget(
            Paragraph.builder().text(Text.from(logSb.toString())).block(logBlock).build(),
            bodyChunks.get(1));

        var footerBlock = Block.builder().style(Style.create().magenta()).build();

        frame.renderWidget(Paragraph.builder()
            .text(Text.from(" Controls: [q] Quit  |  [Space] Pause/Resume  |  [r] Reset Stats"))
            .block(footerBlock).build(), chunks.get(2));
      });
    }
  }

  private List<Integer> parsePorts(String rawPortStr, boolean isSslForced) {
    if (rawPortStr == null || rawPortStr.isBlank()) {
      return List.of(isSslForced ? 443 : 80);
    }

    List<Integer> ports = new ArrayList<>();
    for (var part : rawPortStr.split(",")) {
      var trimmed = part.trim();
      if (trimmed.contains("-")) {
        var range = trimmed.split("-", 2);
        try {
          var start = Integer.parseInt(range[0].trim());
          var end = Integer.parseInt(range[1].trim());
          for (var p = Math.min(start, end); p <= Math.max(start, end); p++) {
            ports.add(p);
          }
        } catch (NumberFormatException ignored) {
        }
      } else {
        try {
          ports.add(Integer.parseInt(trimmed));
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return ports;
  }

  private TlsInfo inspectTls(String host, int port) {
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
          var notAfter = cert.getNotAfter().toInstant();
          var daysRemaining = Duration.between(Instant.now(), notAfter).toDays();
          return new TlsInfo(cert.getSubjectX500Principal().getName(),
              cert.getIssuerX500Principal().getName(), notAfter, daysRemaining,
              sslSocket.getSession().getProtocol(), sslSocket.getSession().getCipherSuite(), null);
        } else {
          return new TlsInfo(null, null, null, 0, null, null, "No X509 certificate found");
        }
      }
    } catch (Exception e) {
      return new TlsInfo(null, null, null, 0, null, null, e.getMessage());
    }
  }

  private HttpInfo inspectHttp(String host, int port, boolean isSsl) {
    try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofMillis(timeout)).build()) {

      var scheme = isSsl ? "https://" : "http://";
      var portPart = (isSsl && port == 443) || (!isSsl && port == 80) ? "" : ":" + port;
      var uri = URI.create(scheme + host + portPart + "/");

      var request = HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody())
          .timeout(Duration.ofMillis(timeout)).header("User-Agent", "jbang-reach").build();

      var start = System.nanoTime();
      var response = client.send(request, HttpResponse.BodyHandlers.discarding());
      var ttfbMs = (System.nanoTime() - start) / 1_000_000.0;

      var serverHeader = response.headers().firstValue("server").orElse(null);
      return new HttpInfo(response.statusCode(), ttfbMs, serverHeader, null);
    } catch (Exception e) {
      return new HttpInfo(0, 0.0, null, e.getMessage());
    }
  }

  private void printJsonOutput(String host, String ip, double dnsTimeMs, List<PortResult> results) {
    var sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"target\": \"").append(escapeJson(host)).append("\",\n");
    sb.append("  \"ip\": \"").append(escapeJson(ip)).append("\",\n");
    sb.append("  \"dns_time_ms\": ").append(String.format("%.2f", dnsTimeMs)).append(",\n");
    sb.append("  \"ports\": [\n");

    for (var i = 0; i < results.size(); i++) {
      var res = results.get(i);
      sb.append("    {\n");
      sb.append("      \"port\": ").append(res.port()).append(",\n");
      sb.append("      \"ssl\": ").append(res.isSsl()).append(",\n");
      sb.append("      \"transmitted\": ").append(res.transmitted()).append(",\n");
      sb.append("      \"received\": ").append(res.received()).append(",\n");
      sb.append("      \"loss_percent\": ").append(String.format("%.1f", res.lossPercent()))
          .append(",\n");
      sb.append("      \"rtt_min_ms\": ").append(String.format("%.2f", res.minRtt())).append(",\n");
      sb.append("      \"rtt_avg_ms\": ").append(String.format("%.2f", res.avgRtt())).append(",\n");
      sb.append("      \"rtt_max_ms\": ").append(String.format("%.2f", res.maxRtt()));

      if (res.tls() != null) {
        sb.append(",\n      \"tls\": {\n");
        if (res.tls().error() == null) {
          sb.append("        \"subject\": \"").append(escapeJson(res.tls().subject()))
              .append("\",\n");
          sb.append("        \"issuer\": \"").append(escapeJson(res.tls().issuer()))
              .append("\",\n");
          sb.append("        \"valid_until\": \"").append(res.tls().notAfter()).append("\",\n");
          sb.append("        \"days_remaining\": ").append(res.tls().daysRemaining()).append(",\n");
          sb.append("        \"protocol\": \"").append(escapeJson(res.tls().protocol()))
              .append("\",\n");
          sb.append("        \"cipher\": \"").append(escapeJson(res.tls().cipherSuite()))
              .append("\"\n");
        } else {
          sb.append("        \"error\": \"").append(escapeJson(res.tls().error())).append("\"\n");
        }
        sb.append("      }");
      }

      if (res.http() != null) {
        sb.append(",\n      \"http\": {\n");
        if (res.http().error() == null) {
          sb.append("        \"status\": ").append(res.http().statusCode()).append(",\n");
          sb.append("        \"ttfb_ms\": ").append(String.format("%.2f", res.http().ttfbMs()))
              .append(",\n");
          sb.append("        \"server\": ")
              .append(res.http().serverHeader() != null
                  ? "\"" + escapeJson(res.http().serverHeader()) + "\""
                  : "null")
              .append("\n");
        } else {
          sb.append("        \"error\": \"").append(escapeJson(res.http().error())).append("\"\n");
        }
        sb.append("      }");
      }

      sb.append("\n    }").append(i < results.size() - 1 ? "," : "").append("\n");
    }

    sb.append("  ]\n");
    sb.append("}\n");
    System.out.print(sb.toString());
  }

  private String escapeJson(String str) {
    if (str == null)
      return "";
    return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r",
        "\\r");
  }
}
