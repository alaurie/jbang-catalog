///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS info.picocli:picocli:4.7.7
//SOURCES Digest.java

package digest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import picocli.CommandLine;

public class DigestTest {

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
      var app = new Digest();
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
    assertTrue(result.stdout().contains("Compute and verify cryptographic checksums"));
  }

  @Test
  void testVersion() {
    var result = runCommand("--version");
    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("digest"));
  }

  @Test
  void testTextHashingSha256() {
    var result = runCommand("-t", "hello world", "-a", "SHA-256");
    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().toLowerCase()
        .contains("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"));
  }

  @Test
  void testTextHashingSha512() {
    var result = runCommand("-t", "hello", "-a", "SHA-512");
    assertEquals(0, result.exitCode());
    // sha512("hello") = 9b71d224bd62f3785d96d46ad3ea3d73319bfbc2890caadae2dff72519673ca72323c3d99ba5c11d7c7acc6e14b8c5da0c4663475c2e5c3adef46f73bcdec043
    assertTrue(result.stdout().toLowerCase()
        .contains("9b71d224bd62f3785d96d46ad3ea3d73319bfbc2890caadae2dff72519673ca7"));
  }

  @Test
  void testFileHashingAndVerification(@TempDir Path tempDir) throws Exception {
    Path testFile = tempDir.resolve("sample.txt");
    Files.writeString(testFile, "test content for hashing");

    var hashResult = runCommand(testFile.toString(), "-a", "SHA-256");
    assertEquals(0, hashResult.exitCode());
    assertTrue(hashResult.stdout().contains("sample.txt"));

    Path checksumFile = tempDir.resolve("sample.sha256");
    Files.writeString(checksumFile, hashResult.stdout());

    var verifyResult = runCommand("-c", checksumFile.toString());
    assertEquals(0, verifyResult.exitCode());
    assertTrue(verifyResult.stdout().contains("OK"));
  }

  @Test
  void testDirectoryHashingRecursive(@TempDir Path tempDir) throws Exception {
    Path dir = tempDir.resolve("sub");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("a.txt"), "hello A");
    Files.writeString(dir.resolve("b.txt"), "hello B");

    var result = runCommand("-r", dir.toString(), "-a", "SHA-256");
    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("a.txt"));
    assertTrue(result.stdout().contains("b.txt"));
  }

  public static void main(String... args) {
    var launcher = LauncherFactory.create();
    var summaryListener = new SummaryGeneratingListener();
    var request = LauncherDiscoveryRequestBuilder.request()
        .selectors(DiscoverySelectors.selectClass(DigestTest.class)).build();
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
