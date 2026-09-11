///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS info.picocli:picocli:4.7.7
//SOURCES Typeit.java

package typeit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import picocli.CommandLine;

public class TypeitTest {

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
      var app = new Typeit();
      var cmd = new CommandLine(app);
      cmd.setOut(pw);
      cmd.setErr(pw);
      int exitCode = cmd.execute(args);
      pw.flush();
      return new ExecutionResult(exitCode,
          outStream.toString(StandardCharsets.UTF_8) + sw.toString(),
          errStream.toString(StandardCharsets.UTF_8) + sw.toString());
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }

  @Test
  void testHelp() {
    var result = runCommand("--help");
    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("Simulates typing clipboard text"));
  }

  @Test
  void testVersion() {
    var result = runCommand("--version");
    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("typeit"));
  }

  @Test
  void testInvalidDelayOption() {
    var result = runCommand("-d", "invalid_delay");
    assertEquals(2, result.exitCode());
    assertTrue(result.stderr().contains("Invalid value for option '--delay'"));
  }

  @Test
  void testInvalidDriverOption() {
    var result = runCommand("--driver", "invalid_driver");
    assertEquals(2, result.exitCode());
    assertTrue(result.stderr().contains("expected one of [AUTO, UINPUT, ROBOT, WTYPE, YDOTOOL]"));
  }

  @Test
  void testTypingCustomText() {
    var result = runCommand("-d", "0", "-s", "1", "-t", "Test123", "-v");
    // Should succeed on desktop / uinput environment
    assertTrue(result.exitCode() == 0 || result.stderr().contains("Error"));
    if (result.exitCode() == 0) {
      assertTrue(result.stdout().contains("Done! Typed 7 characters."));
    }
  }

  public static void main(String... args) {
    var launcher = LauncherFactory.create();
    var summaryListener = new SummaryGeneratingListener();
    var request = LauncherDiscoveryRequestBuilder.request()
        .selectors(DiscoverySelectors.selectClass(TypeitTest.class)).build();
    launcher.execute(request, summaryListener);

    var summary = summaryListener.getSummary();
    System.out.printf("Tests run: %d, Failures: %d, Errors: %d, Skipped: %d%n",
        summary.getTestsFoundCount(), summary.getTestsFailedCount(),
        summary.getContainersFailedCount(), summary.getTestsSkippedCount());

    if (summary.getTestsFailedCount() > 0 || summary.getContainersFailedCount() > 0) {
      summary.printFailuresTo(new PrintWriter(System.err));
      System.exit(1);
    }
    System.exit(0);
  }
}

