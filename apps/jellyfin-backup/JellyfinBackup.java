///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//DEPS org.apache.commons:commons-compress:1.27.1
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms16m -Xmx64m -XX:CICompilerCount=2 -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData
//NATIVE_OPTIONS -O2 -march=native --no-fallback

package jellyfinbackup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// CLI utility to backup, restore, and inspect complete Jellyfin installations.
///
/// Supports Linux systemd, Podman/Docker containers (including rootless
/// systemd/Quadlet user services), and Windows service/tray/portable
/// installations. Archives configuration, databases, plugins, and metadata using
/// a platform-independent layout.
@Command(name = "jellyfin-backup", mixinStandardHelpOptions = true, version = "jellyfin-backup 2.2.1", description = "Complete backup, restore, and disaster recovery utility for Jellyfin media server.", subcommands = {
		JellyfinBackup.BackupCmd.class, JellyfinBackup.RestoreCmd.class,
		JellyfinBackup.InspectCmd.class })
@SuppressWarnings("unused")
class JellyfinBackup implements Callable<Integer> {

	static final String MANIFEST_ENTRY_NAME = "jellyfin-manifest.json";

	enum ServiceScope {
		NONE,
		SYSTEMD_SYSTEM,
		SYSTEMD_USER,
		CONTAINER
	}

	record MountInfo(String type, String source, String destination) {
	}

	record ContainerInfo(
			String runtime,
			String name,
			boolean running,
			String image,
			String imageVersion,
			String systemdUnit,
			ServiceScope serviceScope,
			Path configDir,
			Path dataDir,
			Path quadletFile,
			Path composeFile) {
	}

	record TargetInstallation(
			Path configDir,
			Path dataDir,
			ContainerInfo container,
			ServiceScope serviceScope,
			String serviceName) {
	}

	void main(String... args) {
		if (shouldElevate(args)) {
			int exitCode = reexecWithSudo(args);
			System.exit(exitCode);
		}

		int exitCode = new CommandLine(this).execute(args);
		System.exit(exitCode);
	}

	@Override
	public Integer call() {
		CommandLine.usage(this, System.out);
		return 0;
	}

	// =========================================================================
	// SUBCOMMAND: BACKUP
	// =========================================================================
	@Command(name = "backup", mixinStandardHelpOptions = true, description = "Create a complete, self-contained backup archive of Jellyfin.")
	static class BackupCmd implements Callable<Integer> {

		@Option(names = { "-o",
				"--output" }, description = "Target destination directory or .tar.gz archive path. Default: current directory.")
		private Path outputPath;

		@Option(names = { "-c",
				"--config-dir" }, description = "Configuration directory. Default: /etc/jellyfin on Linux "
						+ "(or container mount), <data-dir>/config on Windows; honors JELLYFIN_CONFIG_DIR.")
		private Path configDir;

		@Option(names = { "-d", "--data-dir" }, description = "Data directory. Default: /var/lib/jellyfin on Linux "
				+ "(or container mount), auto-detected on Windows; honors JELLYFIN_DATA_DIR.")
		private Path dataDir;

		@Option(names = { "--service-name" }, description = "Service name. "
				+ "Default: jellyfin on Linux, JellyfinServer on Windows.")
		private String serviceName = defaultServiceName();

		@Option(names = {
				"--container" }, description = "Jellyfin container name or ID (auto-detected if omitted).")
		private String containerName;

		@Option(names = {
				"--no-stop" }, description = "Do not stop or check Jellyfin before backup (unsafe if the server is running).")
		private boolean noStop;

		@Option(names = { "--include-cache" }, description = "Include transcode cache and temporary directories.")
		private boolean includeCache;

		@Option(names = {
				"--include-internal-backups" }, description = "Include historical built-in backup zip files located in data/backups/.")
		private boolean includeInternalBackups;

		@Override
		public Integer call() throws Exception {
			TargetInstallation target;
			try {
				target = resolveTargetInstallation(configDir, dataDir, containerName, serviceName);
			} catch (IllegalArgumentException e) {
				System.err.println("Error: " + e.getMessage());
				return 1;
			}
			configDir = target.configDir();
			dataDir = target.dataDir();

			if (isLinux() && !isRunningAsRoot()
					&& (requiresRootPath(configDir, null) || requiresRootPath(dataDir, null))) {
				System.err.println("Error: Run as root to back up system Jellyfin directories.");
				return 1;
			}

			boolean hasConfig;
			boolean hasData;
			try {
				hasConfig = isAvailableBackupDirectory(configDir);
				hasData = isAvailableBackupDirectory(dataDir);
			} catch (IOException e) {
				System.err.println("Error: Cannot read backup source: " + e.getMessage());
				return 1;
			}
			if (!hasConfig && !hasData) {
				System.err.printf("Error: Neither config directory (%s) nor data directory (%s) exists.%n",
						configDir, dataDir);
				return 1;
			}

			Path finalArchiveFile = resolveArchiveDestination(outputPath);
			if (finalArchiveFile.getParent() != null) {
				Files.createDirectories(finalArchiveFile.getParent());
			}

			System.out.println("===============================================================");
			System.out.println("  Jellyfin Complete Disaster Recovery Backup");
			System.out.println("===============================================================");
			if (target.container() != null) {
				System.out.printf("Mode:              %s%n", describeTarget(target));
			}
			System.out.printf("Configuration Dir: %s (%s)%n", configDir,
					hasConfig ? "Found" : "Not Found");
			System.out.printf("Data Dir:          %s (%s)%n", dataDir,
					hasData ? "Found" : "Not Found");
			System.out.printf("Archive Target:    %s%n", finalArchiveFile.toAbsolutePath());
			System.out.println("---------------------------------------------------------------");

			String serverVersion = detectServerVersion(target.container());
			boolean serviceWasRunning = false;

			long startTime = System.currentTimeMillis();
			int exitCode = 0;
			try {
				if (!noStop) {
					serviceWasRunning = isTargetActive(target);
					if (serviceWasRunning) {
						System.out.printf("Stopping '%s' for clean database snapshot...%n",
								target.serviceName());
						if (!stopTarget(target)) {
							throw new IOException("Could not stop '" + target.serviceName() + "'.");
						}
					}
					if (target.container() == null) {
						ensureWindowsServerStopped();
					}
				}
				createBackupArchive(finalArchiveFile, hasConfig, hasData, serverVersion, target.container());
			} catch (Exception e) {
				System.err.println("Error: Failed to create backup archive: " + e.getMessage());
				exitCode = 1;
			} finally {
				if (serviceWasRunning) {
					try {
						System.out.printf("Restarting '%s'...%n", target.serviceName());
						if (!startTarget(target)) {
							System.err.printf("FAILED (Please check %s)%n", targetStatusHint(target));
							if (exitCode == 0) {
								exitCode = 1;
							}
						}
					} catch (Exception e) {
						System.err.println("Error: Could not restart Jellyfin: " + e.getMessage());
						exitCode = 1;
					}
				}
			}

			if (exitCode != 0) {
				return exitCode;
			}
			long durationMs = System.currentTimeMillis() - startTime;
			long archiveSize = Files.size(finalArchiveFile);

			System.out.println("---------------------------------------------------------------");
			System.out.printf("Backup Complete in %.1fs!%n", durationMs / 1000.0);
			System.out.printf("Archive Size: %s%n", formatBytes(archiveSize));
			System.out.printf("Location:     %s%n", finalArchiveFile.toAbsolutePath());
			System.out.println("===============================================================");

			return 0;
		}

		private Path resolveArchiveDestination(Path out) {
			String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
			String defaultName = "jellyfin-backup-" + timestamp + ".tar.gz";

			if (out == null) {
				return Path.of(defaultName);
			}
			if (Files.isDirectory(out) || out.toString().endsWith("/") || out.toString().endsWith("\\")) {
				return out.resolve(defaultName);
			}
			return out;
		}

		private static boolean isAvailableBackupDirectory(Path source) throws IOException {
			try {
				if (!Files.readAttributes(source, BasicFileAttributes.class).isDirectory()) {
					throw new IOException("Not a backup directory: " + source);
				}
				return true;
			} catch (NoSuchFileException e) {
				if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Cannot read backup directory: " + source, e);
				}
				return false;
			}
		}

		private void createBackupArchive(Path archivePath, boolean hasConfig, boolean hasData,
				String serverVersion, ContainerInfo targetContainer) throws Exception {
			Path tempArchive = Path.of(archivePath.toString() + ".tmp");
			Files.deleteIfExists(tempArchive);

			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

			try {
				try (FileOutputStream fos = new FileOutputStream(tempArchive.toFile());
						BufferedOutputStream bos = new BufferedOutputStream(fos, 128 * 1024);
						DigestOutputStream dos = new DigestOutputStream(bos, sha256);
						GZIPOutputStream gzos = new GZIPOutputStream(dos);
						TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzos)) {

					tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
					tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

					// 1. Archive /etc/jellyfin
					if (hasConfig) {
						System.out.printf("Archiving configurations (%s)... ", configDir);
						archiveDirectory(configDir, "etc/jellyfin", tarOut, false, false, null);
						System.out.println("OK");
					}

					// 2. Archive /var/lib/jellyfin
					if (hasData) {
						Path excludedSubdir = null;
						Path normConfig = configDir.toAbsolutePath().normalize();
						Path normData = dataDir.toAbsolutePath().normalize();
						if (hasConfig && !normConfig.equals(normData) && normConfig.startsWith(normData)) {
							excludedSubdir = normConfig;
						}
						System.out.printf("Archiving databases, plugins & metadata (%s)... ", dataDir);
						archiveDirectory(dataDir, "var/lib/jellyfin", tarOut, includeCache,
								includeInternalBackups, excludedSubdir);
						System.out.println("OK");
					}

					// 3. Archive Quadlet / Compose definition if present
					boolean hasQuadlet = targetContainer != null && targetContainer.quadletFile() != null
							&& Files.isRegularFile(targetContainer.quadletFile());
					if (hasQuadlet) {
						System.out.printf("Archiving Quadlet unit (%s)... ",
								targetContainer.quadletFile().getFileName());
						archiveSingleFile(targetContainer.quadletFile(),
								"quadlet/" + targetContainer.quadletFile().getFileName(), tarOut);
						System.out.println("OK");
					}

					boolean hasCompose = targetContainer != null && targetContainer.composeFile() != null
							&& Files.isRegularFile(targetContainer.composeFile());
					if (hasCompose) {
						System.out.printf("Archiving Compose file (%s)... ",
								targetContainer.composeFile().getFileName());
						archiveSingleFile(targetContainer.composeFile(),
								"compose/" + targetContainer.composeFile().getFileName(), tarOut);
						System.out.println("OK");
					}

					// 4. Write manifest
					String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
					String escapedVersion = serverVersion == null ? ""
							: serverVersion.replace("\\", "\\\\").replace("\"", "\\\"");
					String manifestJson = """
							{
							  "generator": "jellyfin-backup 2.2.1",
							  "timestamp": "%s",
							  "serverVersion": "%s",
							  "hasConfig": %b,
							  "hasData": %b,
							  "hasQuadlet": %b,
							  "hasCompose": %b
							}
							""".formatted(timestamp, escapedVersion, hasConfig, hasData, hasQuadlet,
							hasCompose);

					byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
					TarArchiveEntry manifestEntry = new TarArchiveEntry(MANIFEST_ENTRY_NAME);
					manifestEntry.setSize(manifestBytes.length);
					manifestEntry.setModTime(System.currentTimeMillis());
					tarOut.putArchiveEntry(manifestEntry);
					tarOut.write(manifestBytes);
					tarOut.closeArchiveEntry();
				}

				Files.move(tempArchive, archivePath, StandardCopyOption.REPLACE_EXISTING);

				// Write sidecar SHA-256 checksum file
				String hexHash = HexFormat.of().formatHex(sha256.digest());
				Path shaFile = Path.of(archivePath + ".sha256");
				String shaContent = hexHash + "  " + archivePath.getFileName() + "\n";
				Files.writeString(shaFile, shaContent, StandardCharsets.UTF_8);
				System.out.println("SHA-256: " + hexHash);
			} finally {
				Files.deleteIfExists(tempArchive);
			}
		}

		private static void archiveSingleFile(Path file, String entryName, TarArchiveOutputStream tarOut)
				throws IOException {
			byte[] content = Files.readAllBytes(file);
			TarArchiveEntry entry = new TarArchiveEntry(entryName);
			entry.setSize(content.length);
			entry.setModTime(Files.getLastModifiedTime(file).toMillis());
			tarOut.putArchiveEntry(entry);
			tarOut.write(content);
			tarOut.closeArchiveEntry();
		}

		private void archiveDirectory(Path baseDir, String prefix, TarArchiveOutputStream tarOut,
				boolean inclCache, boolean inclBackups, Path excludedSubdir) throws Exception {
			Path normExcluded = excludedSubdir != null ? excludedSubdir.toAbsolutePath().normalize() : null;
			Files.walkFileTree(baseDir, new SimpleFileVisitor<>() {
				@Override
				public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
						BasicFileAttributes attrs) throws IOException {
					if (normExcluded != null && !dir.equals(baseDir)
							&& dir.toAbsolutePath().normalize().equals(normExcluded)) {
						return java.nio.file.FileVisitResult.SKIP_SUBTREE;
					}
					String relPath = baseDir.relativize(dir).toString().replace('\\', '/');
					if (!relPath.isEmpty()) {
						if (!inclCache && isCachePath(relPath)) {
							return java.nio.file.FileVisitResult.SKIP_SUBTREE;
						}
						if (!inclBackups && isBackupPath(relPath)) {
							return java.nio.file.FileVisitResult.SKIP_SUBTREE;
						}
						String entryName = prefix + "/" + relPath + "/";
						try {
							TarArchiveEntry entry = new TarArchiveEntry(dir.toFile(), entryName);
							entry.setModTime(attrs.lastModifiedTime().toMillis());
							tarOut.putArchiveEntry(entry);
							tarOut.closeArchiveEntry();
						} catch (Exception e) {
							throw new IOException("Failed to archive directory: " + dir, e);
						}
					}
					return java.nio.file.FileVisitResult.CONTINUE;
				}

				@Override
				public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
						throws IOException {
					String relPath = baseDir.relativize(file).toString().replace('\\', '/');
					if (!inclCache && isCachePath(relPath)) {
						return java.nio.file.FileVisitResult.CONTINUE;
					}
					if (!inclBackups && isBackupPath(relPath)
							&& (file.toString().endsWith(".zip") || file.toString().endsWith(".tar.gz"))) {
						return java.nio.file.FileVisitResult.CONTINUE;
					}

					String entryName = prefix + "/" + relPath.replace('\\', '/');
					try {
						if (attrs.isSymbolicLink()) {
							Path target = Files.readSymbolicLink(file);
							TarArchiveEntry entry = new TarArchiveEntry(entryName, TarArchiveEntry.LF_SYMLINK);
							entry.setLinkName(target.toString());
							entry.setSize(0);
							entry.setModTime(attrs.lastModifiedTime().toMillis());
							tarOut.putArchiveEntry(entry);
							tarOut.closeArchiveEntry();
							return java.nio.file.FileVisitResult.CONTINUE;
						}

						if (attrs.isOther()) {
							// Skip character devices, block devices, FIFOs, sockets
							return java.nio.file.FileVisitResult.CONTINUE;
						}

						long actualSize = Files.size(file);
						TarArchiveEntry entry = new TarArchiveEntry(file.toFile(), entryName);
						entry.setSize(actualSize);
						entry.setModTime(attrs.lastModifiedTime().toMillis());
						tarOut.putArchiveEntry(entry);
						long written = 0;
						try (InputStream is = Files.newInputStream(file)) {
							byte[] buf = new byte[64 * 1024];
							int read;
							while ((read = is.read(buf)) != -1 && written < actualSize) {
								int toWrite = (int) Math.min(read, actualSize - written);
								tarOut.write(buf, 0, toWrite);
								written += toWrite;
							}
						}
						// Pad if file shrank during live reading
						if (written < actualSize) {
							byte[] zero = new byte[1024];
							while (written < actualSize) {
								int pad = (int) Math.min(zero.length, actualSize - written);
								tarOut.write(zero, 0, pad);
								written += pad;
							}
						}
						tarOut.closeArchiveEntry();
					} catch (Exception e) {
						throw new IOException("Failed to archive file: " + file, e);
					}
					return java.nio.file.FileVisitResult.CONTINUE;
				}

				@Override
				public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc)
						throws IOException {
					String relPath = baseDir.relativize(file).toString().replace('\\', '/');
					if ((!inclCache && isCachePath(relPath))
							|| (!inclBackups && (isBackupPath(relPath)
									|| file.toString().endsWith(".zip")
									|| file.toString().endsWith(".tar.gz")))) {
						return java.nio.file.FileVisitResult.SKIP_SUBTREE;
					}
					throw new IOException("Failed to read backup path: " + file, exc);
				}
			});
		}

		private static boolean isCachePath(String relPath) {
			return relPath.startsWith("transcodes") || relPath.contains("/transcodes")
					|| relPath.startsWith("cache") || relPath.contains("/cache") || relPath.startsWith("log")
					|| relPath.contains("/log") || relPath.startsWith(".cache")
					|| relPath.startsWith(".local/share/containers")
					|| relPath.contains("/containers/storage");
		}

		private static boolean isBackupPath(String relPath) {
			return relPath.startsWith("data/backups") || relPath.contains("backups/");
		}
	}

	// =========================================================================
	// SUBCOMMAND: RESTORE
	// =========================================================================
	@Command(name = "restore", mixinStandardHelpOptions = true, description = "Restore a Jellyfin backup archive into the target system.")
	static class RestoreCmd implements Callable<Integer> {

		@Parameters(index = "0", description = "Path to the .tar.gz backup archive.")
		private Path archiveFile;

		@Option(names = { "-c", "--config-dir" }, description = "Target configuration directory. "
				+ "Default: /etc/jellyfin on Linux (or container mount), <data-dir>/config on Windows; honors JELLYFIN_CONFIG_DIR.")
		private Path configDir;

		@Option(names = { "-d", "--data-dir" }, description = "Target data directory. "
				+ "Default: /var/lib/jellyfin on Linux (or container mount); auto-detected on Windows; honors JELLYFIN_DATA_DIR.")
		private Path dataDir;

		@Option(names = { "--service-name" }, description = "Service name. "
				+ "Default: jellyfin on Linux, JellyfinServer on Windows.")
		private String serviceName = defaultServiceName();

		@Option(names = {
				"--container" }, description = "Jellyfin container name or ID (auto-detected if omitted).")
		private String containerName;

		@Option(names = { "--no-stop" }, description = "Do not stop or check Jellyfin before restore "
				+ "(unsafe if the server is running).")
		private boolean noStop;

		@Option(names = {
				"--no-chown" }, description = "Do not automatically set jellyfin:jellyfin file ownership after restore.")
		private boolean noChown;

		@Option(names = {
				"--no-verify" }, description = "Skip SHA-256 checksum integrity verification before restoring.")
		private boolean noVerify;

		@Option(names = {
				"--no-quadlet" }, description = "Skip restoring Quadlet .container files from the archive.")
		private boolean noQuadlet;

		@Option(names = {
				"--no-compose" }, description = "Skip restoring Docker/Podman Compose files from the archive.")
		private boolean noCompose;

		@Option(names = { "-y", "--yes" }, description = "Skip confirmation prompt and proceed with restore.")
		private boolean assumeYes;

		@Override
		public Integer call() throws Exception {
			if (!Files.isRegularFile(archiveFile)) {
				System.err.printf("Error: Backup archive '%s' does not exist or is not a file.%n",
						archiveFile);
				return 1;
			}

			TargetInstallation target;
			try {
				target = resolveTargetInstallation(configDir, dataDir, containerName, serviceName);
			} catch (IllegalArgumentException e) {
				System.err.println("Error: " + e.getMessage());
				return 1;
			}
			configDir = target.configDir();
			dataDir = target.dataDir();

			if (isLinux() && !isRunningAsRoot()
					&& (requiresRootPath(configDir, null) || requiresRootPath(dataDir, null))) {
				System.err.println("Error: Run as root to restore system Jellyfin directories.");
				return 1;
			}

			System.out.println("===============================================================");
			System.out.println("  Jellyfin Complete Disaster Recovery Restore");
			System.out.println("===============================================================");
			System.out.printf("Backup Archive:    %s (%s)%n", archiveFile.toAbsolutePath(),
					formatBytes(Files.size(archiveFile)));
			if (target.container() != null) {
				System.out.printf("Mode:              %s%n", describeTarget(target));
			}
			System.out.printf("Target Config Dir: %s%n", configDir.toAbsolutePath());
			System.out.printf("Target Data Dir:   %s%n", dataDir.toAbsolutePath());
			System.out.println("---------------------------------------------------------------");

			if (!noVerify) {
				System.out.print("Verifying archive integrity (SHA-256)... ");
				String checksumError = verifyArchiveChecksum(archiveFile);
				if (checksumError == null) {
					System.out.println("OK");
				} else {
					System.out.println("FAILED (" + checksumError + ")");
					System.err.println(
							"Error: Archive verification failed. Use --no-verify to override if you are sure.");
					return 1;
				}
			}

			if (!assumeYes && System.console() != null) {
				System.out.print(
						"WARNING: This will overwrite existing databases and configurations. Continue? [y/N]: ");
				String response = new BufferedReader(new InputStreamReader(System.in)).readLine();
				if (response == null || (!response.trim().equalsIgnoreCase("y")
						&& !response.trim().equalsIgnoreCase("yes"))) {
					System.out.println("Restore cancelled.");
					return 0;
				}
			}

			boolean serviceWasRunning = false;

			long startTime = System.currentTimeMillis();
			int exitCode = 0;
			try {
				if (!noStop) {
					serviceWasRunning = isTargetActive(target);
					if (serviceWasRunning) {
						System.out.printf("Stopping '%s' before restore...%n", target.serviceName());
						if (!stopTarget(target)) {
							throw new IOException("Could not stop '" + target.serviceName() + "'.");
						}
					}
					if (target.container() == null) {
						ensureWindowsServerStopped();
					}
				}
				unpackArchive(target);
			} catch (Exception e) {
				System.err.println("Error: Failed to restore backup archive: " + e.getMessage());
				exitCode = 1;
			} finally {
				if (!noChown && isLinux() && isRunningAsRoot() && target.container() == null) {
					fixJellyfinPermissions();
				}

				if (serviceWasRunning) {
					try {
						System.out.printf("Restarting '%s'...%n", target.serviceName());
						if (!startTarget(target)) {
							System.err.printf("FAILED (Please check %s)%n", targetStatusHint(target));
							if (exitCode == 0) {
								exitCode = 1;
							}
						}
					} catch (Exception e) {
						System.err.println("Error: Could not restart Jellyfin: " + e.getMessage());
						exitCode = 1;
					}
				}
			}

			if (exitCode != 0) {
				return exitCode;
			}

			long durationMs = System.currentTimeMillis() - startTime;
			System.out.println("---------------------------------------------------------------");
			System.out.printf("Restore Complete in %.1fs!%n", durationMs / 1000.0);
			System.out.println(
					"Your Jellyfin server state, databases, watch history, and plugins are restored.");
			System.out.println("===============================================================");

			return 0;
		}

		private void unpackArchive(TargetInstallation target) throws Exception {
			Path configRoot = configDir.toAbsolutePath().normalize();
			Path dataRoot = dataDir.toAbsolutePath().normalize();
			ensureSafeDirectory(configRoot);
			ensureSafeDirectory(dataRoot);
			boolean restoredQuadlet = false;
			boolean quadletUserScope = !isRunningAsRoot();

			try (InputStream fis = Files.newInputStream(archiveFile);
					BufferedInputStream bis = new BufferedInputStream(fis, 128 * 1024);
					GZIPInputStream gzis = new GZIPInputStream(bis);
					TarArchiveInputStream tarIn = new TarArchiveInputStream(gzis)) {

				TarArchiveEntry entry;
				int restoredFiles = 0;
				while ((entry = tarIn.getNextEntry()) != null) {
					String name = entry.getName();
					if (name.equals(MANIFEST_ENTRY_NAME)) {
						continue;
					}

					if (name.startsWith("quadlet/") && !entry.isDirectory()) {
						if (!noQuadlet && isLinux()) {
							String fileName = name.substring("quadlet/".length());
							if (isSafeSingleFilename(fileName) && fileName.endsWith(".container")) {
								Path quadletDir = (target.container() != null
										&& target.container().quadletFile() != null)
												? target.container().quadletFile().getParent()
												: defaultQuadletDir(quadletUserScope);
								ensureSafeDirectory(quadletDir);
								Path quadletTarget = quadletDir.resolve(fileName).normalize();
								if (!quadletTarget.startsWith(quadletDir)) {
									throw new IOException("Quadlet entry escapes target dir: " + name);
								}
								prepareTargetFile(quadletTarget);
								writeStreamToFile(tarIn, quadletTarget);
								restoredQuadlet = true;
								restoredFiles++;
								System.out.printf("Restored Quadlet unit: %s%n", quadletTarget);
							}
						}
						continue;
					}

					if (name.startsWith("compose/") && !entry.isDirectory()) {
						if (!noCompose) {
							String fileName = name.substring("compose/".length());
							if (isSafeSingleFilename(fileName)) {
								Path composeTarget = (target.container() != null
										&& target.container().composeFile() != null)
												? target.container().composeFile().toAbsolutePath().normalize()
												: Path.of(fileName).toAbsolutePath().normalize();
								ensureSafeDirectory(composeTarget.getParent());
								prepareTargetFile(composeTarget);
								writeStreamToFile(tarIn, composeTarget);
								restoredFiles++;
								System.out.printf("Restored Compose file: %s%n", composeTarget);
							}
						}
						continue;
					}

					Path baseDir = null;
					String sub = null;
					if (name.startsWith("etc/jellyfin/")) {
						baseDir = configRoot;
						sub = name.substring("etc/jellyfin/".length());
					} else if (name.startsWith("var/lib/jellyfin/")) {
						baseDir = dataRoot;
						sub = name.substring("var/lib/jellyfin/".length());
					}

					if (baseDir != null) {
						Path targetPath = baseDir.resolve(sub).normalize();
						if (!targetPath.startsWith(baseDir)) {
							throw new IOException("Archive entry escapes restore root: " + name);
						}

						ensureSafeDirectory(targetPath.getParent());
						if (entry.isDirectory()) {
							if (!Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS)) {
								Files.deleteIfExists(targetPath);
							}
							ensureSafeDirectory(targetPath);
						} else if (entry.isSymbolicLink()) {
							Path linkTarget = Path.of(entry.getLinkName());
							ensureSafeLinkTarget(baseDir, targetPath, linkTarget);
							prepareTargetFile(targetPath);
							Files.createSymbolicLink(targetPath, linkTarget);
							restoredFiles++;
						} else if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
							if (!Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS)) {
								Files.deleteIfExists(targetPath);
							}
						} else {
							prepareTargetFile(targetPath);
							writeStreamToFile(tarIn, targetPath);
							restoredFiles++;
						}
					}
				}
				if (restoredQuadlet) {
					reloadSystemdDaemon(quadletUserScope);
				}
				System.out.printf("Restored %d files into %s and %s.%n", restoredFiles, configDir, dataDir);
			}
		}

		private static void writeStreamToFile(InputStream in, Path targetPath) throws IOException {
			try (var out = Files.newOutputStream(targetPath, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE)) {
				byte[] buf = new byte[64 * 1024];
				int read;
				while ((read = in.read(buf)) != -1) {
					out.write(buf, 0, read);
				}
			}
		}

		private static boolean isSafeSingleFilename(String fileName) {
			return !fileName.isEmpty() && !fileName.equals(".") && !fileName.equals("..")
					&& !fileName.contains("/") && !fileName.contains("\\");
		}

		/// Creates and checks each directory component, rejecting symlinks and
		/// Windows junctions.
		private static void ensureSafeDirectory(Path directory) throws IOException {
			Path current = directory.getRoot();
			for (Path component : directory) {
				current = current.resolve(component);
				if (Files.isSymbolicLink(current)) {
					throw new IOException("Symbolic link in restore directory: " + current);
				}
				if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
					Files.createDirectory(current);
				} else if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Not a restore directory: " + current);
				} else if (isWindows() && !current.toRealPath().equals(current)) {
					throw new IOException("Directory link or alias in restore directory: " + current);
				}
			}
		}

		/// Rejects links outside the root, including links through existing
		/// symlink chains.
		private static void ensureSafeLinkTarget(Path root, Path link, Path linkTarget)
				throws IOException {
			Path rawTarget = linkTarget.isAbsolute() ? linkTarget : link.getParent().resolve(linkTarget);
			if (!rawTarget.startsWith(root)) {
				throw new IOException("Symlink escapes restore root: " + link + " -> " + linkTarget);
			}
			Path current = root;
			for (Path component : root.relativize(rawTarget)) {
				if (component.toString().equals(".")) {
					continue;
				}
				current = component.toString().equals("..") ? current.getParent() : current.resolve(component);
				if (current == null || !current.startsWith(root)) {
					throw new IOException("Symlink escapes restore root: " + link + " -> " + linkTarget);
				}
				if (Files.isSymbolicLink(current) || (isWindows() && Files.isDirectory(current))) {
					current = current.toRealPath();
					if (!current.startsWith(root)) {
						throw new IOException("Symlink escapes restore root: " + link + " -> " + linkTarget);
					}
				}
			}
		}

		private static void prepareTargetFile(Path targetPath) throws IOException {
			if (!Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS)) {
				Files.deleteIfExists(targetPath);
			}
		}

		private void fixJellyfinPermissions() {
			try {
				System.out.print("Applying 'jellyfin:jellyfin' ownership and file permissions... ");
				if (new ProcessBuilder("id", "-u", "jellyfin").start().waitFor() == 0) {
					new ProcessBuilder("chown", "-R", "jellyfin:jellyfin", configDir.toString()).start()
						.waitFor();
					new ProcessBuilder("chown", "-R", "jellyfin:jellyfin", dataDir.toString()).start()
						.waitFor();
					System.out.println("OK");
				} else {
					System.out.println("SKIPPED ('jellyfin' user not found on system)");
				}
			} catch (Exception e) {
				System.out.println("FAILED (" + e.getMessage() + ")");
			}
		}
	}

	// =========================================================================
	// SUBCOMMAND: INSPECT
	// =========================================================================
	@Command(name = "inspect", mixinStandardHelpOptions = true, description = "Inspect the contents, manifest, and database metrics of a backup archive.")
	static class InspectCmd implements Callable<Integer> {

		@Parameters(index = "0", description = "Path to the .tar.gz backup archive.")
		private Path archiveFile;

		@Override
		public Integer call() throws Exception {
			if (!Files.isRegularFile(archiveFile)) {
				System.err.printf("Error: Backup archive '%s' does not exist.%n", archiveFile);
				return 1;
			}

			System.out.println("===============================================================");
			System.out.println("  Jellyfin Backup Archive Inspection");
			System.out.println("===============================================================");
			System.out.printf("Archive File: %s (%s)%n", archiveFile.toAbsolutePath(),
					formatBytes(Files.size(archiveFile)));
			System.out.println("---------------------------------------------------------------");

			System.out.print("Integrity Check (SHA-256): ");
			String checksumError = verifyArchiveChecksum(archiveFile);
			if (checksumError == null) {
				System.out.println("VALID");
			} else {
				System.out.println("FAILED / " + checksumError);
			}
			System.out.println();

			String manifestJson = null;
			List<String> plugins = new ArrayList<>();
			List<String> containerConfigs = new ArrayList<>();
			long totalUncompressedSize = 0;
			int totalEntries = 0;
			long dbSize = 0;

			try (InputStream fis = Files.newInputStream(archiveFile);
					BufferedInputStream bis = new BufferedInputStream(fis, 128 * 1024);
					GZIPInputStream gzis = new GZIPInputStream(bis);
					TarArchiveInputStream tarIn = new TarArchiveInputStream(gzis)) {

				TarArchiveEntry entry;
				while ((entry = tarIn.getNextEntry()) != null) {
					totalEntries++;
					totalUncompressedSize += entry.getSize();
					String name = entry.getName();

					if (name.equals(MANIFEST_ENTRY_NAME)) {
						manifestJson = new String(tarIn.readAllBytes(), StandardCharsets.UTF_8);
					} else if ((name.startsWith("quadlet/") || name.startsWith("compose/"))
							&& !entry.isDirectory()) {
						containerConfigs.add(name);
					} else if (name.contains("plugins/")) {
						String[] parts = name.split("/");
						for (int i = 0; i < parts.length - 1; i++) {
							if (parts[i].equals("plugins") && !parts[i + 1].isEmpty()
									&& !parts[i + 1].equals("configurations")) {
								String pluginName = parts[i + 1];
								if (!plugins.contains(pluginName)) {
									plugins.add(pluginName);
								}
							}
						}
					} else if (name.endsWith("jellyfin.db") || name.endsWith("library.db")) {
						dbSize += entry.getSize();
					}
				}
			}

			if (manifestJson != null) {
				System.out.println("Manifest Details:");
				for (String line : manifestJson.lines().toList()) {
					line = line.trim();
					if (!line.equals("{") && !line.equals("}")) {
						System.out.println("  " + line.replace("\"", ""));
					}
				}
				System.out.println();
			}

			System.out.printf("Total Files:             %d%n", totalEntries);
			System.out.printf("Uncompressed Size:       %s%n", formatBytes(totalUncompressedSize));
			System.out.printf("Database Payload Size:   %s%n", formatBytes(dbSize));
			if (!containerConfigs.isEmpty()) {
				System.out.println("\nContainer Definitions (" + containerConfigs.size() + "):");
				for (String cfg : containerConfigs) {
					System.out.println("  - " + cfg);
				}
			}
			if (!plugins.isEmpty()) {
				System.out.println("\nInstalled Plugins (" + plugins.size() + "):");
				for (String p : plugins) {
					System.out.println("  - " + p);
				}
			}
			System.out.println("===============================================================");

			return 0;
		}
	}

	// =========================================================================
	// SYSTEM & HELPER METHODS
	// =========================================================================
	static boolean needsElevation(String... args) {
		if (args == null || args.length == 0) {
			return false;
		}
		for (String arg : args) {
			if (arg.equals("-h") || arg.equals("--help") || arg.equals("-v") || arg.equalsIgnoreCase("-V")
					|| arg.equals("--version")) {
				return false;
			}
		}
		String first = args[0].toLowerCase(Locale.ROOT);
		return first.equals("backup") || first.equals("restore");
	}

	static List<String> buildSudoCommand(String selfCommand, String[] jvmArgs, String... args) {
		boolean isJava = selfCommand != null && (selfCommand.endsWith("/java") || selfCommand.equals("java")
				|| selfCommand.endsWith("/java.exe") || selfCommand.endsWith("java"));

		List<String> sudoCmd = new ArrayList<>();
		sudoCmd.add("sudo");

		if (isJava) {
			if (jvmArgs != null && jvmArgs.length > 0) {
				sudoCmd.add(selfCommand);
				Collections.addAll(sudoCmd, jvmArgs);
			} else {
				String classPath = System.getProperty("java.class.path");
				if (classPath != null && !classPath.isBlank()) {
					sudoCmd.add(selfCommand);
					sudoCmd.add("-cp");
					sudoCmd.add(classPath);
					sudoCmd.add("jellyfinbackup.JellyfinBackup");
					if (args != null) {
						Collections.addAll(sudoCmd, args);
					}
				} else {
					String userHome = System.getProperty("user.home", "");
					Path binaryPath = Path.of(userHome, ".local", "bin", "jellyfin-backup");
					if (!Files.isExecutable(binaryPath)) {
						binaryPath = Path.of(userHome, ".jbang", "bin", "jellyfin-backup");
					}
					if (Files.isExecutable(binaryPath)) {
						sudoCmd.add(binaryPath.toAbsolutePath().toString());
						if (args != null) {
							Collections.addAll(sudoCmd, args);
						}
					}
				}
			}
		} else if (selfCommand != null && Files.isExecutable(Path.of(selfCommand))) {
			sudoCmd.add(selfCommand);
			if (args != null) {
				Collections.addAll(sudoCmd, args);
			}
		} else {
			String userHome = System.getProperty("user.home", "");
			Path binaryPath = Path.of(userHome, ".local", "bin", "jellyfin-backup");
			if (!Files.isExecutable(binaryPath)) {
				binaryPath = Path.of(userHome, ".jbang", "bin", "jellyfin-backup");
			}
			if (Files.isExecutable(binaryPath)) {
				sudoCmd.add(binaryPath.toAbsolutePath().toString());
				if (args != null) {
					Collections.addAll(sudoCmd, args);
				}
			}
		}

		return sudoCmd.size() > 1 ? sudoCmd : null;
	}

	static int reexecWithSudo(String... args) {
		String selfCommand = ProcessHandle.current().info().command().orElse(null);
		String[] jvmArgs = ProcessHandle.current().info().arguments().orElse(null);
		List<String> sudoCmd = buildSudoCommand(selfCommand, jvmArgs, args);
		if (sudoCmd == null) {
			System.err
				.println("Error: 'jellyfin-backup' requires root privileges for backup and restore.");
			System.err.println(
					"Please run with sudo: sudo jbang jellyfin-backup@alaurie " + String.join(" ", args));
			return 1;
		}

		try {
			Process process = new ProcessBuilder(sudoCmd).inheritIO().start();
			return process.waitFor();
		} catch (Exception e) {
			System.err.println("Error executing sudo: " + e.getMessage());
			return 1;
		}
	}

	static String verifyArchiveChecksum(Path archiveFile) {
		Path shaFile = Path.of(archiveFile.toString() + ".sha256");
		if (!Files.isRegularFile(shaFile)) {
			return "No .sha256 sidecar file found";
		}

		try {
			String expectedHash = null;
			for (String line : Files.readAllLines(shaFile, StandardCharsets.UTF_8)) {
				line = line.trim();
				if (!line.isEmpty() && !line.startsWith("#")) {
					String[] parts = line.split("\\s+");
					if (parts.length >= 1) {
						expectedHash = parts[0].trim();
						break;
					}
				}
			}

			if (expectedHash == null || expectedHash.isBlank()) {
				return "Empty or malformed .sha256 file";
			}

			String actualHash = computeFileSha256(archiveFile);
			if (expectedHash.equalsIgnoreCase(actualHash)) {
				return null; // Valid
			} else {
				return "Hash mismatch: expected " + expectedHash + ", actual " + actualHash;
			}
		} catch (Exception e) {
			return "Error reading checksum: " + e.getMessage();
		}
	}

	static String computeFileSha256(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024);
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			while (channel.read(buffer) > 0) {
				buffer.flip();
				digest.update(buffer);
				buffer.clear();
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	static boolean isRunningAsRoot() {
		String user = System.getProperty("user.name");
		return "root".equals(user);
	}

	static boolean isLinux() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
	}

	/// Returns whether this process is running on Windows.
	static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	}

	/// Resolves Jellyfin's data root, preferring the standard Windows installation.
	static Path defaultDataDir() {
		String override = System.getenv("JELLYFIN_DATA_DIR");
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		if (!isWindows()) {
			return Path.of("/var/lib/jellyfin");
		}
		var home = Path.of(System.getProperty("user.home"));
		String programData = System.getenv("ProgramData");
		Path shared = (programData == null || programData.isBlank()
				? home.toAbsolutePath().getRoot().resolve("ProgramData")
				: Path.of(programData))
			.resolve("Jellyfin")
			.resolve("Server");
		String localAppData = System.getenv("LOCALAPPDATA");
		Path local = (localAppData == null || localAppData.isBlank()
				? home.resolve("AppData").resolve("Local")
				: Path.of(localAppData))
			.resolve("jellyfin");
		return Files.isDirectory(shared) || !Files.isDirectory(local) ? shared : local;
	}

	/// Resolves the config root after applying an explicit or default
	/// data directory.
	static Path defaultConfigDir(Path dataDir) {
		String override = System.getenv("JELLYFIN_CONFIG_DIR");
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		return isWindows() ? dataDir.resolve("config") : Path.of("/etc/jellyfin");
	}

	/// Returns the platform's standard Jellyfin service name.
	static String defaultServiceName() {
		return isWindows() ? "JellyfinServer" : "jellyfin";
	}

	/// Refuses to copy a live Windows tray/portable instance that service control
	/// cannot stop.
	private static void ensureWindowsServerStopped() throws IOException, InterruptedException {
		if (!isWindows()) {
			return;
		}
		var process = new ProcessBuilder("tasklist.exe", "/FI", "IMAGENAME eq jellyfin.exe", "/FO", "CSV", "/NH")
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (process.waitFor() != 0) {
			throw new IOException("Cannot check for running Jellyfin processes: " + output.strip());
		}
		if (output.toLowerCase(Locale.ROOT).contains("\"jellyfin.exe\"")) {
			throw new IOException("Jellyfin is still running. Quit the tray/portable server before continuing."
					+ " Use --no-stop only if you accept copying a live database.");
		}
	}

	/// Queries the platform service manager; Windows errors must not be mistaken
	/// for a stopped server.
	static boolean isServiceRunning(String serviceName) throws IOException, InterruptedException {
		if (isWindows()) {
			return windowsServiceCommand(serviceName, "query") == 4;
		}
		return isSystemdServiceActive(serviceName);
	}

	static boolean shouldElevate(String... args) {
		if (!needsElevation(args) || isRunningAsRoot() || !isLinux()) {
			return false;
		}
		String containerArg = extractOptionValue(args, "--container");
		String configArg = extractOptionValue(args, "-c", "--config-dir");
		String dataArg = extractOptionValue(args, "-d", "--data-dir");

		if (containerArg != null) {
			ContainerInfo container = detectContainer(containerArg);
			if (container != null && !requiresRootPath(container.configDir(), configArg)
					&& !requiresRootPath(container.dataDir(), dataArg)) {
				return false;
			}
			return true;
		}

		if (configArg != null || dataArg != null) {
			return requiresRootPath(null, configArg) || requiresRootPath(null, dataArg);
		}

		ContainerInfo container = detectContainer(null);
		if (container != null && !requiresRootPath(container.configDir(), null)
				&& !requiresRootPath(container.dataDir(), null)) {
			return false;
		}
		return true;
	}

	private static boolean requiresRootPath(Path resolvedPath, String explicitArg) {
		String pathStr = explicitArg != null ? explicitArg
				: (resolvedPath != null ? resolvedPath.toString() : "");
		return pathStr.equals("/etc") || pathStr.startsWith("/etc/")
				|| pathStr.equals("/var") || pathStr.startsWith("/var/");
	}

	private static String extractOptionValue(String[] args, String... names) {
		if (args == null) {
			return null;
		}
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			for (String name : names) {
				if (arg.equals(name) && i + 1 < args.length) {
					return args[i + 1];
				}
				if (arg.startsWith(name + "=")) {
					return arg.substring(name.length() + 1);
				}
			}
		}
		return null;
	}

	static TargetInstallation resolveTargetInstallation(Path explicitConfigDir, Path explicitDataDir,
			String requestedContainer, String serviceNameOverride) throws IOException, InterruptedException {
		String effectiveService = (serviceNameOverride == null || serviceNameOverride.isBlank())
				? defaultServiceName()
				: serviceNameOverride;
		Path defaultData = defaultDataDir();
		Path defaultConfig = defaultConfigDir(defaultData);

		if (requestedContainer != null && !requestedContainer.isBlank()) {
			ContainerInfo container = detectContainer(requestedContainer);
			if (container == null) {
				throw new IllegalArgumentException(
						"Container '%s' not found via podman, docker, or systemd."
							.formatted(requestedContainer));
			}
			Path resolvedData = explicitDataDir != null ? explicitDataDir
					: (container.dataDir() != null ? container.dataDir() : defaultData);
			Path resolvedConfig = explicitConfigDir != null ? explicitConfigDir
					: (container.configDir() != null ? container.configDir()
							: defaultConfigDir(resolvedData));
			String serviceName = container.serviceScope() == ServiceScope.CONTAINER
					? container.name()
					: container.systemdUnit();
			return new TargetInstallation(resolvedConfig, resolvedData, container,
					container.serviceScope(), serviceName);
		}

		if (explicitConfigDir != null || explicitDataDir != null) {
			Path resolvedData = explicitDataDir != null ? explicitDataDir : defaultData;
			Path resolvedConfig = explicitConfigDir != null ? explicitConfigDir
					: defaultConfigDir(resolvedData);
			return new TargetInstallation(resolvedConfig, resolvedData, null,
					ServiceScope.SYSTEMD_SYSTEM, effectiveService);
		}

		if (!isWindows()) {
			if (!isRunningAsRoot()) {
				ContainerInfo container = detectContainer(null);
				if (container != null) {
					Path resolvedData = container.dataDir() != null ? container.dataDir() : defaultData;
					Path resolvedConfig = container.configDir() != null ? container.configDir()
							: defaultConfigDir(resolvedData);
					String serviceName = container.serviceScope() == ServiceScope.CONTAINER
							? container.name()
							: container.systemdUnit();
					return new TargetInstallation(resolvedConfig, resolvedData, container,
							container.serviceScope(), serviceName);
				}
			} else {
				boolean nativeExists = Files.isDirectory(defaultConfig)
						|| Files.isDirectory(defaultData)
						|| isSystemdServiceActive(effectiveService);
				if (!nativeExists) {
					ContainerInfo container = detectContainer(null);
					if (container != null) {
						Path resolvedData = container.dataDir() != null ? container.dataDir() : defaultData;
						Path resolvedConfig = container.configDir() != null ? container.configDir()
								: defaultConfigDir(resolvedData);
						String serviceName = container.serviceScope() == ServiceScope.CONTAINER
								? container.name()
								: container.systemdUnit();
						return new TargetInstallation(resolvedConfig, resolvedData, container,
								container.serviceScope(), serviceName);
					}
				}
			}
		}

		return new TargetInstallation(defaultConfig, defaultData, null,
				ServiceScope.SYSTEMD_SYSTEM, effectiveService);
	}

	static String describeTarget(TargetInstallation target) {
		ContainerInfo c = target.container();
		if (c == null) {
			return "Native systemd";
		}
		if (target.serviceScope() == ServiceScope.SYSTEMD_USER) {
			return "Container (%s: %s, systemd --user: %s)".formatted(c.runtime(), c.name(),
					c.systemdUnit());
		}
		if (target.serviceScope() == ServiceScope.SYSTEMD_SYSTEM) {
			return "Container (%s: %s, systemd: %s)".formatted(c.runtime(), c.name(), c.systemdUnit());
		}
		return "Container (%s: %s)".formatted(c.runtime(), c.name());
	}

	static String targetStatusHint(TargetInstallation target) {
		return switch (target.serviceScope()) {
		case SYSTEMD_USER -> "'systemctl --user status " + target.serviceName() + "'";
		case SYSTEMD_SYSTEM -> "'systemctl status " + target.serviceName() + "'";
		case CONTAINER -> "'" + target.container().runtime() + " ps -a'";
		case NONE -> "service status";
		};
	}

	static ContainerInfo detectContainer(String requestedName) {
		String[] runtimes = { "podman", "docker" };
		if (requestedName != null && !requestedName.isBlank()) {
			for (String runtime : runtimes) {
				ContainerInfo info = inspectContainer(runtime, requestedName);
				if (info != null) {
					return enrichSystemdScope(info);
				}
			}
			return detectContainerFromSystemd(requestedName);
		}

		ContainerInfo fallback = null;
		for (String runtime : runtimes) {
			String candidate = findJellyfinContainerName(runtime);
			if (candidate != null) {
				ContainerInfo info = inspectContainer(runtime, candidate);
				if (info != null) {
					info = enrichSystemdScope(info);
					if (info.running()) {
						return info;
					}
					if (fallback == null) {
						fallback = info;
					}
				}
			}
		}
		if (fallback != null) {
			return fallback;
		}
		return detectContainerFromSystemd("jellyfin");
	}

	private static String findJellyfinContainerName(String runtime) {
		String out = runCommandOutput(runtime, "ps", "-a", "--format",
				"{{.Names}}\t{{.Image}}\t{{.State}}");
		if (out == null || out.isBlank()) {
			return null;
		}
		String stoppedMatch = null;
		String imageMatch = null;
		for (String line : out.lines().toList()) {
			String[] parts = line.split("\t", -1);
			if (parts.length < 3) {
				continue;
			}
			String name = parts[0].trim();
			String image = parts[1].trim().toLowerCase(Locale.ROOT);
			String state = parts[2].trim().toLowerCase(Locale.ROOT);
			boolean running = state.startsWith("up") || state.equals("running");
			if (name.equalsIgnoreCase("jellyfin")) {
				if (running) {
					return name;
				}
				stoppedMatch = name;
			} else if (image.contains("jellyfin")) {
				if (running && imageMatch == null) {
					imageMatch = name;
				} else if (stoppedMatch == null) {
					stoppedMatch = name;
				}
			}
		}
		return imageMatch != null ? imageMatch : stoppedMatch;
	}

	private static ContainerInfo inspectContainer(String runtime, String name) {
		String format = """
				{{.State.Running}}
				{{.Config.Image}}
				{{index .Config.Labels "PODMAN_SYSTEMD_UNIT"}}
				{{index .Config.Labels "org.opencontainers.image.version"}}
				{{index .Config.Labels "com.docker.compose.project.config_files"}}
				{{index .Config.Labels "com.docker.compose.project.working_dir"}}
				---ENV---
				{{range .Config.Env}}{{println .}}{{end}}---MOUNTS---
				{{range .Mounts}}{{.Type}}{{"\\t"}}{{.Source}}{{"\\t"}}{{.Destination}}{{"\\n"}}{{end}}""";
		String out = runCommandOutput(runtime, "inspect", name, "--format", format);
		if (out == null || out.isBlank()) {
			return null;
		}
		return parseContainerInspect(runtime, name, out);
	}

	static ContainerInfo parseContainerInspect(String runtime, String name, String inspectOutput) {
		List<String> lines = inspectOutput.lines().toList();
		if (lines.size() < 4) {
			return null;
		}
		boolean running = "true".equalsIgnoreCase(lines.getFirst().trim());
		String image = lines.get(1).trim();
		String systemdUnit = cleanInspectValue(lines.get(2));
		String imageVersion = cleanInspectValue(lines.get(3));
		String composeConfigFiles = "";
		String composeWorkingDir = "";

		int bodyStart = 4;
		if (lines.size() > 5 && !lines.get(4).equals("---ENV---")) {
			composeConfigFiles = cleanInspectValue(lines.get(4));
			bodyStart = 5;
			if (lines.size() > 6 && !lines.get(5).equals("---ENV---")) {
				composeWorkingDir = cleanInspectValue(lines.get(5));
				bodyStart = 6;
			}
		}

		Map<String, String> env = new LinkedHashMap<>();
		List<MountInfo> mounts = new ArrayList<>();
		String section = "";
		for (int i = bodyStart; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.equals("---ENV---")) {
				section = "ENV";
				continue;
			}
			if (line.equals("---MOUNTS---")) {
				section = "MOUNTS";
				continue;
			}
			if (section.equals("ENV")) {
				int eq = line.indexOf('=');
				if (eq > 0) {
					env.put(line.substring(0, eq), line.substring(eq + 1));
				}
			} else if (section.equals("MOUNTS")) {
				String[] parts = line.split("\t", -1);
				if (parts.length >= 3) {
					mounts.add(new MountInfo(parts[0].trim(), parts[1].trim(), parts[2].trim()));
				}
			}
		}

		Path configDir = resolveJellyfinHostDir(env, mounts, true);
		Path dataDir = resolveJellyfinHostDir(env, mounts, false);
		Path composeFile = resolveComposeFile(composeConfigFiles, composeWorkingDir);
		ServiceScope scope = systemdUnit.isEmpty() ? ServiceScope.CONTAINER
				: ServiceScope.SYSTEMD_SYSTEM;
		return new ContainerInfo(runtime, name, running, image, imageVersion,
				systemdUnit.isEmpty() ? null : systemdUnit, scope, configDir, dataDir, null,
				composeFile);
	}

	private static String cleanInspectValue(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.equals("<no value>") ? "" : trimmed;
	}

	static Path resolveComposeFile(String configFiles, String workingDir) {
		if (configFiles != null && !configFiles.isBlank()) {
			for (String entry : configFiles.split(",")) {
				String trimmed = entry.trim();
				if (trimmed.isEmpty()) {
					continue;
				}
				Path candidate = Path.of(trimmed);
				if (!candidate.isAbsolute() && workingDir != null && !workingDir.isBlank()) {
					candidate = Path.of(workingDir).resolve(candidate);
				}
				if (Files.isRegularFile(candidate)) {
					return candidate.normalize();
				}
			}
			// Return first specified path even if not yet checked on disk when absolute
			String first = configFiles.split(",")[0].trim();
			if (!first.isEmpty()) {
				Path p = Path.of(first);
				if (!p.isAbsolute() && workingDir != null && !workingDir.isBlank()) {
					return Path.of(workingDir).resolve(p).normalize();
				}
				return p.normalize();
			}
		}
		if (workingDir != null && !workingDir.isBlank()) {
			Path dir = Path.of(workingDir);
			for (String name : List.of("compose.yaml", "compose.yml", "docker-compose.yaml",
					"docker-compose.yml")) {
				Path candidate = dir.resolve(name);
				if (Files.isRegularFile(candidate)) {
					return candidate.normalize();
				}
			}
		}
		return null;
	}

	private static Path resolveJellyfinHostDir(Map<String, String> env, List<MountInfo> mounts,
			boolean isConfig) {
		String envKey = isConfig ? "JELLYFIN_CONFIG_DIR" : "JELLYFIN_DATA_DIR";
		String containerPath = env.get(envKey);
		if (containerPath != null && !containerPath.isBlank()) {
			Path resolved = resolveContainerHostPath(containerPath, mounts);
			if (resolved != null) {
				return resolved;
			}
		}

		String defaultSplit = isConfig ? "/etc/jellyfin" : "/var/lib/jellyfin";
		Path splitResolved = resolveContainerHostPath(defaultSplit, mounts);
		if (splitResolved != null) {
			return splitResolved;
		}

		String fallbackContainerPath = isConfig ? "/config/config" : "/config";
		Path configMount = resolveContainerHostPath(fallbackContainerPath, mounts);
		if (configMount != null) {
			return configMount;
		}
		return isConfig ? resolveContainerHostPath("/config", mounts) : null;
	}

	static Path resolveContainerHostPath(String containerPath, List<MountInfo> mounts) {
		if (containerPath == null || containerPath.isBlank() || mounts == null) {
			return null;
		}
		String normalizedTarget = trimTrailingSlash(containerPath.trim());
		MountInfo bestMatch = null;
		int bestLen = -1;
		for (MountInfo m : mounts) {
			if (m.source() == null || m.source().isBlank() || m.destination() == null) {
				continue;
			}
			String dest = trimTrailingSlash(m.destination().trim());
			boolean matches = normalizedTarget.equals(dest)
					|| normalizedTarget.startsWith(dest + "/")
					|| dest.equals("/");
			if (matches && dest.length() > bestLen) {
				bestMatch = m;
				bestLen = dest.length();
			}
		}
		if (bestMatch == null) {
			return null;
		}
		String dest = trimTrailingSlash(bestMatch.destination().trim());
		Path hostBase = Path.of(bestMatch.source());
		if (normalizedTarget.equals(dest)) {
			return hostBase;
		}
		String rel = dest.equals("/") ? normalizedTarget.substring(1)
				: normalizedTarget.substring(dest.length() + 1);
		Path resolved = hostBase;
		for (String part : rel.split("/")) {
			if (!part.isEmpty()) {
				resolved = resolved.resolve(part);
			}
		}
		return resolved;
	}

	private static String trimTrailingSlash(String path) {
		while (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return path;
	}

	private static ContainerInfo enrichSystemdScope(ContainerInfo info) {
		if (!isLinux()) {
			return info;
		}
		String unit = info.systemdUnit();
		if (unit == null || unit.isBlank()) {
			String candidateUnit = info.name() + ".service";
			if (!isRunningAsRoot() && isSystemdUserServiceLoaded(candidateUnit)) {
				Path quadlet = detectQuadletSourcePath(candidateUnit, true);
				return new ContainerInfo(info.runtime(), info.name(), info.running(), info.image(),
						info.imageVersion(), candidateUnit, ServiceScope.SYSTEMD_USER,
						info.configDir(), info.dataDir(), quadlet, info.composeFile());
			}
			return info;
		}
		if (!isRunningAsRoot() && isSystemdUserServiceLoaded(unit)) {
			Path quadlet = detectQuadletSourcePath(unit, true);
			return new ContainerInfo(info.runtime(), info.name(), info.running(), info.image(),
					info.imageVersion(), unit, ServiceScope.SYSTEMD_USER, info.configDir(),
					info.dataDir(), quadlet, info.composeFile());
		}
		if (isSystemdServiceLoaded(unit)) {
			Path quadlet = detectQuadletSourcePath(unit, false);
			return new ContainerInfo(info.runtime(), info.name(), info.running(), info.image(),
					info.imageVersion(), unit, ServiceScope.SYSTEMD_SYSTEM, info.configDir(),
					info.dataDir(), quadlet, info.composeFile());
		}
		return info;
	}

	private static Path detectQuadletSourcePath(String unit, boolean userScope) {
		String sourcePath = userScope
				? runCommandOutput("systemctl", "--user", "show", "-p", "SourcePath", "--value", unit)
				: runCommandOutput("systemctl", "show", "-p", "SourcePath", "--value", unit);
		if (sourcePath != null && sourcePath.endsWith(".container")) {
			Path p = Path.of(sourcePath);
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		String baseName = unit.endsWith(".service")
				? unit.substring(0, unit.length() - ".service".length())
				: unit;
		Path fallback = defaultQuadletDir(userScope).resolve(baseName + ".container");
		return Files.isRegularFile(fallback) ? fallback : null;
	}

	static Path defaultQuadletDir(boolean userScope) {
		if (userScope) {
			return Path.of(System.getProperty("user.home", ""), ".config", "containers", "systemd");
		}
		return Path.of("/etc/containers/systemd");
	}

	private static void reloadSystemdDaemon(boolean userScope) {
		if (!isLinux()) {
			return;
		}
		if (userScope) {
			runCommandSuccess("systemctl", "--user", "daemon-reload");
		} else {
			runCommandSuccess("systemctl", "daemon-reload");
		}
	}

	private static ContainerInfo detectContainerFromSystemd(String name) {
		if (!isLinux()) {
			return null;
		}
		String unit = name.endsWith(".service") ? name : name + ".service";
		if (!isRunningAsRoot() && isSystemdUserServiceLoaded(unit)) {
			String content = runCommandOutput("systemctl", "--user", "cat", unit);
			if (content != null && !content.isBlank()) {
				ContainerInfo parsed = parseSystemdUnitContainer(unit, ServiceScope.SYSTEMD_USER,
						content, isSystemdUserServiceActive(unit));
				if (parsed != null) {
					return new ContainerInfo(parsed.runtime(), parsed.name(), parsed.running(),
							parsed.image(), parsed.imageVersion(), parsed.systemdUnit(),
							parsed.serviceScope(), parsed.configDir(), parsed.dataDir(),
							detectQuadletSourcePath(unit, true), null);
				}
			}
		}
		if (isSystemdServiceLoaded(unit)) {
			String content = runCommandOutput("systemctl", "cat", unit);
			if (content != null && !content.isBlank()) {
				boolean active = false;
				try {
					active = isSystemdServiceActive(unit);
				} catch (Exception _) {
				}
				ContainerInfo parsed = parseSystemdUnitContainer(unit, ServiceScope.SYSTEMD_SYSTEM,
						content, active);
				if (parsed != null) {
					return new ContainerInfo(parsed.runtime(), parsed.name(), parsed.running(),
							parsed.image(), parsed.imageVersion(), parsed.systemdUnit(),
							parsed.serviceScope(), parsed.configDir(), parsed.dataDir(),
							detectQuadletSourcePath(unit, false), null);
				}
			}
		}
		return null;
	}

	static ContainerInfo parseSystemdUnitContainer(String unitName, ServiceScope scope,
			String unitContent, boolean active) {
		boolean looksLikeContainer = unitContent.contains("[X-Container]")
				|| unitContent.contains("podman ") || unitContent.contains("docker ")
				|| unitContent.contains("PODMAN_SYSTEMD_UNIT");
		if (!looksLikeContainer) {
			return null;
		}
		String runtime = unitContent.contains("docker ") && !unitContent.contains("podman")
				? "docker"
				: "podman";
		String containerName = unitName.endsWith(".service")
				? unitName.substring(0, unitName.length() - ".service".length())
				: unitName;
		String image = "";
		Map<String, String> env = new LinkedHashMap<>();
		List<MountInfo> mounts = new ArrayList<>();
		String userHome = System.getProperty("user.home", "");

		for (String rawLine : unitContent.lines().toList()) {
			String line = rawLine.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			if (line.startsWith("ContainerName=")) {
				containerName = line.substring("ContainerName=".length()).trim();
			} else if (line.startsWith("Image=")) {
				image = line.substring("Image=".length()).trim();
			} else if (line.startsWith("Volume=")) {
				addVolumeSpec(line.substring("Volume=".length()).trim(), userHome, mounts);
			} else if (line.startsWith("Environment=")) {
				String envVal = line.substring("Environment=".length()).trim();
				int eq = envVal.indexOf('=');
				if (eq > 0) {
					env.put(envVal.substring(0, eq), envVal.substring(eq + 1));
				}
			} else if (line.startsWith("ExecStart=")) {
				String exec = line.substring("ExecStart=".length()).trim();
				String[] tokens = exec.split("\\s+");
				for (int i = 0; i < tokens.length; i++) {
					if ((tokens[i].equals("-v") || tokens[i].equals("--volume"))
							&& i + 1 < tokens.length) {
						addVolumeSpec(tokens[++i], userHome, mounts);
					} else if ((tokens[i].equals("-e") || tokens[i].equals("--env"))
							&& i + 1 < tokens.length) {
						String envVal = tokens[++i];
						int eq = envVal.indexOf('=');
						if (eq > 0) {
							env.put(envVal.substring(0, eq), envVal.substring(eq + 1));
						}
					} else if (tokens[i].equals("--name") && i + 1 < tokens.length) {
						containerName = tokens[++i];
					}
				}
			}
		}

		Path configDir = resolveJellyfinHostDir(env, mounts, true);
		Path dataDir = resolveJellyfinHostDir(env, mounts, false);
		String imageVersion = extractImageTag(image);
		return new ContainerInfo(runtime, containerName, active, image, imageVersion, unitName,
				scope, configDir, dataDir, null, null);
	}

	private static void addVolumeSpec(String spec, String userHome, List<MountInfo> mounts) {
		String expanded = spec.replace("%h", userHome);
		String[] parts = expanded.split(":");
		if (parts.length >= 2) {
			mounts.add(new MountInfo("bind", parts[0], parts[1]));
		}
	}

	static String extractImageTag(String image) {
		if (image == null || image.isBlank()) {
			return "";
		}
		int lastSlash = image.lastIndexOf('/');
		int colon = image.lastIndexOf(':');
		if (colon > lastSlash && colon + 1 < image.length()) {
			return image.substring(colon + 1);
		}
		return "";
	}

	static boolean isTargetActive(TargetInstallation target) throws IOException, InterruptedException {
		return switch (target.serviceScope()) {
		case SYSTEMD_USER -> isSystemdUserServiceActive(target.serviceName());
		case SYSTEMD_SYSTEM -> isServiceRunning(target.serviceName());
		case CONTAINER -> target.container() != null && target.container().running();
		case NONE -> false;
		};
	}

	static boolean stopTarget(TargetInstallation target) throws IOException, InterruptedException {
		return switch (target.serviceScope()) {
		case SYSTEMD_USER -> stopSystemdUserService(target.serviceName());
		case SYSTEMD_SYSTEM -> {
			changeServiceState(target.serviceName(), false);
			yield true;
		}
		case CONTAINER -> target.container() != null
				&& runCommandSuccess(target.container().runtime(), "stop", target.serviceName());
		case NONE -> true;
		};
	}

	static boolean startTarget(TargetInstallation target) throws IOException, InterruptedException {
		return switch (target.serviceScope()) {
		case SYSTEMD_USER -> startSystemdUserService(target.serviceName());
		case SYSTEMD_SYSTEM -> {
			changeServiceState(target.serviceName(), true);
			yield true;
		}
		case CONTAINER -> target.container() != null
				&& runCommandSuccess(target.container().runtime(), "start", target.serviceName());
		case NONE -> true;
		};
	}

	static String detectServerVersion(ContainerInfo container) {
		if (container != null) {
			if (container.running()) {
				for (String bin : List.of("/jellyfin/jellyfin", "jellyfin")) {
					String out = runCommandOutput(container.runtime(), "exec", container.name(), bin,
							"--version");
					if (out != null && !out.isBlank()) {
						return out.trim();
					}
				}
			}
			if (container.imageVersion() != null && !container.imageVersion().isBlank()) {
				return container.imageVersion();
			}
			String tag = extractImageTag(container.image());
			if (!tag.isBlank()) {
				return tag;
			}
		}
		return detectJellyfinVersion();
	}

	private static String runCommandOutput(String... cmd) {
		try {
			Process process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
			String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
				.trim();
			int code = process.waitFor();
			return code == 0 ? out : null;
		} catch (Exception _) {
			return null;
		}
	}

	private static boolean runCommandSuccess(String... cmd) {
		try {
			return new ProcessBuilder(cmd).start().waitFor() == 0;
		} catch (Exception _) {
			return false;
		}
	}

	static boolean isSystemdServiceLoaded(String serviceName) {
		if (!isLinux()) {
			return false;
		}
		String out = runCommandOutput("systemctl", "show", "-p", "LoadState", "--value", serviceName);
		return "loaded".equalsIgnoreCase(out);
	}

	static boolean isSystemdUserServiceLoaded(String serviceName) {
		if (!isLinux()) {
			return false;
		}
		String out = runCommandOutput("systemctl", "--user", "show", "-p", "LoadState", "--value",
				serviceName);
		return "loaded".equalsIgnoreCase(out);
	}

	static boolean isSystemdUserServiceActive(String serviceName) {
		if (!isLinux()) {
			return false;
		}
		String out = runCommandOutput("systemctl", "--user", "is-active", serviceName);
		return "active".equalsIgnoreCase(out);
	}

	static boolean stopSystemdUserService(String serviceName) {
		return runCommandSuccess("systemctl", "--user", "stop", serviceName);
	}

	static boolean startSystemdUserService(String serviceName) {
		return runCommandSuccess("systemctl", "--user", "start", serviceName);
	}

	static boolean isSystemdServiceActive(String serviceName) throws IOException, InterruptedException {
		if (!isLinux()) {
			return false;
		}
		var process = new ProcessBuilder("systemctl", "is-active", serviceName).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		process.waitFor();
		return "active".equals(output.strip());
	}

	/// Starts or stops a service, waiting for Windows to reach the requested state.
	static void changeServiceState(String serviceName, boolean start) throws IOException, InterruptedException {
		if (isWindows()) {
			windowsServiceCommand(serviceName, start ? "start" : "stop");
			return;
		}
		var process = new ProcessBuilder("systemctl", start ? "start" : "stop", serviceName)
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (process.waitFor() != 0) {
			throw new IOException(output.strip());
		}
	}

	/// Uses Windows PowerShell's service API with literal names, numeric states,
	/// and bounded state transitions.
	private static int windowsServiceCommand(String serviceName, String action)
			throws IOException, InterruptedException {
		String script = """
				$ErrorActionPreference = 'Stop'
				[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
				try {
				    $service = @(Get-Service | Where-Object { $_.Name -eq $env:JBANG_JELLYFIN_SERVICE })
				    if ($service.Count -eq 0) {
				        if ($env:JBANG_JELLYFIN_ACTION -eq 'query') { Write-Output 0; exit 0 }
				        throw 'Jellyfin service is not installed.'
				    }
				    $service = $service[0]
				    if ($env:JBANG_JELLYFIN_ACTION -eq 'query') {
				        if ($service.Status -notin @('Running', 'Stopped')) {
				            throw "Jellyfin service is in state $($service.Status). Wait for it to settle."
				        }
				        Write-Output ([int]$service.Status)
				    } else {
				        $target = if ($env:JBANG_JELLYFIN_ACTION -eq 'start') { 'Running' } else { 'Stopped' }
				        if ($service.Status.ToString() -ne $target) {
				            if ($target -eq 'Running') { $service.Start() } else { $service.Stop() }
				            $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]$target,
				                [TimeSpan]::FromSeconds(60))
				        }
				        Write-Output ([int]$service.Status)
				    }
				} catch {
				    [Console]::Error.WriteLine($_.Exception.Message)
				    exit 1
				}
				""";
		var builder = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
				"-Command", script)
			.redirectErrorStream(true);
		builder.environment().put("JBANG_JELLYFIN_SERVICE", serviceName);
		builder.environment().put("JBANG_JELLYFIN_ACTION", action);
		var process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
		if (process.waitFor() != 0) {
			throw new IOException(output + " Run PowerShell as Administrator to control Windows services.");
		}
		try {
			return Integer.parseInt(output);
		} catch (NumberFormatException e) {
			throw new IOException("Unexpected Windows service response: " + output, e);
		}
	}

	static String detectJellyfinVersion() {
		try {
			String command = "jellyfin";
			if (isWindows()) {
				command = "jellyfin.exe";
				String programFiles = System.getenv("ProgramFiles");
				if (programFiles != null && !programFiles.isBlank()) {
					Path installed = Path.of(programFiles, "Jellyfin", "Server", "jellyfin.exe");
					if (Files.isRegularFile(installed)) {
						command = installed.toString();
					}
				}
			}
			Process p = new ProcessBuilder(command, "--version").start();
			String out = new String(p.getInputStream().readAllBytes()).trim();
			if (!out.isBlank()) {
				return out;
			}
		} catch (Exception _) {
		}
		return "Unknown";
	}

	static String formatBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		int exp = (int) (Math.log(bytes) / Math.log(1024));
		String pre = "KMGTPE".charAt(exp - 1) + "B";
		return String.format(Locale.ROOT, "%.2f %s", bytes / Math.pow(1024, exp), pre);
	}
}
