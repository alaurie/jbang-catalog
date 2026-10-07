///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS org.apache.commons:commons-compress:1.27.1
//DEPS info.picocli:picocli:4.7.7
//SOURCES JellyfinBackup.java

package jellyfinbackup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
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

	private record ExecutionResult(int exitCode, String stdout, String stderr) {
	}

	private ExecutionResult runCommand(String... args) {
		return runCommand(new CommandLine(new JellyfinBackup()), args);
	}

	private ExecutionResult runCommand(CommandLine cmd, String... args) {
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

	private record ArchiveItem(String name, String content, String linkTarget) {
	}

	private void createRestoreArchive(Path archivePath, ArchiveItem... items) throws Exception {
		try (var fos = Files.newOutputStream(archivePath);
				var bos = new BufferedOutputStream(fos);
				var gzos = new GZIPOutputStream(bos);
				var tarOut = new TarArchiveOutputStream(gzos)) {
			for (var item : items) {
				if (item.linkTarget() != null) {
					var entry = new TarArchiveEntry(item.name(), TarArchiveEntry.LF_SYMLINK);
					entry.setLinkName(item.linkTarget());
					tarOut.putArchiveEntry(entry);
					tarOut.closeArchiveEntry();
				} else {
					byte[] bytes = item.content().getBytes(StandardCharsets.UTF_8);
					var entry = new TarArchiveEntry(item.name());
					entry.setSize(bytes.length);
					tarOut.putArchiveEntry(entry);
					tarOut.write(bytes);
					tarOut.closeArchiveEntry();
				}
			}
			tarOut.finish();
		}
	}

	private ExecutionResult restoreWithoutVerification(Path archive, Path config, Path data) {
		return runCommand("restore", "--yes", "--no-stop", "--no-chown", "--no-verify", "-c",
				config.toString(), "-d", data.toString(), archive.toString());
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

	@Test
	void testRestoreOverExistingFiles(@TempDir Path tempDir) throws Exception {
		Path archiveFile = tempDir.resolve("backup.tar.gz");
		try (var fos = Files.newOutputStream(archiveFile);
				var bos = new BufferedOutputStream(fos);
				var gzos = new GZIPOutputStream(bos);
				var tarOut = new TarArchiveOutputStream(gzos)) {

			byte[] manifestBytes = "{\"version\":\"1.0\"}".getBytes(StandardCharsets.UTF_8);
			var manifestEntry = new TarArchiveEntry("jellyfin-manifest.json");
			manifestEntry.setSize(manifestBytes.length);
			tarOut.putArchiveEntry(manifestEntry);
			tarOut.write(manifestBytes);
			tarOut.closeArchiveEntry();

			byte[] configBytes = "<Config>Restored</Config>".getBytes(StandardCharsets.UTF_8);
			var configEntry = new TarArchiveEntry("etc/jellyfin/system.xml");
			configEntry.setSize(configBytes.length);
			tarOut.putArchiveEntry(configEntry);
			tarOut.write(configBytes);
			tarOut.closeArchiveEntry();

			byte[] dataBytes = "DATABASE_DATA".getBytes(StandardCharsets.UTF_8);
			var dataEntry = new TarArchiveEntry("var/lib/jellyfin/data/jellyfin.db");
			dataEntry.setSize(dataBytes.length);
			tarOut.putArchiveEntry(dataEntry);
			tarOut.write(dataBytes);
			tarOut.closeArchiveEntry();

			tarOut.finish();
		}

		byte[] archiveBytes = Files.readAllBytes(archiveFile);
		var md = MessageDigest.getInstance("SHA-256");
		String hashHex = HexFormat.of().formatHex(md.digest(archiveBytes));
		Files.writeString(Path.of(archiveFile + ".sha256"),
				hashHex + "  " + archiveFile.getFileName() + "\n");

		Path targetConfig = tempDir.resolve("target_etc");
		Path targetData = tempDir.resolve("target_var");
		Files.createDirectories(targetConfig);
		Files.createDirectories(targetData.resolve("data"));

		// Pre-create existing files that should be overwritten cleanly
		Files.writeString(targetConfig.resolve("system.xml"), "<Config>Old</Config>");
		// Pre-create a symlink where the database file will be written
		Path oldTarget = tempDir.resolve("dummy_old_target");
		Files.writeString(oldTarget, "DUMMY");
		Files.createSymbolicLink(targetData.resolve("data/jellyfin.db"), oldTarget);

		var result = runCommand("restore", "--yes", "--no-stop", "--no-chown", "-c",
				targetConfig.toString(), "-d", targetData.toString(), archiveFile.toString());

		assertEquals(0, result.exitCode(), "Restore should succeed with 0. stderr: " + result.stderr());
		assertTrue(result.stdout().contains("Restore Complete"), "stdout should indicate completion");
		assertEquals("<Config>Restored</Config>", Files.readString(targetConfig.resolve("system.xml")));
		assertEquals("DATABASE_DATA", Files.readString(targetData.resolve("data/jellyfin.db")));
		assertFalse(Files.isSymbolicLink(targetData.resolve("data/jellyfin.db")),
				"Restored file should be a regular file, not a symlink");
		assertEquals("DUMMY", Files.readString(oldTarget),
				"Original symlink target should not be overwritten");
	}

	@Test
	void testBackupAndRestoreCycle(@TempDir Path tempDir) throws Exception {
		Path configDir = tempDir.resolve("etc");
		Path dataDir = tempDir.resolve("var");
		Files.createDirectories(configDir);
		Files.createDirectories(dataDir.resolve("data"));
		Files.createDirectories(dataDir.resolve("transcodes"));
		Files.createDirectories(dataDir.resolve("plugins/TestPlugin_1.0.0.0"));

		Files.writeString(configDir.resolve("system.xml"), "<SystemConfig>Valid</SystemConfig>");
		Files.writeString(dataDir.resolve("data/jellyfin.db"), "JELLYFIN_DB_V2");
		Files.writeString(dataDir.resolve("transcodes/temp.ts"), "TEMP_TRANSCODE_DATA");
		Files.writeString(dataDir.resolve("plugins/TestPlugin_1.0.0.0/plugin.dll"), "PLUGIN_BINARY");

		Path backupArchive = tempDir.resolve("full-backup.tar.gz");
		var backupResult = runCommand("backup", "--no-stop", "-c", configDir.toString(), "-d",
				dataDir.toString(), "-o", backupArchive.toString());
		assertEquals(0, backupResult.exitCode(), "Backup should succeed: " + backupResult.stderr());
		assertTrue(Files.isRegularFile(backupArchive), "Backup archive must exist");
		assertTrue(Files.isRegularFile(Path.of(backupArchive + ".sha256")),
				"SHA256 sidecar must exist");

		var inspectResult = runCommand("inspect", backupArchive.toString());
		assertEquals(0, inspectResult.exitCode());
		assertTrue(inspectResult.stdout().contains("TestPlugin"));

		Path restoreConfig = tempDir.resolve("restored_etc");
		Path restoreData = tempDir.resolve("restored_var");
		var restoreResult = runCommand("restore", "--yes", "--no-stop", "--no-chown", "-c",
				restoreConfig.toString(), "-d", restoreData.toString(), backupArchive.toString());
		assertEquals(0, restoreResult.exitCode(), "Restore should succeed: " + restoreResult.stderr());

		assertEquals("<SystemConfig>Valid</SystemConfig>",
				Files.readString(restoreConfig.resolve("system.xml")));
		assertEquals("JELLYFIN_DB_V2", Files.readString(restoreData.resolve("data/jellyfin.db")));
		assertEquals("PLUGIN_BINARY",
				Files.readString(restoreData.resolve("plugins/TestPlugin_1.0.0.0/plugin.dll")));
		assertFalse(Files.exists(restoreData.resolve("transcodes/temp.ts")),
				"Cache files must be excluded");
	}

	@Test
	void testBackupFailurePreservesExistingArchiveAndChecksum(@TempDir Path tempDir)
			throws Exception {
		Path archive = tempDir.resolve("existing.tar.gz");
		createMockBackupArchive(archive);
		Path checksum = Path.of(archive + ".sha256");
		byte[] originalArchive = Files.readAllBytes(archive);
		String originalChecksum = Files.readString(checksum);
		Path data = Files.createDirectory(tempDir.resolve("data"));

		// A ZIP filesystem provides a real, stat-able regular file whose Path cannot
		// be converted to java.io.File by the archive writer on any OS or as root.
		URI uri = URI.create("jar:" + tempDir.resolve("source.zip").toUri());
		try (var sourceFileSystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
			Path config = Files.createDirectory(sourceFileSystem.getPath("/source"));
			Files.writeString(config.resolve("important.db"), "must not be silently skipped");
			var command = new CommandLine(new JellyfinBackup());
			command.getSubcommands()
				.get("backup")
				.registerConverter(Path.class,
						value -> value.equals("zip-source") ? config : Path.of(value));

			var result = runCommand(command, "backup", "--no-stop", "-c", "zip-source", "-d",
					data.toString(), "-o", archive.toString());
			assertEquals(1, result.exitCode(), result.stderr());
			assertTrue(result.stderr().contains("important.db"), result.stderr());
			assertFalse(result.stdout().contains("Backup Complete"));
		}

		assertArrayEquals(originalArchive, Files.readAllBytes(archive));
		assertEquals(originalChecksum, Files.readString(checksum));
		assertFalse(Files.exists(Path.of(archive + ".tmp")));
	}

	@Test
	void testBackupRejectsUnstatableSourceRoot(@TempDir Path tempDir) throws Exception {
		Path config = Files.createDirectory(tempDir.resolve("config"));
		Path missing = tempDir.resolve("missing");
		Path brokenLink = tempDir.resolve("broken-data");
		Files.createSymbolicLink(brokenLink, missing);
		Path archive = tempDir.resolve("incomplete.tar.gz");

		var result = runCommand("backup", "--no-stop", "-c", config.toString(), "-d",
				brokenLink.toString(), "-o", archive.toString());

		assertEquals(1, result.exitCode(), result.stderr());
		assertTrue(result.stderr().contains(brokenLink.toString()), result.stderr());
		assertFalse(Files.exists(archive));
		assertFalse(Files.exists(Path.of(archive + ".sha256")));
	}

	@Test
	void testRejectsArchiveSymlinksOutsideRoot(@TempDir Path tempDir) throws Exception {
		Path outside = Files.createDirectory(tempDir.resolve("outside"));
		Files.writeString(outside.resolve("sentinel"), "unchanged");
		Path config = tempDir.resolve("config");
		Path data = tempDir.resolve("data");

		for (String destination : new String[] { outside.toString(), "../outside" }) {
			Path archive = tempDir.resolve(
					"malicious-" + (destination.startsWith("..") ? "relative" : "absolute") + ".tar.gz");
			createRestoreArchive(archive, new ArchiveItem("etc/jellyfin/link", null, destination),
					new ArchiveItem("etc/jellyfin/link/sentinel", "overwritten", null));
			var result = restoreWithoutVerification(archive, config, data);
			assertEquals(1, result.exitCode(), result.stderr());
			assertTrue(result.stderr().contains("Symlink escapes restore root"));
			assertFalse(result.stdout().contains("Restore Complete"));
			assertFalse(Files.exists(config.resolve("link"), LinkOption.NOFOLLOW_LINKS));
			assertEquals("unchanged", Files.readString(outside.resolve("sentinel")));
		}
	}

	@Test
	void testRejectsPreexistingSymlinkAncestors(@TempDir Path tempDir) throws Exception {
		Path outside = Files.createDirectory(tempDir.resolve("outside"));
		Files.writeString(outside.resolve("sentinel"), "unchanged");
		Path config = Files.createDirectory(tempDir.resolve("config"));
		Path data = tempDir.resolve("data");
		Files.createSymbolicLink(config.resolve("linked"), outside);
		Path archive = tempDir.resolve("through-ancestor.tar.gz");
		createRestoreArchive(archive,
				new ArchiveItem("etc/jellyfin/linked/sentinel", "overwritten", null));

		var result = restoreWithoutVerification(archive, config, data);
		assertEquals(1, result.exitCode(), result.stderr());
		assertTrue(result.stderr().contains("Symbolic link in restore directory"));
		assertEquals("unchanged", Files.readString(outside.resolve("sentinel")));
	}

	@Test
	void testRejectsArchiveLinkThroughPreexistingExternalSymlink(@TempDir Path tempDir)
			throws Exception {
		Path outside = Files.createDirectory(tempDir.resolve("outside"));
		Files.writeString(outside.resolve("sentinel"), "unchanged");
		Path config = Files.createDirectory(tempDir.resolve("config"));
		Files.createSymbolicLink(config.resolve("existing"), outside);
		Path archive = tempDir.resolve("indirect-link.tar.gz");
		createRestoreArchive(archive, new ArchiveItem("etc/jellyfin/link", null, "existing/sentinel"));

		var result = restoreWithoutVerification(archive, config, tempDir.resolve("data"));
		assertEquals(1, result.exitCode(), result.stderr());
		assertTrue(result.stderr().contains("Symlink escapes restore root"));
		assertFalse(Files.exists(config.resolve("link"), LinkOption.NOFOLLOW_LINKS));
		assertEquals("unchanged", Files.readString(outside.resolve("sentinel")));
	}

	@Test
	void testRejectsSymlinkRootsAndRootAncestors(@TempDir Path tempDir) throws Exception {
		Path outside = Files.createDirectory(tempDir.resolve("outside"));
		Path archive = tempDir.resolve("root-target.tar.gz");
		createRestoreArchive(archive, new ArchiveItem("etc/jellyfin/sentinel", "overwritten", null));
		Path linkedRoot = tempDir.resolve("linked-root");
		Files.createSymbolicLink(linkedRoot, outside);

		for (Path config : new Path[] { linkedRoot, linkedRoot.resolve("nested") }) {
			var result = restoreWithoutVerification(archive, config, tempDir.resolve("data"));
			assertEquals(1, result.exitCode(), result.stderr());
			assertTrue(result.stderr().contains("Symbolic link in restore directory"));
			assertFalse(Files.exists(outside.resolve("sentinel")));
			assertFalse(Files.exists(outside.resolve("nested")));
		}
	}

	@Test
	void testRejectsDataRootSymlink(@TempDir Path tempDir) throws Exception {
		Path outside = Files.createDirectory(tempDir.resolve("outside"));
		Path linkedData = tempDir.resolve("linked-data");
		Files.createSymbolicLink(linkedData, outside);
		Path archive = tempDir.resolve("data-root.tar.gz");
		createRestoreArchive(archive,
				new ArchiveItem("var/lib/jellyfin/sentinel", "overwritten", null));

		var result = restoreWithoutVerification(archive, tempDir.resolve("config"), linkedData);
		assertEquals(1, result.exitCode(), result.stderr());
		assertTrue(result.stderr().contains("Symbolic link in restore directory"));
		assertFalse(Files.exists(outside.resolve("sentinel")));
	}

	@Test
	void testRestoresSafeInternalSymlink(@TempDir Path tempDir) throws Exception {
		Path archive = tempDir.resolve("internal-link.tar.gz");
		createRestoreArchive(archive, new ArchiveItem("etc/jellyfin/system.xml", "restored", null),
				new ArchiveItem("etc/jellyfin/link.xml", null, "system.xml"));
		Path config = tempDir.resolve("config");
		var result = restoreWithoutVerification(archive, config, tempDir.resolve("data"));

		assertEquals(0, result.exitCode(), result.stderr());
		assertTrue(Files.isSymbolicLink(config.resolve("link.xml")));
		assertEquals("system.xml", Files.readSymbolicLink(config.resolve("link.xml")).toString());
		assertEquals("restored", Files.readString(config.resolve("link.xml")));
	}

	@Test
	void testFailedFileAndSymlinkWritesFailRestore(@TempDir Path tempDir) throws Exception {
		Path config = Files.createDirectory(tempDir.resolve("config"));
		Path data = tempDir.resolve("data");
		Path occupied = Files.createDirectory(config.resolve("occupied"));
		Files.writeString(occupied.resolve("sentinel"), "unchanged");
		Path fileArchive = tempDir.resolve("failed-file.tar.gz");
		createRestoreArchive(fileArchive, new ArchiveItem("etc/jellyfin/occupied", "file", null));
		Path linkArchive = tempDir.resolve("failed-link.tar.gz");
		createRestoreArchive(linkArchive,
				new ArchiveItem("etc/jellyfin/occupied", null, "safe-target"));

		for (Path archive : new Path[] { fileArchive, linkArchive }) {
			var result = restoreWithoutVerification(archive, config, data);
			assertEquals(1, result.exitCode(), result.stderr());
			assertTrue(result.stderr().contains("Failed to restore backup archive"));
			assertFalse(result.stdout().contains("Restore Complete"));
			assertEquals("unchanged", Files.readString(occupied.resolve("sentinel")));
		}
	}

	@Test
	void testRestoreChecksumMismatchAndOverride(@TempDir Path tempDir) throws Exception {
		Path archiveFile = tempDir.resolve("corrupt-check.tar.gz");
		createMockBackupArchive(archiveFile);

		Path shaFile = Path.of(archiveFile + ".sha256");
		Files.writeString(shaFile,
				"0000000000000000000000000000000000000000000000000000000000000000  archive.tar.gz\n");

		Path targetConfig = tempDir.resolve("target_etc");
		Path targetData = tempDir.resolve("target_var");

		// Without override, should fail verification
		var failResult = runCommand("restore", "--yes", "--no-stop", "--no-chown", "-c",
				targetConfig.toString(), "-d", targetData.toString(), archiveFile.toString());
		assertEquals(1, failResult.exitCode(), "Should fail on hash mismatch");
		assertTrue(failResult.stderr().contains("verification failed"));

		// With --no-verify, should proceed
		var passResult = runCommand("restore", "--yes", "--no-stop", "--no-chown", "--no-verify", "-c",
				targetConfig.toString(), "-d", targetData.toString(), archiveFile.toString());
		assertEquals(0, passResult.exitCode(),
				"Should succeed with --no-verify: " + passResult.stderr());
	}

	@Test
	void testFormatBytesHelper() {
		assertEquals("500 B", JellyfinBackup.formatBytes(500));
		assertEquals("1.00 KB", JellyfinBackup.formatBytes(1024));
		assertEquals("1.50 MB", JellyfinBackup.formatBytes((long) (1.5 * 1024 * 1024)));
		assertEquals("2.00 GB", JellyfinBackup.formatBytes(2L * 1024 * 1024 * 1024));
	}

	@Test
	void testNeedsElevation() {
		assertFalse(JellyfinBackup.needsElevation());
		assertFalse(JellyfinBackup.needsElevation("--help"));
		assertFalse(JellyfinBackup.needsElevation("-h"));
		assertFalse(JellyfinBackup.needsElevation("-v"));
		assertFalse(JellyfinBackup.needsElevation("-V"));
		assertFalse(JellyfinBackup.needsElevation("--version"));
		assertFalse(JellyfinBackup.needsElevation("inspect", "archive.tar.gz"));
		assertFalse(JellyfinBackup.needsElevation("backup", "--help"));
		assertFalse(JellyfinBackup.needsElevation("backup", "-h"));
		assertFalse(JellyfinBackup.needsElevation("restore", "--help"));
		assertFalse(JellyfinBackup.needsElevation("restore", "-h"));

		assertTrue(JellyfinBackup.needsElevation("backup"));
		assertTrue(JellyfinBackup.needsElevation("backup", "-o", "/tmp"));
		assertTrue(JellyfinBackup.needsElevation("restore", "archive.tar.gz"));
	}

	@Test
	void testBuildSudoCommand() {
		var javaCmd = JellyfinBackup.buildSudoCommand("/usr/bin/java",
				new String[] { "-cp", "app.jar", "jellyfinbackup.JellyfinBackup", "backup" }, "backup");
		assertNotNull(javaCmd);
		assertEquals("sudo", javaCmd.get(0));
		assertEquals("/usr/bin/java", javaCmd.get(1));
		assertEquals("-cp", javaCmd.get(2));
		assertEquals("app.jar", javaCmd.get(3));
		assertEquals("jellyfinbackup.JellyfinBackup", javaCmd.get(4));
		assertEquals("backup", javaCmd.get(5));

		var nativeCmd = JellyfinBackup.buildSudoCommand("/bin/sh", null, "backup", "-o", "/tmp");
		assertNotNull(nativeCmd);
		assertEquals("sudo", nativeCmd.get(0));
		assertEquals("/bin/sh", nativeCmd.get(1));
		assertEquals("backup", nativeCmd.get(2));
		assertEquals("-o", nativeCmd.get(3));
		assertEquals("/tmp", nativeCmd.get(4));
	}

	public static void main(String... args) {
		var launcher = LauncherFactory.create();
		var summaryListener = new SummaryGeneratingListener();
		var request = LauncherDiscoveryRequestBuilder.request()
			.selectors(DiscoverySelectors.selectClass(JellyfinBackupTest.class))
			.build();
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
