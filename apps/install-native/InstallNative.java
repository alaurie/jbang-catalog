///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms4m -Xmx32m -XX:TieredStopAtLevel=1 -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData
//NATIVE_OPTIONS -O2 -march=native --no-fallback

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

/// Cross-platform utility to compile, export, and manage standalone GraalVM native binaries.
///
/// Supports compiling/exporting native binaries directly to `~/.jbang/bin` (or custom directory),
/// listing native compatibility, and cleaning/uninstalling exported binaries.
@Command(name = "install-native", mixinStandardHelpOptions = true, version = "install-native 1.2",
    description = "Compile, export, and manage standalone zero-overhead native executables.")
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
      description = "Specific application aliases to export or clean (e.g. fetch digest jwt). Defaults to all native-supported apps.")
  private List<String> requestedApps = new ArrayList<>();

  @Option(names = {"-d", "--dir"},
      description = "Target destination directory for native binaries. Default: ~/.jbang/bin")
  private Path targetDir;

  @Option(names = {"-l", "--list"},
      description = "List all available catalog applications and dynamic native compatibility.")
  private boolean listOnly;

  @Option(names = {"-c", "--clean", "--uninstall"},
      description = "Remove exported native binaries from the target destination directory.")
  private boolean cleanOnly;

  @Option(names = {"-f", "--force"},
      description = "Overwrite existing binaries in the target directory.")
  private boolean force;

  @Option(names = {"-v", "--verbose"},
      description = "Enable verbose output during native-image compilation.")
  private boolean verbose;


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

    if (cleanOnly) {
      return cleanNativeBinaries(catalogApps, destination);
    }

    if (!Files.exists(destination)) {
      Files.createDirectories(destination);
      System.out.printf("Created destination directory: %s%n", destination.toAbsolutePath());
    }

    List<AppMetadata> targets = selectTargets(catalogApps, false);
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

  private int cleanNativeBinaries(Map<String, AppMetadata> catalogApps, Path destination) {
    if (!Files.isDirectory(destination)) {
      System.out.printf("Directory '%s' does not exist. Nothing to clean.%n",
          destination.toAbsolutePath());
      return 0;
    }

    List<AppMetadata> targets = selectTargets(catalogApps, true);
    if (targets.isEmpty()) {
      System.err.println("No applications selected for cleaning.");
      return 1;
    }

    System.out.println("===============================================================");
    System.out.println("  jbang-catalog native cleaner");
    System.out.println("===============================================================");
    System.out.printf("Target Directory: %s%n", destination.toAbsolutePath());
    System.out.printf("Applications to remove (%d): %s%n%n", targets.size(),
        String.join(", ", targets.stream().map(AppMetadata::alias).toList()));

    int removed = 0;
    int missing = 0;

    for (var app : targets) {
      String binaryName = IS_WINDOWS ? app.alias() + ".exe" : app.alias();
      Path binaryPath = destination.resolve(binaryName);

      if (Files.exists(binaryPath)) {
        try {
          Files.delete(binaryPath);
          removed++;
          System.out.printf("  ✓ Removed: %s%n", binaryPath.getFileName());
        } catch (Exception e) {
          System.err.printf("  ✗ Error removing %s: %s%n", binaryPath.getFileName(),
              e.getMessage());
        }
      } else {
        missing++;
        if (verbose) {
          System.out.printf("  - Not found: %s%n", binaryPath.getFileName());
        }
      }
    }

    System.out.println("---------------------------------------------------------------");
    System.out.printf("Clean complete: %d removed, %d not found.%n", removed, missing);
    return 0;
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
      Object parsed = parseJson(jsonContent);
      if (parsed instanceof Map<?, ?> root) {
        Object aliasesObj = root.get("aliases");
        if (aliasesObj instanceof Map<?, ?> aliases) {
          for (var entry : aliases.entrySet()) {
            String alias = String.valueOf(entry.getKey());
            if (entry.getValue() instanceof Map<?, ?> node) {
              String scriptRef =
                  node.containsKey("script-ref") ? String.valueOf(node.get("script-ref")) : "";
              String description =
                  node.containsKey("description") ? String.valueOf(node.get("description")) : "";

              // Don't export install-native into itself
              if (!"install-native".equalsIgnoreCase(alias)) {
                var compatibility = evaluateNativeCompatibility(alias, scriptRef);
                apps.put(alias.toLowerCase(Locale.ROOT), new AppMetadata(alias, scriptRef,
                    description, compatibility.supported(), compatibility.reason()));
              }
            }
          }
        }
      }
    } catch (Exception e) {
      if (verbose) {
        System.err.println("Failed to parse catalog JSON: " + e.getMessage());
      }
    }
    return apps;
  }

  private static Object parseJson(String json) {
    return new JsonParser(json.trim()).parse();
  }

  private static class JsonParser {
    private final String src;
    private int pos;

    JsonParser(String src) {
      this.src = src;
    }

    Object parse() {
      skipWhitespace();
      Object val = parseValue();
      skipWhitespace();
      return val;
    }

    private Object parseValue() {
      skipWhitespace();
      if (pos >= src.length())
        return null;
      char c = src.charAt(pos);
      if (c == '{')
        return parseObject();
      if (c == '[')
        return parseArray();
      if (c == '"')
        return parseString();
      if (c == 't' || c == 'f')
        return parseBoolean();
      if (c == 'n')
        return parseNull();
      if (c == '-' || (c >= '0' && c <= '9'))
        return parseNumber();
      throw new IllegalArgumentException("Unexpected char: " + c);
    }

    private Map<String, Object> parseObject() {
      Map<String, Object> map = new LinkedHashMap<>();
      pos++;
      skipWhitespace();
      if (pos < src.length() && src.charAt(pos) == '}') {
        pos++;
        return map;
      }
      while (pos < src.length()) {
        skipWhitespace();
        String key = parseString();
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == ':') {
          pos++;
        }
        Object val = parseValue();
        map.put(key, val);
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == ',') {
          pos++;
        } else if (pos < src.length() && src.charAt(pos) == '}') {
          pos++;
          break;
        }
      }
      return map;
    }

    private List<Object> parseArray() {
      List<Object> list = new ArrayList<>();
      pos++;
      skipWhitespace();
      if (pos < src.length() && src.charAt(pos) == ']') {
        pos++;
        return list;
      }
      while (pos < src.length()) {
        list.add(parseValue());
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == ',') {
          pos++;
        } else if (pos < src.length() && src.charAt(pos) == ']') {
          pos++;
          break;
        }
      }
      return list;
    }

    private String parseString() {
      pos++;
      var sb = new StringBuilder();
      while (pos < src.length()) {
        char c = src.charAt(pos++);
        if (c == '"') {
          return sb.toString();
        }
        if (c == '\\' && pos < src.length()) {
          char esc = src.charAt(pos++);
          switch (esc) {
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case '/' -> sb.append('/');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'u' -> {
              if (pos + 4 <= src.length()) {
                sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                pos += 4;
              }
            }
            default -> sb.append(esc);
          }
        } else {
          sb.append(c);
        }
      }
      return sb.toString();
    }

    private Boolean parseBoolean() {
      if (src.startsWith("true", pos)) {
        pos += 4;
        return Boolean.TRUE;
      }
      if (src.startsWith("false", pos)) {
        pos += 5;
        return Boolean.FALSE;
      }
      throw new IllegalArgumentException("Invalid boolean");
    }

    private Object parseNull() {
      if (src.startsWith("null", pos)) {
        pos += 4;
        return null;
      }
      throw new IllegalArgumentException("Invalid null");
    }

    private Number parseNumber() {
      int start = pos;
      if (src.charAt(pos) == '-')
        pos++;
      while (pos < src.length()
          && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.' || src.charAt(pos) == 'e'
              || src.charAt(pos) == 'E' || src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
        pos++;
      }
      String numStr = src.substring(start, pos);
      if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
        return Double.parseDouble(numStr);
      }
      return Long.parseLong(numStr);
    }

    private void skipWhitespace() {
      while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
        pos++;
      }
    }
  }

  private String loadCatalogJson() {
    Path localCatalog = Path.of("jbang-catalog.json");
    if (Files.isRegularFile(localCatalog)) {
      try {
        return Files.readString(localCatalog);
      } catch (Exception _) {
      }
    }

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

  private CompatibilityResult evaluateNativeCompatibility(String alias, String scriptRef) {
    String source = loadScriptSource(alias, scriptRef);
    if (source == null) {
      return new CompatibilityResult(true, "Supported");
    }

    if (source.contains("//NATIVE_DISABLED") || source.contains("//NO_NATIVE")) {
      return new CompatibilityResult(false, "Disabled via //NATIVE_DISABLED directive");
    }

    for (String jniPattern : JNI_DEPENDENCY_PATTERNS) {
      if (source.toLowerCase(Locale.ROOT).contains(jniPattern)) {
        return new CompatibilityResult(false,
            "Requires JVM for dynamic JNA / C-bindings (%s)".formatted(jniPattern));
      }
    }

    if (source.contains("//NATIVE_OPTIONS")) {
      return new CompatibilityResult(true, "Supported (AOT Configured)");
    }

    return new CompatibilityResult(true, "Supported");
  }

  private String loadScriptSource(String alias, String scriptRef) {
    if (scriptRef != null && !scriptRef.isBlank()) {
      Path localFile = Path.of(scriptRef);
      if (Files.isRegularFile(localFile)) {
        try {
          return Files.readString(localFile);
        } catch (Exception _) {
        }
      }
    }

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

  private List<AppMetadata> selectTargets(Map<String, AppMetadata> catalogApps,
      boolean includeAllForClean) {
    if (requestedApps == null || requestedApps.isEmpty()) {
      if (includeAllForClean) {
        return new ArrayList<>(catalogApps.values());
      }
      return catalogApps.values().stream().filter(AppMetadata::nativeSupported).toList();
    }

    List<AppMetadata> result = new ArrayList<>();
    for (String req : requestedApps) {
      String key = req.trim().toLowerCase(Locale.ROOT);
      var app = catalogApps.get(key);
      if (app == null) {
        System.err.printf("Warning: Unknown application '%s' (use --list to see available tools)%n",
            req);
      } else if (!includeAllForClean && !app.nativeSupported()) {
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
    System.out.println("  jbang install-native@alaurie fetch digest   # Install specific apps");
    System.out
        .println("  jbang install-native@alaurie --clean      # Remove installed native binaries");
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
