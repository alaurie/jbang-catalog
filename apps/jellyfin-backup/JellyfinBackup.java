///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//DEPS org.apache.commons:commons-compress:1.27.1
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms16m -Xmx64m
//NATIVE_OPTIONS -O2 -march=native --no-fallback

package jellyfinbackup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
/// Designed for full disaster recovery and OS migrations across Linux distributions.
/// Captures configurations (/etc/jellyfin), live databases (jellyfin.db, library.db),
/// installed plugins, and metadata, while safely stopping the systemd service and excluding
/// temporary cache bloat and previous internal backups.
@Command(name = "jellyfin-backup", mixinStandardHelpOptions = true, version = "jellyfin-backup 1.0",
    description = "Complete backup, restore, and disaster recovery utility for Jellyfin media server.",
    subcommands = {JellyfinBackup.BackupCmd.class, JellyfinBackup.RestoreCmd.class,
        JellyfinBackup.InspectCmd.class})
@SuppressWarnings("unused")
class JellyfinBackup implements Callable<Integer> {

  static final String DEFAULT_CONFIG_DIR = "/etc/jellyfin";
  static final String DEFAULT_DATA_DIR = "/var/lib/jellyfin";
  static final String MANIFEST_ENTRY_NAME = "jellyfin-manifest.json";

  void main(String... args) {
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
  @Command(name = "backup", mixinStandardHelpOptions = true,
      description = "Create a complete, self-contained backup archive of Jellyfin.")
  static class BackupCmd implements Callable<Integer> {

    @Option(names = {"-o", "--output"},
        description = "Target destination directory or .tar.gz archive path. Default: current directory.")
    private Path outputPath;

    @Option(names = {"-c", "--config-dir"},
        description = "Jellyfin configuration directory. Default: /etc/jellyfin")
    private Path configDir = Path.of(DEFAULT_CONFIG_DIR);

    @Option(names = {"-d", "--data-dir"},
        description = "Jellyfin data directory. Default: /var/lib/jellyfin")
    private Path dataDir = Path.of(DEFAULT_DATA_DIR);

    @Option(names = {"--no-stop"},
        description = "Do not stop the Jellyfin systemd service during backup (live copy).")
    private boolean noStop;

    @Option(names = {"--include-cache"},
        description = "Include transcode cache and temporary directories.")
    private boolean includeCache;

    @Option(names = {"--include-internal-backups"},
        description = "Include historical built-in backup zip files located in data/backups/.")
    private boolean includeInternalBackups;

    @Override
    public Integer call() throws Exception {
      boolean isRoot = isRunningAsRoot();
      if (!isRoot) {
        System.err.println(
            "Error: 'jellyfin-backup backup' requires root privileges to read /var/lib/jellyfin and stop services.");
        System.err.println("Please run: sudo ~/.jbang/bin/jellyfin-backup backup [options]");
        return 1;
      }

      if (!Files.isDirectory(configDir) && !Files.isDirectory(dataDir)) {
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
      System.out.printf("Configuration Dir: %s (%s)%n", configDir,
          Files.isDirectory(configDir) ? "Found" : "Not Found");
      System.out.printf("Data Dir:          %s (%s)%n", dataDir,
          Files.isDirectory(dataDir) ? "Found" : "Not Found");
      System.out.printf("Archive Target:    %s%n", finalArchiveFile.toAbsolutePath());
      System.out.println("---------------------------------------------------------------");

      boolean serviceWasRunning = false;
      if (!noStop && isSystemdServiceActive("jellyfin")) {
        serviceWasRunning = true;
        System.out.print("Stopping 'jellyfin' service for clean database snapshot... ");
        if (stopSystemdService("jellyfin")) {
          System.out.println("OK");
        } else {
          System.err.println("FAILED");
          System.err.println(
              "Error: Could not stop jellyfin service. Aborting to prevent inconsistent database state.");
          return 1;
        }
      }

      long startTime = System.currentTimeMillis();
      try {
        createBackupArchive(finalArchiveFile);
      } finally {
        if (serviceWasRunning) {
          System.out.print("Restarting 'jellyfin' service... ");
          if (startSystemdService("jellyfin")) {
            System.out.println("OK");
          } else {
            System.err.println("FAILED (Please check 'systemctl status jellyfin')");
          }
        }
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
      String timestamp =
          LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
      String defaultName = "jellyfin-backup-" + timestamp + ".tar.gz";

      if (out == null) {
        return Path.of(defaultName);
      }
      if (Files.isDirectory(out) || out.toString().endsWith("/") || out.toString().endsWith("\\")) {
        return out.resolve(defaultName);
      }
      return out;
    }

    private void createBackupArchive(Path archivePath) throws Exception {
      Path tempArchive = Path.of(archivePath.toString() + ".tmp");
      Files.deleteIfExists(tempArchive);

      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

      try (FileOutputStream fos = new FileOutputStream(tempArchive.toFile());
          BufferedOutputStream bos = new BufferedOutputStream(fos, 128 * 1024);
          DigestOutputStream dos = new DigestOutputStream(bos, sha256);
          GZIPOutputStream gzos = new GZIPOutputStream(dos);
          TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzos)) {

        tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

        // 1. Archive /etc/jellyfin
        if (Files.isDirectory(configDir)) {
          System.out.print("Archiving configurations (/etc/jellyfin)... ");
          archiveDirectory(configDir, "etc/jellyfin", tarOut, false, false);
          System.out.println("OK");
        }

        // 2. Archive /var/lib/jellyfin
        if (Files.isDirectory(dataDir)) {
          System.out.print("Archiving databases, plugins & metadata (/var/lib/jellyfin)... ");
          archiveDirectory(dataDir, "var/lib/jellyfin", tarOut, includeCache,
              includeInternalBackups);
          System.out.println("OK");
        }

        // 3. Write manifest
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String serverVersion = detectJellyfinVersion();
        String manifestJson = """
            {
              "generator": "jellyfin-backup 1.0",
              "timestamp": "%s",
              "serverVersion": "%s",
              "hasConfig": %b,
              "hasData": %b
            }
            """.formatted(timestamp, serverVersion, Files.isDirectory(configDir),
            Files.isDirectory(dataDir));

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
      Path shaFile = Path.of(archivePath.toString() + ".sha256");
      String shaContent = hexHash + "  " + archivePath.getFileName().toString() + "\n";
      Files.writeString(shaFile, shaContent, StandardCharsets.UTF_8);
      System.out.println("SHA-256: " + hexHash);
    }

    private void archiveDirectory(Path baseDir, String prefix, TarArchiveOutputStream tarOut,
        boolean inclCache, boolean inclBackups) throws Exception {
      Files.walkFileTree(baseDir, new SimpleFileVisitor<Path>() {
        @Override
        public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
            BasicFileAttributes attrs) {
          String relPath = baseDir.relativize(dir).toString();
          if (!relPath.isEmpty()) {
            if (!inclCache && isCachePath(relPath)) {
              return java.nio.file.FileVisitResult.SKIP_SUBTREE;
            }
            if (!inclBackups && isBackupPath(relPath)) {
              return java.nio.file.FileVisitResult.SKIP_SUBTREE;
            }
            try {
              String entryName = prefix + "/" + relPath.replace('\\', '/') + "/";
              TarArchiveEntry entry = new TarArchiveEntry(dir.toFile(), entryName);
              entry.setModTime(attrs.lastModifiedTime().toMillis());
              tarOut.putArchiveEntry(entry);
              tarOut.closeArchiveEntry();
            } catch (Exception _) {
            }
          }
          return java.nio.file.FileVisitResult.CONTINUE;
        }

        @Override
        public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          String relPath = baseDir.relativize(file).toString();
          if (!inclCache && isCachePath(relPath)) {
            return java.nio.file.FileVisitResult.CONTINUE;
          }
          if (!inclBackups
              && (file.toString().endsWith(".zip") || file.toString().endsWith(".tar.gz"))) {
            return java.nio.file.FileVisitResult.CONTINUE;
          }

          String entryName = prefix + "/" + relPath.replace('\\', '/');
          try {
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
          } catch (Exception _) {
            // Ignore permission denied on unreadable files if running without sudo
          }
          return java.nio.file.FileVisitResult.CONTINUE;
        }

        @Override
        public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
          return java.nio.file.FileVisitResult.SKIP_SUBTREE;
        }
      });
    }

    private static boolean isCachePath(String relPath) {
      return relPath.startsWith("transcodes") || relPath.contains("/transcodes")
          || relPath.startsWith("cache") || relPath.contains("/cache") || relPath.startsWith("log")
          || relPath.contains("/log") || relPath.startsWith(".cache");
    }

    private static boolean isBackupPath(String relPath) {
      return relPath.startsWith("data/backups") || relPath.contains("backups/");
    }
  }

  // =========================================================================
  // SUBCOMMAND: RESTORE
  // =========================================================================
  @Command(name = "restore", mixinStandardHelpOptions = true,
      description = "Restore a Jellyfin backup archive into the target system.")
  static class RestoreCmd implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the .tar.gz backup archive.")
    private Path archiveFile;

    @Option(names = {"-c", "--config-dir"},
        description = "Target configuration directory. Default: /etc/jellyfin")
    private Path configDir = Path.of(DEFAULT_CONFIG_DIR);

    @Option(names = {"-d", "--data-dir"},
        description = "Target data directory. Default: /var/lib/jellyfin")
    private Path dataDir = Path.of(DEFAULT_DATA_DIR);

    @Option(names = {"--no-stop"},
        description = "Do not stop the Jellyfin systemd service during restore.")
    private boolean noStop;

    @Option(names = {"--no-chown"},
        description = "Do not automatically set jellyfin:jellyfin file ownership after restore.")
    private boolean noChown;

    @Option(names = {"--no-verify"},
        description = "Skip SHA-256 checksum integrity verification before restoring.")
    private boolean noVerify;

    @Option(names = {"-y", "--yes"},
        description = "Skip confirmation prompt and proceed with restore.")
    private boolean assumeYes;

    @Override
    public Integer call() throws Exception {
      if (!Files.isRegularFile(archiveFile)) {
        System.err.printf("Error: Backup archive '%s' does not exist or is not a file.%n",
            archiveFile);
        return 1;
      }

      boolean isRoot = isRunningAsRoot();
      if (!isRoot) {
        System.err.println(
            "Error: 'jellyfin-backup restore' requires root privileges to write /var/lib/jellyfin and /etc/jellyfin.");
        System.err.println("Please run: sudo ~/.jbang/bin/jellyfin-backup restore <archive-file>");
        return 1;
      }

      System.out.println("===============================================================");
      System.out.println("  Jellyfin Complete Disaster Recovery Restore");
      System.out.println("===============================================================");
      System.out.printf("Backup Archive:    %s (%s)%n", archiveFile.toAbsolutePath(),
          formatBytes(Files.size(archiveFile)));
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
      if (!noStop && isSystemdServiceActive("jellyfin")) {
        serviceWasRunning = true;
        System.out.print("Stopping 'jellyfin' service before restore... ");
        if (stopSystemdService("jellyfin")) {
          System.out.println("OK");
        } else {
          System.err.println("FAILED");
          System.err.println("Error: Could not stop jellyfin service before restore. Aborting.");
          return 1;
        }
      }

      long startTime = System.currentTimeMillis();
      try {
        unpackArchive();
      } finally {
        if (!noChown && isLinux()) {
          fixJellyfinPermissions();
        }

        if (serviceWasRunning) {
          System.out.print("Starting 'jellyfin' service... ");
          if (startSystemdService("jellyfin")) {
            System.out.println("OK");
          } else {
            System.err.println("FAILED (Please check 'systemctl status jellyfin')");
          }
        }
      }

      long durationMs = System.currentTimeMillis() - startTime;
      System.out.println("---------------------------------------------------------------");
      System.out.printf("Restore Complete in %.1fs!%n", durationMs / 1000.0);
      System.out.println(
          "Your Jellyfin server state, databases, watch history, and plugins are restored.");
      System.out.println("===============================================================");

      return 0;
    }

    private void unpackArchive() throws Exception {
      Files.createDirectories(configDir);
      Files.createDirectories(dataDir);

      try (InputStream fis = Files.newInputStream(archiveFile);
          BufferedInputStream bis = new BufferedInputStream(fis, 128 * 1024);
          GZIPInputStream gzis = new GZIPInputStream(bis);
          TarArchiveInputStream tarIn = new TarArchiveInputStream(gzis)) {

        TarArchiveEntry entry;
        int restoredFiles = 0;
        while ((entry = tarIn.getNextTarEntry()) != null) {
          String name = entry.getName();
          if (name.equals(MANIFEST_ENTRY_NAME)) {
            continue;
          }

          Path targetPath = null;
          if (name.startsWith("etc/jellyfin/")) {
            String sub = name.substring("etc/jellyfin/".length());
            targetPath = configDir.resolve(sub);
          } else if (name.startsWith("var/lib/jellyfin/")) {
            String sub = name.substring("var/lib/jellyfin/".length());
            targetPath = dataDir.resolve(sub);
          }

          if (targetPath != null) {
            if (entry.isDirectory()) {
              Files.createDirectories(targetPath);
            } else {
              if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
              }
              try (var out = Files.newOutputStream(targetPath)) {
                byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = tarIn.read(buf)) != -1) {
                  out.write(buf, 0, read);
                }
              }
              restoredFiles++;
            }
          }
        }
        System.out.printf("Restored %d files into %s and %s.%n", restoredFiles, configDir, dataDir);
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
  @Command(name = "inspect", mixinStandardHelpOptions = true,
      description = "Inspect the contents, manifest, and database metrics of a backup archive.")
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
      long totalUncompressedSize = 0;
      int totalEntries = 0;
      long dbSize = 0;

      try (InputStream fis = Files.newInputStream(archiveFile);
          BufferedInputStream bis = new BufferedInputStream(fis, 128 * 1024);
          GZIPInputStream gzis = new GZIPInputStream(bis);
          TarArchiveInputStream tarIn = new TarArchiveInputStream(gzis)) {

        TarArchiveEntry entry;
        while ((entry = tarIn.getNextTarEntry()) != null) {
          totalEntries++;
          totalUncompressedSize += entry.getSize();
          String name = entry.getName();

          if (name.equals(MANIFEST_ENTRY_NAME)) {
            manifestJson = new String(tarIn.readAllBytes(), StandardCharsets.UTF_8);
          } else if (name.contains("plugins/") && entry.isDirectory()) {
            String[] parts = name.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
              if (parts[i].equals("plugins") && !parts[i + 1].equals("configurations")) {
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

  static boolean isSystemdServiceActive(String serviceName) {
    if (!isLinux()) {
      return false;
    }
    try {
      Process process = new ProcessBuilder("systemctl", "is-active", serviceName).start();
      String out = new String(process.getInputStream().readAllBytes()).trim();
      process.waitFor();
      return "active".equalsIgnoreCase(out);
    } catch (Exception _) {
      return false;
    }
  }

  static boolean stopSystemdService(String serviceName) {
    try {
      return new ProcessBuilder("systemctl", "stop", serviceName).start().waitFor() == 0;
    } catch (Exception _) {
      return false;
    }
  }

  static boolean startSystemdService(String serviceName) {
    try {
      return new ProcessBuilder("systemctl", "start", serviceName).start().waitFor() == 0;
    } catch (Exception _) {
      return false;
    }
  }

  static String detectJellyfinVersion() {
    try {
      Process p = new ProcessBuilder("jellyfin", "--version").start();
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
