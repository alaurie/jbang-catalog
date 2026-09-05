///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS org.apache.commons:commons-compress:1.27.1
//DEPS info.picocli:picocli:4.7.7
//SOURCES JellyfinBackup.java

package jellyfinbackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import picocli.CommandLine;

public class JellyfinBackupTest {

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
      var app = new JellyfinBackup();
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

  private void createMockBackupArchive(Path archivePath) throws Exception {
    try (var fos = Files.newOutputStream(archivePath);
        var bos = new BufferedOutputStream(fos);
        var gzos = new GZIPOutputStream(bos);
        var tarOut = new TarArchiveOutputStream(gzos)) {

      // 1. Add manifest
      String manifestJson = """
          {
            "version": "1.0",
            "jellyfin_version": "10.9.11",
            "timestamp": "2026-09-05T00:00:00Z",
            "hostname": "test-server",
            "total_entries": 2,
            "uncompressed_size_bytes": 100
          }
          """;
      byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
      var manifestEntry = new TarArchiveEntry("jellyfin-manifest.json");
      manifestEntry.setSize(manifestBytes.length);
      tarOut.putArchiveEntry(manifestEntry);
      tarOut.write(manifestBytes);
      tarOut.closeArchiveEntry();

      // 2. Add config file
      byte[] configBytes = "<Configuration></Configuration>".getBytes(StandardCharsets.UTF_8);
      var configEntry = new TarArchiveEntry("config/system.xml");
      configEntry.setSize(configBytes.length);
      tarOut.putArchiveEntry(configEntry);
      tarOut.write(configBytes);
      tarOut.closeArchiveEntry();

      tarOut.finish();
    }

    // Compute and write SHA-256 sidecar file
    byte[] archiveBytes = Files.readAllBytes(archivePath);
    var md = MessageDigest.getInstance("SHA-256");
    String hashHex = HexFormat.of().formatHex(md.digest(archiveBytes));
    Files.writeString(Path.of(archivePath.toString() + ".sha256"),
        hashHex + "  " + archivePath.getFileName() + "\n");
  }

  @Test
  void testHelp() {
    var result = runCommand("--help");
    assertEquals(0, result.exitCode());
    assertTrue(result.stdout()
        .contains("Complete backup, restore, and disaster recovery utility for Jellyfin"));
  }

  @Test
  void testSubcommandsHelp() {
    var backupHelp = runCommand("backup", "--help");
    assertEquals(0, backupHelp.exitCode());
    assertTrue(backupHelp.stdout()
        .contains("Create a complete, self-contained backup archive of Jellyfin"));

    var restoreHelp = runCommand("restore", "--help");
    assertEquals(0, restoreHelp.exitCode());
    assertTrue(
        restoreHelp.stdout().contains("Restore a Jellyfin backup archive into the target system"));

    var inspectHelp = runCommand("inspect", "--help");
    assertEquals(0, inspectHelp.exitCode());
    assertTrue(
        inspectHelp.stdout().contains("Inspect the contents, manifest, and database metrics"));
  }

  @Test
  void testInspectArchive(@TempDir Path tempDir) throws Exception {
    Path archiveFile = tempDir.resolve("jellyfin-backup-test.tar.gz");
    createMockBackupArchive(archiveFile);

    var result = runCommand("inspect", archiveFile.toString());
    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("Jellyfin Backup Archive Inspection"));
    assertTrue(result.stdout().contains("Integrity Check (SHA-256): VALID"));
    assertTrue(result.stdout().contains("10.9.11"));
  }

  @Test
  void testInspectNonExistentArchive() {
    var result = runCommand("inspect", "/nonexistent/jellyfin-backup-fake.tar.gz");
    assertEquals(1, result.exitCode());
    assertTrue(result.stderr().contains("does not exist"));
  }

  public static void main(String... args) {
    var launcher = LauncherFactory.create();
    var summaryListener = new SummaryGeneratingListener();
    var request = LauncherDiscoveryRequestBuilder.request()
        .selectors(DiscoverySelectors.selectClass(JellyfinBackupTest.class)).build();
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
