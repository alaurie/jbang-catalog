///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//DEPS me.tongfei:progressbar:0.10.1
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//NATIVE_OPTIONS -O2 --no-fallback

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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
      List.of("SHA512SUMS", "SHA256SUMS", "CHECKSUM", "CHECKSUMS", "MD5SUMS");

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
    if (lower.contains("sha512"))
      return "SHA-512";
    if (lower.contains("md5"))
      return "MD5";
    return "SHA-256";
  }

  private String extractHash(String manifestBody, String filename) {
    return manifestBody.lines().map(String::trim)
        .filter(line -> !line.startsWith("#") && !line.isBlank())
        .filter(line -> line.endsWith(filename) || line.split("\\s+").length == 1).findFirst()
        .map(line -> line.split("\\s+")[0]).orElse(null);
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
    return new ProgressBarBuilder().setTaskName(outputPath.getFileName().toString())
        .setInitialMax(total).setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
        .setUnit("MB", 1_048_576) // 1024 * 1024 bytes per unit
        .showSpeed().setUpdateIntervalMillis(200).build();
  }
}
