///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms4m -Xmx32m -XX:TieredStopAtLevel=1 -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData
//NATIVE_OPTIONS -O2 --no-fallback

package installnative;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// Cross-platform utility to compile and export catalog applications as standalone GraalVM native
/// binaries.
///
/// Exports native ELF / Mach-O / PE machine executables directly to `~/.jbang/bin` or a specified
/// directory, bypassing shell script wrapper overhead for instant sub-10ms CLI startup.
@Command(name = "install-native", mixinStandardHelpOptions = true, version = "install-native 1.0",
    description = "Compile and export catalog tools as standalone zero-overhead native executables.")
@SuppressWarnings("unused")
class InstallNative implements Callable<Integer> {

  private static final boolean IS_WINDOWS =
      System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

  /// Registry of catalog apps and their native image suitability.
  private static final Map<String, AppMetadata> APPS = new LinkedHashMap<>();

  record AppMetadata(String alias, String scriptRef, String description, boolean nativeSupported) {}

  static {
    APPS.put("fetch", new AppMetadata("fetch", "apps/fetch/Fetch.java",
        "Multithreaded download utility with automatic checksum capability", true));
    APPS.put("hash", new AppMetadata("hash", "apps/hash/Hash.java",
        "Compute and verify cryptographic checksums for files or text input", true));
    APPS.put("jwt", new AppMetadata("jwt", "apps/jwt/Jwt.java",
        "Inspect and decode JSON Web Tokens (JWT) safely off-line", true));
    APPS.put("killport", new AppMetadata("killport", "apps/killport/Killport.java",
        "Find and terminate processes listening on specified network ports", true));
    APPS.put("nudge", new AppMetadata("nudge", "apps/nudge/Nudge.java",
        "Simulates user activity to keep presence status active", true));
    APPS.put("reach", new AppMetadata("reach", "apps/reach/Reach.java",
        "Network diagnostic utility to test TCP reachability and inspect TLS certs", true));
    APPS.put("serve", new AppMetadata("serve", "apps/serve/Serve.java",
        "Serves the given directory on the specified port", true));
    APPS.put("typeit", new AppMetadata("typeit", "apps/typeit/Typeit.java",
        "Simulates typing clipboard text into active window after countdown", true));
    APPS.put("slowfetch", new AppMetadata("slowfetch", "apps/slowfetch/Slowfetch.java",
        "Terminal system information fetcher powered by OSHI (Requires JVM for JNA)", false));
  }

  @Parameters(arity = "0..*", paramLabel = "<apps>",
      description = "Specific application aliases to export (e.g. fetch hash jwt). Defaults to all native-supported apps.")
  private List<String> requestedApps = new ArrayList<>();

  @Option(names = {"-d", "--dir"},
      description = "Target destination directory for native binaries. Default: ~/.jbang/bin")
  private Path targetDir;

  @Option(names = {"-l", "--list"},
      description = "List all available catalog applications and native compatibility.")
  private boolean listOnly;

  @Option(names = {"-f", "--force"},
      description = "Overwrite existing binaries in the target directory.")
  private boolean force = true;

  @Option(names = {"-v", "--verbose"},
      description = "Enable verbose output during native-image compilation.")
  private boolean verbose;

  @Override
  public Integer call() throws Exception {
    if (listOnly) {
      printCatalogList();
      return 0;
    }

    Path destination = resolveDestination();
    if (!Files.exists(destination)) {
      Files.createDirectories(destination);
      System.out.printf("Created destination directory: %s%n", destination.toAbsolutePath());
    }

    List<AppMetadata> targets = selectTargets();
    if (targets.isEmpty()) {
      System.err.println("No matching native-supported applications selected.");
      return 1;
    }

    System.out.println("===============================================================");
    System.out.println("  jbang-catalog native exporter");
    System.out.println("===============================================================");
    System.out.printf("Destination: %s%n", destination.toAbsolutePath());
    System.out.printf("Applications to export (%d): %s%n%n", targets.size(),
        String.join(", ", targets.stream().map(AppMetadata::alias).toList()));

    int successful = 0;
    int failed = 0;

    for (int i = 0; i < targets.size(); i++) {
      var app = targets.get(i);
      String binaryName = IS_WINDOWS ? app.alias() + ".exe" : app.alias();
      Path outputPath = destination.resolve(binaryName);

      System.out.printf("[%d/%d] Compiling and exporting '%s' -> %s...%n", i + 1, targets.size(),
          app.alias(), outputPath.getFileName());

      long startTime = System.currentTimeMillis();
      boolean ok = exportNativeBinary(app, outputPath);
      long elapsed = System.currentTimeMillis() - startTime;

      if (ok) {
        successful++;
        System.out.printf("      SUCCESS in %.1fs (%s)%n%n", elapsed / 1000.0,
            formatFileSize(outputPath));
      } else {
        failed++;
        System.err.printf("      FAILED after %.1fs%n%n", elapsed / 1000.0);
      }
    }

    System.out.println("---------------------------------------------------------------");
    System.out.printf("Export complete: %d succeeded, %d failed.%n", successful, failed);
    if (successful > 0) {
      System.out.printf("Native binaries ready in: %s%n", destination.toAbsolutePath());
      checkPathEnvironment(destination);
    }

    return failed == 0 ? 0 : 1;
  }

  private Path resolveDestination() {
    if (targetDir != null) {
      return targetDir;
    }
    String userHome = System.getProperty("user.home");
    return Path.of(userHome, ".jbang", "bin");
  }

  private List<AppMetadata> selectTargets() {
    if (requestedApps == null || requestedApps.isEmpty()) {
      // All native-supported apps
      return APPS.values().stream().filter(AppMetadata::nativeSupported).toList();
    }

    List<AppMetadata> result = new ArrayList<>();
    for (String req : requestedApps) {
      String key = req.trim().toLowerCase(Locale.ROOT);
      var app = APPS.get(key);
      if (app == null) {
        System.err.printf("Warning: Unknown application '%s' (use --list to see available tools)%n",
            req);
      } else if (!app.nativeSupported()) {
        System.err.printf("Warning: '%s' is marked JVM-only (%s) - skipping native build.%n",
            app.alias(), app.description());
      } else {
        result.add(app);
      }
    }
    return result;
  }

  private boolean exportNativeBinary(AppMetadata app, Path outputPath) {
    // Resolve script source: prefer local repo file if present, else fallback to alias@alaurie
    String scriptSource = app.scriptRef();
    if (!Files.exists(Path.of(scriptSource))) {
      scriptSource = app.alias() + "@alaurie";
    }

    List<String> command = new ArrayList<>();
    command.add("jbang");
    command.add("export");
    command.add("native");
    command.add(scriptSource);
    command.add("-O");
    command.add(outputPath.toAbsolutePath().toString());
    if (force) {
      command.add("--force");
    }

    try {
      var processBuilder = new ProcessBuilder(command);
      processBuilder.redirectErrorStream(true);

      var process = processBuilder.start();
      try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (verbose || line.startsWith("[jbang] Building") || line.contains("Error")) {
            System.out.println("      " + line);
          }
        }
      }

      int exitCode = process.waitFor();
      if (exitCode == 0 && Files.exists(outputPath)) {
        // Ensure executable permissions on POSIX
        if (!IS_WINDOWS) {
          outputPath.toFile().setExecutable(true, false);
        }
        return true;
      }
      return false;
    } catch (Exception e) {
      System.err.println("      Error executing jbang export: " + e.getMessage());
      return false;
    }
  }

  private void printCatalogList() {
    System.out.println("Available applications in jbang-catalog:");
    System.out.println();
    System.out.printf("  %-12s %-16s %s%n", "ALIAS", "NATIVE BUILD", "DESCRIPTION");
    System.out.println("  " + "-".repeat(70));
    for (var app : APPS.values()) {
      String status = app.nativeSupported() ? "Supported" : "JVM Only (JNA)";
      System.out.printf("  %-12s %-16s %s%n", app.alias(), status, app.description());
    }
    System.out.println();
    System.out.println("Usage:");
    System.out.println("  jbang install-native@alaurie              # Install all native apps");
    System.out.println("  jbang install-native@alaurie fetch hash   # Install specific apps");
  }

  private void checkPathEnvironment(Path destination) {
    String pathEnv = System.getenv("PATH");
    if (pathEnv != null) {
      String destStr = destination.toAbsolutePath().toString();
      boolean onPath = List.of(pathEnv.split(File.pathSeparator)).stream()
          .map(p -> Path.of(p).toAbsolutePath().toString())
          .anyMatch(p -> p.equalsIgnoreCase(destStr));

      if (!onPath) {
        System.out.println();
        System.out.printf("Note: '%s' is not in your $PATH.%n", destStr);
        System.out.printf("Add it to your shell config via:%n  export PATH=\"%s:$PATH\"%n",
            destStr);
      }
    }
  }

  private String formatFileSize(Path path) {
    try {
      long bytes = Files.size(path);
      if (bytes < 1024)
        return bytes + " B";
      if (bytes < 1024 * 1024)
        return "%.1f KB".formatted(bytes / 1024.0);
      return "%.1f MB".formatted(bytes / (1024.0 * 1024.0));
    } catch (Exception _) {
      return "unknown size";
    }
  }

  void main(String... args) {
    int exitCode = new CommandLine(this).execute(args);
    System.exit(exitCode);
  }
}
