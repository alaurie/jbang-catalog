///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//DEPS tools.jackson.core:jackson-databind:3.2.1
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms4m -Xmx32m -XX:TieredStopAtLevel=1 -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData
//NATIVE_OPTIONS -O2 --no-fallback

package installnative;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/// Cross-platform utility to compile and export catalog applications as standalone GraalVM native
/// binaries.
///
/// Dynamically inspects catalog manifests and source script directives (`//NATIVE_OPTIONS`,
/// dependencies)
/// to determine native compatibility and exports binaries directly into `~/.jbang/bin` or a
/// specified directory.
@Command(name = "install-native", mixinStandardHelpOptions = true, version = "install-native 1.1",
    description = "Compile and export catalog tools as standalone zero-overhead native executables.")
@SuppressWarnings("unused")
class InstallNative implements Callable<Integer> {

  private static final boolean IS_WINDOWS =
      System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

  private static final String REMOTE_CATALOG_URL =
      "https://raw.githubusercontent.com/alaurie/jbang-catalog/main/jbang-catalog.json";

  private static final Set<String> JNI_DEPENDENCY_PATTERNS =
      Set.of("oshi-core", "jna", "sqlite-jdbc", "libjnidispatch");

  record AppMetadata(String alias, String scriptRef, String description, boolean nativeSupported,
      String supportReason) {}

  @Parameters(arity = "0..*", paramLabel = "<apps>",
      description = "Specific application aliases to export (e.g. fetch hash jwt). Defaults to all native-supported apps.")
  private List<String> requestedApps = new ArrayList<>();

  @Option(names = {"-d", "--dir"},
      description = "Target destination directory for native binaries. Default: ~/.jbang/bin")
  private Path targetDir;

  @Option(names = {"-l", "--list"},
      description = "List all available catalog applications and dynamic native compatibility.")
  private boolean listOnly;

  @Option(names = {"-f", "--force"},
      description = "Overwrite existing binaries in the target directory.")
  private boolean force = true;

  @Option(names = {"-v", "--verbose"},
      description = "Enable verbose output during native-image compilation.")
  private boolean verbose;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public Integer call() throws Exception {
    Map<String, AppMetadata> catalogApps = discoverCatalogApps();
    if (catalogApps.isEmpty()) {
      System.err.println("Error: Failed to discover applications from jbang-catalog.json.");
      return 1;
    }

    if (listOnly) {
      printCatalogList(catalogApps);
      return 0;
    }

    Path destination = resolveDestination();
    if (!Files.exists(destination)) {
      Files.createDirectories(destination);
      System.out.printf("Created destination directory: %s%n", destination.toAbsolutePath());
    }

    List<AppMetadata> targets = selectTargets(catalogApps);
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

  /// Discovers catalog aliases from local `jbang-catalog.json` or remote GitHub repository,
  /// then inspects script source code to evaluate dynamic native compatibility.
  private Map<String, AppMetadata> discoverCatalogApps() {
    Map<String, AppMetadata> apps = new LinkedHashMap<>();
    String jsonContent = loadCatalogJson();
    if (jsonContent == null) {
      return apps;
    }

    try {
      JsonNode root = objectMapper.readTree(jsonContent);
      JsonNode aliases = root.get("aliases");
      if (aliases != null && aliases.isObject()) {
        aliases.properties().forEach(entry -> {
          String alias = entry.getKey();
          JsonNode node = entry.getValue();
          String scriptRef = node.has("script-ref") ? node.get("script-ref").asText() : "";
          String description = node.has("description") ? node.get("description").asText() : "";

          // Don't export install-native into itself
          if (!"install-native".equalsIgnoreCase(alias)) {
            var compatibility = evaluateNativeCompatibility(alias, scriptRef);
            apps.put(alias.toLowerCase(Locale.ROOT), new AppMetadata(alias, scriptRef, description,
                compatibility.supported(), compatibility.reason()));
          }
        });
      }
    } catch (Exception e) {
      if (verbose) {
        System.err.println("Failed to parse catalog JSON: " + e.getMessage());
      }
    }
    return apps;
  }

  private String loadCatalogJson() {
    // 1. Try local catalog file in current working directory
    Path localCatalog = Path.of("jbang-catalog.json");
    if (Files.isRegularFile(localCatalog)) {
      try {
        return Files.readString(localCatalog);
      } catch (Exception _) {
        // Fallback to remote
      }
    }

    // 2. Fetch from remote GitHub catalog
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(REMOTE_CATALOG_URL)).GET().build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return response.body();
      }
    } catch (Exception e) {
      if (verbose) {
        System.err.println("Could not fetch remote catalog: " + e.getMessage());
      }
    }
    return null;
  }

  record CompatibilityResult(boolean supported, String reason) {}

  /// Evaluates native compilation support by inspecting script directives and dependencies.
  private CompatibilityResult evaluateNativeCompatibility(String alias, String scriptRef) {
    String source = loadScriptSource(alias, scriptRef);
    if (source == null) {
      // Default to supported if source cannot be inspected ahead of time
      return new CompatibilityResult(true, "Supported");
    }

    // Check for explicit native exclusion directive
    if (source.contains("//NATIVE_DISABLED") || source.contains("//NO_NATIVE")) {
      return new CompatibilityResult(false, "Disabled via //NATIVE_DISABLED directive");
    }

    // Check for known JNI / dynamic C-library dependencies
    for (String jniPattern : JNI_DEPENDENCY_PATTERNS) {
      if (source.toLowerCase(Locale.ROOT).contains(jniPattern)) {
        return new CompatibilityResult(false,
            "Requires JVM for dynamic JNA / C-bindings (%s)".formatted(jniPattern));
      }
    }

    // Check for native options directive
    if (source.contains("//NATIVE_OPTIONS")) {
      return new CompatibilityResult(true, "Supported (AOT Configured)");
    }

    return new CompatibilityResult(true, "Supported");
  }

  private String loadScriptSource(String alias, String scriptRef) {
    // Check local filesystem
    if (scriptRef != null && !scriptRef.isBlank()) {
      Path localFile = Path.of(scriptRef);
      if (Files.isRegularFile(localFile)) {
        try {
          return Files.readString(localFile);
        } catch (Exception _) {
        }
      }
    }

    // Check remote raw GitHub URL
    if (scriptRef != null && !scriptRef.isBlank()) {
      try {
        String remoteUrl =
            "https://raw.githubusercontent.com/alaurie/jbang-catalog/main/" + scriptRef;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(remoteUrl)).GET().build();
        HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 200) {
          return res.body();
        }
      } catch (Exception _) {
      }
    }
    return null;
  }

  private Path resolveDestination() {
    if (targetDir != null) {
      return targetDir;
    }
    String userHome = System.getProperty("user.home");
    return Path.of(userHome, ".jbang", "bin");
  }

  private List<AppMetadata> selectTargets(Map<String, AppMetadata> catalogApps) {
    if (requestedApps == null || requestedApps.isEmpty()) {
      return catalogApps.values().stream().filter(AppMetadata::nativeSupported).toList();
    }

    List<AppMetadata> result = new ArrayList<>();
    for (String req : requestedApps) {
      String key = req.trim().toLowerCase(Locale.ROOT);
      var app = catalogApps.get(key);
      if (app == null) {
        System.err.printf("Warning: Unknown application '%s' (use --list to see available tools)%n",
            req);
      } else if (!app.nativeSupported()) {
        System.err.printf("Warning: '%s' is marked JVM-only (%s) - skipping native build.%n",
            app.alias(), app.supportReason());
      } else {
        result.add(app);
      }
    }
    return result;
  }

  private boolean exportNativeBinary(AppMetadata app, Path outputPath) {
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

  private void printCatalogList(Map<String, AppMetadata> catalogApps) {
    System.out.println("Available applications in jbang-catalog:");
    System.out.println();
    System.out.printf("  %-12s %-26s %s%n", "ALIAS", "NATIVE BUILD", "DESCRIPTION");
    System.out.println("  " + "-".repeat(80));
    for (var app : catalogApps.values()) {
      String status = app.nativeSupported() ? "Supported" : "JVM Only (JNA)";
      System.out.printf("  %-12s %-26s %s%n", app.alias(), status, app.description());
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
