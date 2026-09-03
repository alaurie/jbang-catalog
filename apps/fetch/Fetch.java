///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms16m -Xmx64m -XX:TieredStopAtLevel=1
//NATIVE_OPTIONS -O2 --no-fallback

package fetch;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// High-performance multi-threaded CLI file downloader with auto-checksum verification.
///
/// Supports concurrent chunked range requests and automatic remote manifest probing.
@Command(name = "fetch", mixinStandardHelpOptions = true, version = "fetch 0.1",
    description = "High-performance multi-threaded CLI file downloader with auto-checksum verification")
@SuppressWarnings("unused")
class Fetch implements Callable<Integer> {

  @Parameters(index = "0", description = "Target URL to download")
  private URI uri;

  @Option(names = {"-o", "--output"}, description = "Target file output path")
  private Path outputPath;

  @Option(names = {"-c", "--connections"}, defaultValue = "4",
      description = "Concurrent chunk download connections")
  private int connections;

  @Option(names = {"--no-checksum"},
      description = "Skip automatic checksum probing and verification")
  private boolean skipChecksum;

  @Option(names = {"--expected-hash"},
      description = "Explicitly verify against this hash (auto-detects algorithm by length). Bypasses server probe.")
  private String explicitHash;

  private static final List<String> CANDIDATE_MANIFESTS =
      List.of("SHA512SUMS", "SHA256SUMS", "SHA512", "SHA256", "MD5SUMS", "MD5", "CHECKSUMS",
          "CHECKSUM", "sha512sums.txt", "sha256sums.txt", "sha512sum.txt", "sha256sum.txt");

  private final HttpClient client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();

  static void main(String... args) {
    int exitCode = new CommandLine(new Fetch()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws Exception {
    String pathStr = uri.getPath();
    String defaultFileName =
        (pathStr == null || pathStr.isBlank() || pathStr.endsWith("/")) ? "downloaded_file"
            : Path.of(pathStr).getFileName().toString();

    if (outputPath == null) {
      outputPath = Path.of(defaultFileName);
    } else if (Files.isDirectory(outputPath) || outputPath.toString().endsWith("/")
        || outputPath.toString().endsWith("\\")) {
      outputPath = outputPath.resolve(defaultFileName);
    }
    if (outputPath.getParent() != null) {
      Files.createDirectories(outputPath.getParent());
    }

    String localFilename = outputPath.getFileName().toString();
    String remoteFilename = defaultFileName;
    ExpectedHash expectedHash = null;
    if (explicitHash != null && !explicitHash.isBlank()) {
      String rawHash = explicitHash.trim();
      if (rawHash.contains(":")) {
        rawHash = rawHash.substring(rawHash.indexOf(':') + 1).trim();
      }
      String algo = switch (rawHash.length()) {
        case 32 -> "MD5";
        case 40 -> "SHA-1";
        case 128 -> "SHA-512";
        default -> "SHA-256";
      };
      expectedHash = new ExpectedHash(algo, rawHash, "user-provided");
    } else if (!skipChecksum) {
      expectedHash = findExpectedHash(remoteFilename, localFilename);
    }

    if (expectedHash != null && Files.isRegularFile(outputPath)) {
      System.out.printf("Found manifest: %s (Algorithm: %s)%n", expectedHash.candidate(),
          expectedHash.algorithm());
      System.out.print("Local file exists. Verifying checksum... ");
      String actualHash = computeFileHash(outputPath, expectedHash.algorithm());
      if (expectedHash.hash().equalsIgnoreCase(actualHash)) {
        System.out.println("OK");
        System.out.println("File already downloaded and verified. Skipping download.");
        return 0;
      } else {
        System.out.println("FAILED (Hash mismatch). Re-downloading...");
      }
    }

    HttpRequest headReq =
        HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
    HttpResponse<Void> headRes = client.send(headReq, HttpResponse.BodyHandlers.discarding());

    int headStatus = headRes.statusCode();
    if (headStatus >= 400) {
      System.err.println("Error: Server returned HTTP " + headStatus + " for " + uri);
      return 1;
    }

    long contentLength = headRes.headers().firstValueAsLong("content-length").orElse(-1L);
    boolean acceptsRanges = headRes.headers().firstValue("accept-ranges")
        .map(v -> v.equalsIgnoreCase("bytes")).orElse(false);

    if (contentLength <= 0 || !acceptsRanges || connections <= 1) {
      downloadSingleStream();
    } else {
      downloadMultiThreaded(contentLength);
    }

    System.out.println("Saved: " + outputPath.toAbsolutePath());

    if (expectedHash != null || !skipChecksum) {
      boolean verified = verifyAutoChecksum(expectedHash);
      if (!verified) {
        return 1;
      }
    }

    return 0;
  }

  private record ExpectedHash(String algorithm, String hash, String candidate) {}

  private ExpectedHash findExpectedHash(String remoteFilename, String localFilename) {
    URI baseUri = uri.resolve("./");

    List<String> candidates = new ArrayList<>(CANDIDATE_MANIFESTS);
    candidates.add(remoteFilename + ".sha256");
    candidates.add(remoteFilename + ".sha512");
    if (!localFilename.equals(remoteFilename)) {
      candidates.add(localFilename + ".sha256");
      candidates.add(localFilename + ".sha512");
    }

    for (String candidate : candidates) {
      URI manifestUri = baseUri.resolve(candidate);
      HttpRequest req = HttpRequest.newBuilder(manifestUri).GET().build();

      try {
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 200) {
          String algorithm = determineAlgorithm(candidate);
          String expectedHash = extractHash(res.body(), remoteFilename);
          if (expectedHash == null && !localFilename.equals(remoteFilename)) {
            expectedHash = extractHash(res.body(), localFilename);
          }

          if (expectedHash != null) {
            return new ExpectedHash(algorithm, expectedHash, candidate);
          }
        }
      } catch (Exception _) {
        // Continue scanning candidates if request or parsing fails
      }
    }
    return null;
  }

  private boolean verifyAutoChecksum(ExpectedHash expectedHash) {
    if (expectedHash == null) {
      System.out.println("No matching checksum manifest detected on remote server.");
      return true;
    }

    System.out.printf("Found manifest: %s (Algorithm: %s)%n", expectedHash.candidate(),
        expectedHash.algorithm());
    System.out.print("Verifying checksum... ");

    try {
      String actualHash = computeFileHash(outputPath, expectedHash.algorithm());
      if (expectedHash.hash().equalsIgnoreCase(actualHash)) {
        System.out.println("OK");
        System.out.println("Hash: " + actualHash);
        return true;
      } else {
        System.out.println("FAILED");
        System.err.println("Expected: " + expectedHash.hash());
        System.err.println("Actual:   " + actualHash);
        return false;
      }
    } catch (Exception e) {
      System.out.println("FAILED (Error reading file)");
      return false;
    }
  }

  private void downloadSingleStream() throws Exception {
    HttpRequest req = HttpRequest.newBuilder(uri).GET().build();
    HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());

    long total = res.headers().firstValueAsLong("content-length").orElse(-1L);
    try (ProgressBar pb = createProgressBar(total);
        InputStream in = res.body();
        FileChannel out = FileChannel.open(outputPath, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

      byte[] buf = new byte[64 * 1024];
      int read;
      while ((read = in.read(buf)) != -1) {
        out.write(ByteBuffer.wrap(buf, 0, read));
        pb.stepBy(read);
      }
    }
  }

  private void downloadMultiThreaded(long totalSize) throws Exception {
    long chunkSize = (long) Math.ceil((double) totalSize / connections);

    try (
        FileChannel fileChannel = FileChannel.open(outputPath, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.READ);
        ProgressBar pb = createProgressBar(totalSize);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

      fileChannel.truncate(totalSize);
      List<CompletableFuture<Void>> futures = new ArrayList<>();

      for (int i = 0; i < connections; i++) {
        long start = i * chunkSize;
        long end = Math.min(start + chunkSize - 1, totalSize - 1);
        if (start > end)
          break;

        futures.add(CompletableFuture.runAsync(() -> {
          try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Range", "bytes=" + start + "-" + end).GET().build();

            HttpResponse<InputStream> response =
                client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
              byte[] buf = new byte[64 * 1024];
              int bytesRead;
              long currentOffset = start;
              while ((bytesRead = in.read(buf)) != -1) {
                fileChannel.write(ByteBuffer.wrap(buf, 0, bytesRead), currentOffset);
                currentOffset += bytesRead;
                pb.stepBy(bytesRead);
              }
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, executor));
      }

      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
  }


  private String determineAlgorithm(String manifestName) {
    String lower = manifestName.toLowerCase();
    if (lower.contains("sha512") || lower.contains("sha-512")) {
      return "SHA-512";
    }
    if (lower.contains("sha384") || lower.contains("sha-384")) {
      return "SHA-384";
    }
    if (lower.contains("sha1") || lower.contains("sha-1")) {
      return "SHA-1";
    }
    if (lower.contains("md5")) {
      return "MD5";
    }
    return "SHA-256";
  }

  private String extractHash(String manifestBody, String filename) {
    for (String line : manifestBody.lines().map(String::trim).toList()) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      // Format 1: BSD style -> "SHA256 (filename) = hash" or "MD5(filename)= hash"
      if (line.contains("(") && line.contains(")") && line.contains("=")) {
        int openParen = line.indexOf('(');
        int closeParen = line.lastIndexOf(')');
        int equals = line.lastIndexOf('=');
        if (openParen < closeParen && closeParen < equals) {
          String target = line.substring(openParen + 1, closeParen).trim();
          // Strip potential directory prefixes in the manifest target path
          if (target.endsWith("/" + filename) || target.equals(filename)) {
            return line.substring(equals + 1).trim();
          }
        }
      }

      // Format 2: GNU/coreutils style -> "<hash> [* ]<filename>" or "<hash>  <path/to/filename>"
      String[] tokens = line.split("\\s+");
      if (tokens.length >= 2) {
        String hashToken = tokens[0];
        String pathToken = line.substring(hashToken.length()).trim();
        if (pathToken.startsWith("*")) {
          pathToken = pathToken.substring(1).trim();
        }
        if (pathToken.equals(filename) || pathToken.endsWith("/" + filename)) {
          return hashToken;
        }
      } else if (tokens.length == 1 && isValidHexHash(tokens[0])) {
        // Single hash in file (e.g. filename.sha256 containing just the hash)
        return tokens[0];
      }
    }
    return null;
  }

  private static boolean isValidHexHash(String s) {
    int len = s.length();
    if (len != 32 && len != 40 && len != 64 && len != 96 && len != 128) {
      return false;
    }
    for (int i = 0; i < len; i++) {
      char c = s.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
        return false;
      }
    }
    return true;
  }

  private String computeFileHash(Path file, String algorithm) throws Exception {
    MessageDigest digest = MessageDigest.getInstance(algorithm);
    try (InputStream is = Files.newInputStream(file)) {
      byte[] buffer = new byte[1024 * 1024];
      int read;
      while ((read = is.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private ProgressBar createProgressBar(long total) {
    return new ProgressBar(outputPath.getFileName().toString(), total);
  }

  /** Pure-Java lightweight progress bar with transfer rate and ETA calculations. */
  static class ProgressBar implements AutoCloseable {
    private static final String HIDE_CURSOR = "\u001B[?25l";
    private static final String SHOW_CURSOR = "\u001B[?25h";
    private static final String ERASE_TO_EOL = "\u001B[K";

    private final String taskName;
    private final long totalBytes;
    private final LongAdder downloaded = new LongAdder();
    private final long startTime = System.nanoTime();
    private final Thread shutdownHook;
    private final Thread renderThread;
    private volatile boolean closed = false;

    ProgressBar(String taskName, long totalBytes) {
      this.taskName = taskName;
      this.totalBytes = totalBytes;
      this.shutdownHook = new Thread(() -> System.out.print(SHOW_CURSOR));
      try {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
      } catch (IllegalStateException _) {
        // VM already shutting down
      }
      System.out.print(HIDE_CURSOR);
      System.out.flush();
      this.renderThread = Thread.ofVirtual().name("progress-render").start(this::renderLoop);
    }

    void stepBy(long bytes) {
      downloaded.add(bytes);
    }

    private void renderLoop() {
      while (!closed) {
        render();
        try {
          Thread.sleep(75); // ~13 FPS smooth update rate
        } catch (InterruptedException _) {
          break;
        }
      }
    }

    private void render() {
      long current = downloaded.sum();
      double elapsedSec = (System.nanoTime() - startTime) / 1_000_000_000.0;
      double speedMBps = elapsedSec > 0 ? (current / 1_048_576.0) / elapsedSec : 0.0;
      String displayName = taskName.length() > 20 ? taskName.substring(0, 17) + "..." : taskName;

      String output;
      if (totalBytes > 0) {
        double percent = Math.min(100.0, (current * 100.0) / totalBytes);
        int barWidth = 30;
        int completed = (int) Math.round((percent / 100.0) * barWidth);
        completed = Math.clamp(completed, 0, barWidth);
        String bar = "█".repeat(completed) + "░".repeat(barWidth - completed);
        long remainingBytes = Math.max(0, totalBytes - current);
        long etaSec = speedMBps > 0 ? (long) ((remainingBytes / 1_048_576.0) / speedMBps) : 0;

        output = String.format("\r%-20s [%s] %5.1f%% (%6.2f / %6.2f MB) %6.2f MB/s eta %02d:%02d%s",
            displayName, bar, percent, current / 1_048_576.0, totalBytes / 1_048_576.0, speedMBps,
            etaSec / 60, etaSec % 60, ERASE_TO_EOL);
      } else {
        long elapsed = (long) elapsedSec;
        output =
            String.format("\r%-20s %6.2f MB downloaded (%6.2f MB/s) [%02d:%02d]%s", displayName,
                current / 1_048_576.0, speedMBps, elapsed / 60, elapsed % 60, ERASE_TO_EOL);
      }
      System.out.print(output);
      System.out.flush();
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      renderThread.interrupt();
      try {
        renderThread.join(200);
      } catch (InterruptedException _) {
        // continue shutdown
      }
      render(); // final 100% frame
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException _) {
        // VM already shutting down
      }
      System.out.println(SHOW_CURSOR);
      System.out.flush();
    }
  }
}
