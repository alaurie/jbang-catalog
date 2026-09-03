///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms8m -Xmx64m -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData
//NATIVE_OPTIONS -O2 -march=native --no-fallback


package digest;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// CLI utility to calculate and verify cryptographic checksums (MD5, SHA-1, SHA-256, SHA-512,
/// etc.).
///
/// Supports checking files, recursive directory manifests, string inputs, benchmark mode, stdin,
/// and
/// verification files.
@Command(name = "digest", mixinStandardHelpOptions = true, version = "digest 1.0",
    description = "Compute and verify cryptographic checksums for files or text input.")
@SuppressWarnings("unused")
class Digest implements Callable<Integer> {

  @Option(names = {"-a", "--algorithm"},
      description = "Hash algorithm: MD5, SHA-1, SHA-256, SHA-512, SHA3-256, SHA3-512. Default: SHA-256.")
  private String algorithm = "SHA-256";

  @Option(names = {"-t", "--text"},
      description = "Compute hash for string text input instead of file.")
  private String textInput;

  @Option(names = {"-c", "--check"}, description = "Verify checksums from specified checksum file.")
  private Path checkFile;

  @Option(names = {"-r", "--recursive"},
      description = "Recursively compute checksum manifest for directories.")
  private boolean recursive;

  @Option(names = {"--benchmark"},
      description = "Benchmark CPU hashing throughput across algorithms (MB/s).")
  private boolean benchmark;

  @Parameters(arity = "0..*", paramLabel = "<file>",
      description = "One or more file paths or directories to hash, or '-' for stdin.")
  private List<Path> files;

  /**
   * Main entry point for the JBang script execution.
   *
   * @param args Command-line arguments.
   */
  void main(String... args) {
    var exitCode = new CommandLine(this).execute(args);
    System.exit(exitCode);
  }

  /**
   * Calculates or verifies hashes according to CLI options.
   *
   * @return Status code 0 for success, 1 on hash mismatch or errors.
   */
  @Override
  public Integer call() {
    if (benchmark) {
      runBenchmark();
      return 0;
    }

    try {
      normalizeAlgorithm();
    } catch (NoSuchAlgorithmException e) {
      System.err.println("Error: " + e.getMessage());
      return 1;
    }

    if (checkFile != null) {
      return verifyCheckFile(checkFile);
    }

    if (textInput != null) {
      return hashText(textInput);
    }

    if (files == null || files.isEmpty()) {
      if (System.console() != null) {
        CommandLine.usage(this, System.out);
        return 0;
      }
      return hashStream(System.in, "-");
    }

    var hasError = false;
    for (var file : files) {
      if (file.toString().equals("-")) {
        if (hashStream(System.in, "-") != 0) {
          hasError = true;
        }
      } else if (!Files.exists(file)) {
        System.err.printf("Error: File '%s' does not exist.%n", file);
        hasError = true;
      } else if (Files.isDirectory(file)) {
        if (recursive || files.size() == 1) {
          if (hashDirectory(file) != 0) {
            hasError = true;
          }
        } else {
          System.err.printf("Error: Path '%s' is a directory (use -r for recursive).%n", file);
          hasError = true;
        }
      } else {
        if (hashFile(file) != 0) {
          hasError = true;
        }
      }
    }

    return hasError ? 1 : 0;
  }

  /**
   * Benchmarks CPU hashing throughput across supported algorithms using a 50MB buffer.
   */
  private void runBenchmark() {
    System.out.println("Benchmarking CPU Cryptographic Throughput (50 MB Payload)...");
    System.out.println("------------------------------------------------------------");

    var algorithms = List.of("MD5", "SHA-1", "SHA-256", "SHA-512", "SHA3-256", "SHA3-512");
    var payloadSize = 50 * 1024 * 1024; // 50 MB
    var payload = new byte[1024 * 1024]; // 1 MB chunk repeated 50 times
    Arrays.fill(payload, (byte) 0x42);

    for (var algo : algorithms) {
      try {
        var md = MessageDigest.getInstance(algo);
        var start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
          md.update(payload);
        }
        var hash = md.digest();
        var durationSec = (System.nanoTime() - start) / 1_000_000_000.0;
        var mbs = (payloadSize / (1024.0 * 1024.0)) / durationSec;
        System.out.printf("%-10s : %7.2f MB/s  (Time: %.3f s)%n", algo, mbs, durationSec);
      } catch (Exception e) {
        System.out.printf("%-10s : Unsupported (%s)%n", algo, e.getMessage());
      }
    }
  }

  /**
   * Recursively walks a directory and computes hashes for all files in manifest format.
   *
   * @param dir Directory root path.
   * @return Status code 0 on success, 1 on error.
   */
  private int hashDirectory(Path dir) {
    var hasError = false;
    try (var stream = Files.walk(dir)) {
      var paths = stream.filter(Files::isRegularFile).sorted().toList();
      for (var p : paths) {
        var relPath = dir.relativize(p).toString();
        var hashStr = computeHashForPath(p);
        if (hashStr != null) {
          System.out.printf("%s  %s%n", hashStr, relPath);
        } else {
          hasError = true;
        }
      }
    } catch (Exception e) {
      System.err.printf("Error walking directory '%s': %s%n", dir, e.getMessage());
      return 1;
    }
    return hasError ? 1 : 0;
  }

  /**
   * Normalizes algorithm name to standard JCA provider string.
   *
   * @throws NoSuchAlgorithmException If algorithm is not supported.
   */
  private void normalizeAlgorithm() throws NoSuchAlgorithmException {
    var algoUpper = algorithm.toUpperCase(Locale.ROOT).replace("-", "");
    algorithm = switch (algoUpper) {
      case "MD5" -> "MD5";
      case "SHA1" -> "SHA-1";
      case "SHA256" -> "SHA-256";
      case "SHA512" -> "SHA-512";
      case "SHA3256" -> "SHA3-256";
      case "SHA3512" -> "SHA3-512";
      default -> throw new NoSuchAlgorithmException("Unsupported algorithm: " + algorithm);
    };
    MessageDigest.getInstance(algorithm);
  }

  /**
   * Hashes plain text string.
   *
   * @param input Raw text string.
   * @return Status code 0 on success, 1 on error.
   */
  private int hashText(String input) {
    try {
      var digest = MessageDigest.getInstance(algorithm);
      var hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      var hex = HexFormat.of().formatHex(hashBytes);
      System.out.printf("%s  \"%s\"%n", hex, input);
      return 0;
    } catch (Exception e) {
      System.err.printf("Error hashing string: %s%n", e.getMessage());
      return 1;
    }
  }

  /**
   * Hashes a single file on disk.
   *
   * @param path File path.
   * @return Status code 0 on success, 1 on error.
   */
  private int hashFile(Path path) {
    var hex = computeHashForPath(path);
    if (hex != null) {
      System.out.printf("%s  %s%n", hex, path);
      return 0;
    }
    return 1;
  }

  /**
   * Computes hash for path returning hex string.
   *
   * @param path Target path.
   * @return Hex string or {@code null} on error.
   */
  private String computeHashForPath(Path path) {
    try {
      long totalBytes = Files.size(path);
      var digest = MessageDigest.getInstance(algorithm);
      int bufferSize = (int) Math.min(8 * 1024 * 1024, Math.max(64 * 1024, totalBytes));
      ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);

      try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
        while (channel.read(buffer) > 0) {
          buffer.flip();
          digest.update(buffer);
          buffer.clear();
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      System.err.printf("Error hashing file '%s': %s%n", path, e.getMessage());
      return null;
    }
  }

  /**
   * Hashes stream data (e.g. stdin).
   *
   * @param is Input stream.
   * @param name Display label for stream.
   * @return Status code 0 on success, 1 on error.
   */
  private int hashStream(InputStream is, String name) {
    try {
      var digest = MessageDigest.getInstance(algorithm);
      var buffer = new byte[64 * 1024];
      int read;
      while ((read = is.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
      var hex = HexFormat.of().formatHex(digest.digest());
      System.out.printf("%s  %s%n", hex, name);
      return 0;
    } catch (Exception e) {
      System.err.printf("Error hashing stream: %s%n", e.getMessage());
      return 1;
    }
  }

  /**
   * Verifies file checksums against specified manifest file.
   *
   * @param manifest Path to checksum manifest file.
   * @return Status code 0 if all verified, 1 if mismatches or errors occur.
   */
  private int verifyCheckFile(Path manifest) {
    if (!Files.exists(manifest)) {
      System.err.printf("Error: Checksum file '%s' does not exist.%n", manifest);
      return 1;
    }

    var baseDir = manifest.getParent() != null ? manifest.getParent() : Path.of(".");
    var hasError = false;

    try (var reader = new BufferedReader(
        new InputStreamReader(Files.newInputStream(manifest), StandardCharsets.UTF_8))) {
      String line;
      var lineNumber = 0;

      while ((line = reader.readLine()) != null) {
        lineNumber++;
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }

        var parts = line.split("\\s+", 2);
        if (parts.length < 2) {
          System.err.printf("Warning: Line %d in '%s' is malformed: '%s'%n", lineNumber, manifest,
              line);
          hasError = true;
          continue;
        }

        var expectedHash = parts[0].trim();
        var targetPathStr = parts[1].trim();

        if (targetPathStr.startsWith("*") || targetPathStr.startsWith(" ")) {
          targetPathStr = targetPathStr.substring(1).trim();
        }

        var targetFile = baseDir.resolve(targetPathStr);
        if (!Files.exists(targetFile)) {
          System.out.printf("%s: FAILED (File not found)%n", targetPathStr);
          hasError = true;
          continue;
        }

        var actualHash = computeHashForPath(targetFile);
        if (actualHash == null) {
          System.out.printf("%s: FAILED (Error reading file)%n", targetPathStr);
          hasError = true;
        } else if (expectedHash.equalsIgnoreCase(actualHash)) {
          System.out.printf("%s: OK%n", targetPathStr);
        } else {
          System.out.printf("%s: FAILED (Hash mismatch)%n", targetPathStr);
          hasError = true;
        }
      }
    } catch (Exception e) {
      System.err.printf("Error reading checksum file '%s': %s%n", manifest, e.getMessage());
      return 1;
    }

    return hasError ? 1 : 0;
  }
}
