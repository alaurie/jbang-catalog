///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS info.picocli:picocli:4.7.7
//SOURCES Reach.java

package reach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import picocli.CommandLine;

public class ReachTest {

  private record ExecutionResult(int exitCode, String stdout, String stderr) {}

  private ExecutionResult runCommand(String... args) {
    var originalOut = System.out;
    var originalErr = System.err;
    var outStream = new ByteArrayOutputStream();
    var errStream = new ByteArrayOutputStream();
    var printOut = new PrintStream(outStream, true, StandardCharsets.UTF_8);
    var printErr = new PrintStream(errStream, true, StandardCharsets.UTF_8);
    var sw = new StringWriter();
    var pw = new PrintWriter(sw);

    try {
      System.setOut(printOut);
      System.setErr(printErr);
      var app = new Reach();
      var cmd = new CommandLine(app);
      cmd.setOut(pw);
      cmd.setErr(pw);
      int exitCode = cmd.execute(args);
      pw.flush();
      return new ExecutionResult(exitCode,
          outStream.toString(StandardCharsets.UTF_8) + sw.toString(),
          errStream.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }

  @Test
  void testHelp() {
    var result = runCommand("--help");
    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("Network diagnostic CLI utility"));
  }

  @Test
  void testVersion() {
    var result = runCommand("--version");
    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("reach"));
  }

  @Test
  void testProbeOpenTcpPort() throws Exception {
    try (var serverSocket = new ServerSocket()) {
      serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
      int port = serverSocket.getLocalPort();

      var result =
          runCommand("127.0.0.1", String.valueOf(port), "-n", "1", "-t", "500", "-i", "10");
      assertEquals(0, result.exitCode());
      assertTrue(result.stdout().contains("Connected to 127.0.0.1:" + port));
    }
  }

  @Test
  void testProbeClosedTcpPort() throws Exception {
    int unusedPort;
    try (var socket = new ServerSocket(0)) {
      unusedPort = socket.getLocalPort();
    } // Socket closed now

    var result =
        runCommand("127.0.0.1", String.valueOf(unusedPort), "-n", "1", "-t", "200", "-i", "10");
    // When port is closed/unreachable, exit code should indicate failure (1)
    assertEquals(1, result.exitCode());
    assertTrue(result.stdout().contains("refused") || result.stdout().contains("timeout")
        || result.stdout().contains("100.0% packet loss"));
  }

  @Test
  void testJsonOutput() throws Exception {
    try (var serverSocket = new ServerSocket()) {
      serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
      int port = serverSocket.getLocalPort();

      var result = runCommand("127.0.0.1", String.valueOf(port), "-n", "1", "-j", "-t", "500");
      assertEquals(0, result.exitCode());
      assertTrue(result.stdout().contains("\"host\": \"127.0.0.1\"")
          || result.stdout().contains("\"host\":\"127.0.0.1\"")
          || result.stdout().contains("127.0.0.1"));
      assertTrue(result.stdout().contains("\"port\": " + port)
          || result.stdout().contains("\"port\":" + port));
    }
  }

  public static void main(String... args) {
    var launcher = LauncherFactory.create();
    var summaryListener = new SummaryGeneratingListener();
    var request = LauncherDiscoveryRequestBuilder.request()
        .selectors(DiscoverySelectors.selectClass(ReachTest.class)).build();
    launcher.execute(request, summaryListener);

    var summary = summaryListener.getSummary();
    System.out.printf("Tests run: %d, Failures: %d, Errors: %d, Skipped: %d%n",
        summary.getTestsFoundCount(), summary.getTestsFailedCount(),
        summary.getContainersFailedCount(), summary.getTestsSkippedCount());

    if (summary.getTestsFailedCount() > 0 || summary.getContainersFailedCount() > 0) {
      summary.printFailuresTo(new PrintWriter(System.err));
      System.exit(1);
    }
  }
}
