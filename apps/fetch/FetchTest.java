///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS info.picocli:picocli:4.7.7
//SOURCES Fetch.java

package fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import picocli.CommandLine;

public class FetchTest {

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
      var app = new Fetch();
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
    assertTrue(result.stdout().contains("High-performance multi-threaded CLI file downloader"));
  }

  @Test
  void testVersion() {
    var result = runCommand("--version");
    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("fetch"));
  }

  @Test
  void testDownloadFileWithExplicitHashVerification(@TempDir Path tempDir) throws Exception {
    byte[] testData =
        "Sample data for Fetch download and checksum test".getBytes(StandardCharsets.UTF_8);
    var md = MessageDigest.getInstance("SHA-256");
    String sha256Hex = HexFormat.of().formatHex(md.digest(testData));

    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/download.dat", exchange -> {
      exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
      exchange.sendResponseHeaders(200, testData.length);
      try (var os = exchange.getResponseBody()) {
        os.write(testData);
      }
    });
    server.start();

    int port = server.getAddress().getPort();
    Path destFile = tempDir.resolve("downloaded.dat");

    try {
      var result = runCommand("http://127.0.0.1:" + port + "/download.dat", "-o",
          destFile.toString(), "--expected-hash", sha256Hex);
      assertEquals(0, result.exitCode());
      assertTrue(Files.exists(destFile));
      assertEquals("Sample data for Fetch download and checksum test", Files.readString(destFile));
      assertTrue(result.stdout().contains("SHA-256") || result.stdout().contains("OK"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void testDownloadFileWithAutoChecksumManifestDiscovery(@TempDir Path tempDir) throws Exception {
    byte[] fileData = "File payload for auto checksum probe".getBytes(StandardCharsets.UTF_8);
    var md = MessageDigest.getInstance("SHA-256");
    String sha256Hex = HexFormat.of().formatHex(md.digest(fileData));
    String manifestContent = sha256Hex + "  pkg.tar.gz\n";

    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/pkg.tar.gz", exchange -> {
      exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
      exchange.sendResponseHeaders(200, fileData.length);
      try (var os = exchange.getResponseBody()) {
        os.write(fileData);
      }
    });
    server.createContext("/SHA256SUMS", exchange -> {
      byte[] manifestBytes = manifestContent.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/plain");
      exchange.sendResponseHeaders(200, manifestBytes.length);
      try (var os = exchange.getResponseBody()) {
        os.write(manifestBytes);
      }
    });
    server.start();

    int port = server.getAddress().getPort();
    Path destFile = tempDir.resolve("pkg.tar.gz");

    try {
      var result =
          runCommand("http://127.0.0.1:" + port + "/pkg.tar.gz", "-o", destFile.toString());
      assertEquals(0, result.exitCode());
      assertTrue(Files.exists(destFile));
      assertEquals("File payload for auto checksum probe", Files.readString(destFile));
      assertTrue(result.stdout().contains("Found manifest")
          || result.stdout().contains("SHA256SUMS") || result.stdout().contains("OK"));
    } finally {
      server.stop(0);
    }
  }

  public static void main(String... args) {
    var launcher = LauncherFactory.create();
    var summaryListener = new SummaryGeneratingListener();
    var request = LauncherDiscoveryRequestBuilder.request()
        .selectors(DiscoverySelectors.selectClass(FetchTest.class)).build();
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
