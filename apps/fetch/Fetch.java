///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms16m -Xmx64m -XX:CICompilerCount=2 -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData
//NATIVE_OPTIONS -O2 -march=native --no-fallback

package fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
@Command(name = "fetch", mixinStandardHelpOptions = true, version = "fetch 2.0", description = "High-performance multi-threaded CLI file downloader with auto-checksum verification")
@SuppressWarnings("unused")
class Fetch implements Callable<Integer> {

	@Parameters(index = "0", description = "Target URL to download")
	private URI uri;

	@Option(names = { "-o", "--output" }, description = "Target file output path")
	private Path outputPath;

	@Option(names = { "-c",
			"--connections" }, defaultValue = "4", description = "Concurrent chunk download connections")
	private int connections = 4;

	@Option(names = { "-q", "--quiet" }, description = "Quiet mode: disable progress bar and non-essential logs")
	private boolean quiet;

	@Option(names = { "-H",
			"--header" }, description = "Custom HTTP header(s) to send (e.g. -H 'Authorization: Bearer token')")
	private List<String> headers = new ArrayList<>();

	@Option(names = { "-A", "--user-agent" }, description = "Custom User-Agent string")
	private String customUserAgent;

	@Option(names = { "--no-resume" }, description = "Disable automatic download resumption and start fresh")
	private boolean noResume;

	@Option(names = { "--no-checksum" }, description = "Skip automatic checksum probing and verification")
	private boolean skipChecksum;

	@Option(names = {
			"--expected-hash" }, description = "Explicitly verify against this hash (auto-detects algorithm by length). Bypasses server probe.")
	private String explicitHash;

	private static final List<String> CANDIDATE_MANIFESTS = List.of("SHA512SUMS", "SHA256SUMS", "SHA512", "SHA256",
			"MD5SUMS", "MD5", "CHECKSUMS",
			"CHECKSUM", "sha512sums.txt", "sha256sums.txt", "sha512sum.txt", "sha256sum.txt");

	private final HttpClient client = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(15))
		.build();

	private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JBangFetch/2.0";

	private HttpRequest.Builder applyHeaders(HttpRequest.Builder builder) {
		builder.header("User-Agent", customUserAgent != null ? customUserAgent : DEFAULT_USER_AGENT);
		if (headers != null) {
			for (String h : headers) {
				int colon = h.indexOf(':');
				if (colon > 0) {
					builder.header(h.substring(0, colon).trim(), h.substring(colon + 1).trim());
				}
			}
		}
		return builder;
	}

	static void main(String... args) {
		int exitCode = new CommandLine(new Fetch()).execute(args);
		System.exit(exitCode);
	}

	@Override
	public Integer call() throws Exception {
		String pathStr = uri.getPath();
		String defaultFileName = (pathStr == null || pathStr.isBlank() || pathStr.endsWith("/")) ? "downloaded_file"
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
		if (Files.isRegularFile(outputPath)) {
			if (expectedHash != null) {
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
			} else {
				System.out.println("Local file exists. Re-downloading...");
			}
		}

		HttpRequest headReq = applyHeaders(HttpRequest.newBuilder(uri))
			.method("HEAD", HttpRequest.BodyPublishers.noBody())
			.build();
		HttpResponse<Void> headRes = client.send(headReq, HttpResponse.BodyHandlers.discarding());

		int headStatus = headRes.statusCode();
		if (headStatus >= 400) {
			System.err.println("Error: Server returned HTTP " + headStatus + " for " + uri);
			return 1;
		}

		// Content-Disposition filename resolution if output path wasn't an explicit custom file
		String disposition = headRes.headers().firstValue("content-disposition").orElse(null);
		if (disposition != null && (outputPath == null || Files.isDirectory(outputPath))) {
			String extractedFilename = parseContentDispositionFilename(disposition);
			if (extractedFilename != null && !extractedFilename.isBlank()) {
				outputPath = outputPath != null ? outputPath.resolve(extractedFilename) : Path.of(extractedFilename);
			}
		}

		long contentLength = headRes.headers().firstValueAsLong("content-length").orElse(-1L);
		boolean acceptsRanges = headRes.headers()
			.firstValue("accept-ranges")
			.map(v -> v.equalsIgnoreCase("bytes"))
			.orElse(false);
		String etag = headRes.headers().firstValue("etag").orElse(null);
		String lastModified = headRes.headers().firstValue("last-modified").orElse(null);

		Path partPath = Path.of(outputPath.toString() + ".part");
		Path metaPath = Path.of(partPath.toString() + ".meta");

		if (noResume) {
			Files.deleteIfExists(partPath);
			Files.deleteIfExists(metaPath);
		}

		DownloadMeta meta = null;
		if (!noResume && Files.isRegularFile(partPath) && Files.isRegularFile(metaPath)) {
			meta = DownloadMeta.load(metaPath);
			if (!isResumeValid(meta, uri, contentLength, etag, lastModified)) {
				if (!quiet) {
					System.out.println(
							"Previous download metadata mismatch or remote file changed. Starting fresh...");
				}
				Files.deleteIfExists(partPath);
				Files.deleteIfExists(metaPath);
				meta = null;
			}
		} else if (!noResume && Files.isRegularFile(partPath) && !Files.isRegularFile(metaPath)) {
			if (!quiet) {
				System.out.println(
						"Incomplete download lacks resume metadata. Starting fresh to prevent file corruption...");
			}
			Files.deleteIfExists(partPath);
		}

		String streamedHash = null;
		if (acceptsRanges && contentLength > 0) {
			List<DownloadChunk> chunks;
			if (meta != null && !meta.chunks().isEmpty()) {
				chunks = meta.chunks();
				long resumedBytes = chunks.stream().mapToLong(DownloadChunk::getDownloaded).sum();
				if (!quiet) {
					System.out.printf(
							"Resuming download with %d concurrent range workers (%.2f / %.2f MB, %.1f%%)...%n",
							chunks.size(), resumedBytes / 1_048_576.0, contentLength / 1_048_576.0,
							(resumedBytes * 100.0) / contentLength);
				}
			} else {
				int workers = Math.clamp(connections, 1, 64);
				chunks = createChunks(contentLength, workers);
				if (!quiet) {
					if (chunks.size() > 1) {
						System.out.printf("Connecting with %d concurrent range workers (%.2f MB)...%n",
								chunks.size(), contentLength / 1_048_576.0);
					} else {
						System.out.printf("Connecting (%.2f MB)...%n", contentLength / 1_048_576.0);
					}
				}
			}
			boolean success = downloadChunks(partPath, metaPath, chunks, contentLength, etag, lastModified);
			if (!success) {
				return 1;
			}
		} else {
			if (!quiet) {
				if (contentLength > 0) {
					System.out.printf("Connecting (%.2f MB)...%n", contentLength / 1_048_576.0);
				} else {
					System.out.println("Connecting (unknown size)...");
				}
			}
			MessageDigest onTheFlyDigest = null;
			if (expectedHash != null) {
				try {
					onTheFlyDigest = MessageDigest.getInstance(expectedHash.algorithm());
				} catch (Exception _) {
					onTheFlyDigest = null;
				}
			}
			boolean success = downloadSingleStream(partPath, onTheFlyDigest);
			if (!success) {
				return 1;
			}
			if (onTheFlyDigest != null) {
				streamedHash = HexFormat.of().formatHex(onTheFlyDigest.digest());
			}
		}

		Files.deleteIfExists(metaPath);
		Files.deleteIfExists(Path.of(metaPath.toString() + ".tmp"));

		// Atomically promote .part to final outputPath
		try {
			Files.move(partPath, outputPath, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException _) {
			Files.move(partPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
		}

		if (!quiet) {
			System.out.println("Saved: " + outputPath.toAbsolutePath());
		}

		if (expectedHash != null || !skipChecksum) {
			boolean verified = verifyAutoChecksum(expectedHash, streamedHash);
			if (!verified) {
				return 1;
			}
		}

		return 0;
	}

	private record ExpectedHash(String algorithm, String hash, String candidate) {
	}

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

	private boolean verifyAutoChecksum(ExpectedHash expectedHash, String streamedHash) {
		if (expectedHash == null) {
			if (!quiet) {
				System.out.println("No matching checksum manifest detected on remote server.");
			}
			return true;
		}

		if (!quiet) {
			System.out.printf("Found manifest: %s (Algorithm: %s)%n", expectedHash.candidate(),
					expectedHash.algorithm());
			System.out.print("Verifying checksum... ");
		}

		try {
			String actualHash = streamedHash;
			if (actualHash == null) {
				actualHash = computeFileHash(outputPath, expectedHash.algorithm());
			}
			if (expectedHash.hash().equalsIgnoreCase(actualHash)) {
				if (!quiet) {
					System.out.println("OK");
					System.out.println("Hash: " + actualHash);
				}
				return true;
			} else {
				if (!quiet) {
					System.out.println("FAILED");
				}
				System.err.println("Expected: " + expectedHash.hash());
				System.err.println("Actual:   " + actualHash);
				return false;
			}
		} catch (Exception e) {
			if (!quiet) {
				System.out.println("FAILED (Error reading file)");
			}
			return false;
		}
	}

	private boolean downloadSingleStream(Path partPath, MessageDigest digest) {
		try {
			HttpRequest req = applyHeaders(HttpRequest.newBuilder(uri)).GET().build();
			HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());

			int status = res.statusCode();
			if (status >= 400) {
				System.err.println("Error: Server returned HTTP " + status);
				return false;
			}

			long total = res.headers().firstValueAsLong("content-length").orElse(-1L);
			try (ProgressBar pb = createProgressBar(total, 0L);
					InputStream in = res.body();
					FileChannel out = FileChannel.open(partPath, StandardOpenOption.CREATE,
							StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

				byte[] buf = new byte[128 * 1024];
				int read;
				while ((read = in.read(buf)) != -1) {
					out.write(ByteBuffer.wrap(buf, 0, read));
					if (digest != null) {
						digest.update(buf, 0, read);
					}
					if (pb != null) {
						pb.stepBy(read);
					}
				}
			}
			return true;
		} catch (Exception e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			System.err.println("Download failed: " + cause.getMessage());
			return false;
		}
	}

	private boolean downloadChunks(Path partPath, Path metaPath, List<DownloadChunk> chunks,
			long totalSize, String etag, String lastModified) {
		long totalDownloaded = chunks.stream().mapToLong(DownloadChunk::getDownloaded).sum();

		if (totalDownloaded >= totalSize && chunks.stream().allMatch(DownloadChunk::isComplete)) {
			return true;
		}

		Thread metaSaverThread = Thread.ofVirtual().name("meta-saver").start(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					Thread.sleep(Duration.ofMillis(500));
					saveMeta(metaPath, chunks, totalSize, etag, lastModified);
				} catch (InterruptedException _) {
					break;
				}
			}
		});

		Thread shutdownHook = new Thread(() -> saveMeta(metaPath, chunks, totalSize, etag, lastModified));
		try {
			Runtime.getRuntime().addShutdownHook(shutdownHook);
		} catch (IllegalStateException _) {
		}

		try (
				FileChannel fileChannel = FileChannel.open(partPath, StandardOpenOption.CREATE,
						StandardOpenOption.WRITE, StandardOpenOption.READ);
				ProgressBar pb = createProgressBar(totalSize, totalDownloaded);
				ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

			List<CompletableFuture<Void>> futures = new ArrayList<>();
			for (DownloadChunk chunk : chunks) {
				if (chunk.isComplete()) {
					continue;
				}

				futures.add(CompletableFuture.runAsync(() -> {
					try {
						long initialDownloaded = chunk.getDownloaded();
						long startOffset = chunk.start + initialDownloaded;
						long endOffset = chunk.end;
						if (startOffset > endOffset) {
							return;
						}

						HttpRequest req = applyHeaders(HttpRequest.newBuilder(uri))
							.header("Range", "bytes=" + startOffset + "-" + endOffset)
							.GET()
							.build();

						HttpResponse<InputStream> response = client.send(req,
								HttpResponse.BodyHandlers.ofInputStream());

						int statusCode = response.statusCode();
						if (statusCode != 206) {
							throw new IOException("Server returned HTTP " + statusCode + " for range bytes="
									+ startOffset + "-" + endOffset);
						}

						try (InputStream in = response.body()) {
							byte[] buf = new byte[128 * 1024];
							long bytesRemaining = endOffset - startOffset + 1;
							long currentOffset = startOffset;
							while (bytesRemaining > 0) {
								int toRead = (int) Math.min(buf.length, bytesRemaining);
								int bytesRead = in.read(buf, 0, toRead);
								if (bytesRead == -1) {
									break;
								}
								fileChannel.write(ByteBuffer.wrap(buf, 0, bytesRead), currentOffset);
								currentOffset += bytesRead;
								chunk.downloaded.add(bytesRead);
								bytesRemaining -= bytesRead;
								if (pb != null) {
									pb.stepBy(bytesRead);
								}
							}
							if (bytesRemaining > 0) {
								throw new IOException("Connection closed prematurely (" + bytesRemaining
										+ " bytes unread in chunk " + chunk.index + ")");
							}
						}
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}, executor));
			}

			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
			return true;
		} catch (Exception e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			System.err.println("Download interrupted: " + cause.getMessage());
			return false;
		} finally {
			metaSaverThread.interrupt();
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException _) {
			}
			saveMeta(metaPath, chunks, totalSize, etag, lastModified);
		}
	}

	private static List<DownloadChunk> createChunks(long totalSize, int count) {
		int numChunks = Math.clamp(count, 1, 64);
		long chunkSize = (long) Math.ceil((double) totalSize / numChunks);
		List<DownloadChunk> chunks = new ArrayList<>();
		for (int i = 0; i < numChunks; i++) {
			long start = i * chunkSize;
			long end = Math.min(start + chunkSize - 1, totalSize - 1);
			if (start <= end) {
				chunks.add(new DownloadChunk(i, start, end, 0L));
			}
		}
		return chunks;
	}

	private synchronized void saveMeta(Path metaPath, List<DownloadChunk> chunks, long contentLength,
			String etag, String lastModified) {
		try {
			Path tmp = Path.of(metaPath.toString() + ".tmp");
			var sb = new StringBuilder();
			sb.append("version=1\n");
			sb.append("uri=").append(uri).append("\n");
			sb.append("contentLength=").append(contentLength).append("\n");
			if (etag != null) {
				sb.append("etag=").append(etag).append("\n");
			}
			if (lastModified != null) {
				sb.append("lastModified=").append(lastModified).append("\n");
			}
			sb.append("chunks=").append(chunks.size()).append("\n");
			for (var c : chunks) {
				sb.append("chunk=")
					.append(c.index)
					.append(",")
					.append(c.start)
					.append(",")
					.append(c.end)
					.append(",")
					.append(c.getDownloaded())
					.append("\n");
			}
			Files.writeString(tmp, sb.toString(), StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			try {
				Files.move(tmp, metaPath, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException _) {
				Files.move(tmp, metaPath, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception _) {
			// Suppress during process exit / interrupted state
		}
	}

	private boolean isResumeValid(DownloadMeta meta, URI currentUri, long currentLength,
			String currentEtag, String currentLastModified) {
		if (meta == null) {
			return false;
		}
		if (meta.contentLength() != currentLength) {
			return false;
		}
		if (meta.uri() != null && !meta.uri().equals(currentUri.toString())) {
			return false;
		}
		if (currentEtag != null && meta.etag() != null && !currentEtag.equals(meta.etag())) {
			return false;
		}
		if (currentLastModified != null && meta.lastModified() != null
				&& !currentLastModified.equals(meta.lastModified())) {
			return false;
		}
		return true;
	}

	private record DownloadMeta(int version, String uri, long contentLength, String etag, String lastModified,
			List<DownloadChunk> chunks) {
		static DownloadMeta load(Path metaPath) {
			try {
				if (!Files.isRegularFile(metaPath)) {
					return null;
				}
				List<String> lines = Files.readAllLines(metaPath);
				int version = 1;
				String uriStr = null;
				long contentLength = -1;
				String etag = null;
				String lastModified = null;
				List<DownloadChunk> chunks = new ArrayList<>();

				for (String line : lines) {
					line = line.trim();
					if (line.isBlank() || line.startsWith("#")) {
						continue;
					}
					int eq = line.indexOf('=');
					if (eq < 0) {
						continue;
					}
					String key = line.substring(0, eq).trim();
					String val = line.substring(eq + 1).trim();
					switch (key) {
					case "version" -> version = Integer.parseInt(val);
					case "uri" -> uriStr = val;
					case "contentLength" -> contentLength = Long.parseLong(val);
					case "etag" -> etag = val;
					case "lastModified" -> lastModified = val;
					case "chunk" -> {
						String[] parts = val.split(",");
						if (parts.length >= 4) {
							int idx = Integer.parseInt(parts[0].trim());
							long start = Long.parseLong(parts[1].trim());
							long end = Long.parseLong(parts[2].trim());
							long downloaded = Long.parseLong(parts[3].trim());
							chunks.add(new DownloadChunk(idx, start, end, downloaded));
						}
					}
					default -> {
					}
					}
				}
				if (chunks.isEmpty() || contentLength <= 0) {
					return null;
				}
				return new DownloadMeta(version, uriStr, contentLength, etag, lastModified, chunks);
			} catch (Exception _) {
				return null;
			}
		}
	}

	static class DownloadChunk {
		final int index;
		final long start;
		final long end;
		final LongAdder downloaded = new LongAdder();

		DownloadChunk(int index, long start, long end, long initialDownloaded) {
			this.index = index;
			this.start = start;
			this.end = end;
			if (initialDownloaded > 0) {
				this.downloaded.add(initialDownloaded);
			}
		}

		long totalBytes() {
			return end - start + 1;
		}

		long getDownloaded() {
			return downloaded.sum();
		}

		boolean isComplete() {
			return getDownloaded() >= totalBytes();
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
		long totalBytes = Files.size(file);
		MessageDigest digest = MessageDigest.getInstance(algorithm);
		int bufferSize = 8 * 1024 * 1024; // 8 MB high-throughput direct buffer
		ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);

		String taskName = "Verifying " + algorithm;
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
				ProgressBar pb = quiet ? null : new ProgressBar(taskName, totalBytes, 0L)) {
			while (channel.read(buffer) > 0) {
				buffer.flip();
				int remaining = buffer.remaining();
				digest.update(buffer);
				buffer.clear();
				if (pb != null) {
					pb.stepBy(remaining);
				}
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private ProgressBar createProgressBar(long total, long initialOffset) {
		if (quiet)
			return null;
		return new ProgressBar(outputPath.getFileName().toString(), total, initialOffset);
	}

	private static String parseContentDispositionFilename(String disposition) {
		if (disposition == null)
			return null;
		for (String part : disposition.split(";")) {
			String trimmed = part.trim();
			if (trimmed.toLowerCase(Locale.ROOT).startsWith("filename*=")) {
				String val = trimmed.substring(10).trim();
				int lastQuote = val.lastIndexOf("''");
				if (lastQuote >= 0) {
					return URI.create(val.substring(lastQuote + 2)).getPath();
				}
			} else if (trimmed.toLowerCase(Locale.ROOT).startsWith("filename=")) {
				String val = trimmed.substring(9).trim();
				if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
					val = val.substring(1, val.length() - 1);
				}
				return Path.of(val).getFileName().toString();
			}
		}
		return null;
	}

	/// Pure-Java lightweight progress bar with transfer rate and ETA calculations.
	static class ProgressBar implements AutoCloseable {
		private static final String HIDE_CURSOR = "\u001B[?25l";
		private static final String SHOW_CURSOR = "\u001B[?25h";
		private static final String ERASE_TO_EOL = "\u001B[K";

		private final String taskName;
		private final long totalBytes;
		private final long initialOffset;
		private final LongAdder sessionDownloaded = new LongAdder();
		private final long startTime = System.nanoTime();
		private final Thread shutdownHook;
		private final Thread renderThread;
		private volatile boolean closed = false;

		ProgressBar(String taskName, long totalBytes, long initialOffset) {
			this.taskName = taskName;
			this.totalBytes = totalBytes;
			this.initialOffset = Math.max(0L, initialOffset);
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
			sessionDownloaded.add(bytes);
		}

		private void renderLoop() {
			while (!closed) {
				render();
				try {
					Thread.sleep(Duration.ofMillis(75)); // ~13 FPS smooth update rate
				} catch (InterruptedException _) {
					break;
				}
			}
		}

		private void render() {
			long inSession = sessionDownloaded.sum();
			long current = initialOffset + inSession;
			double elapsedSec = (System.nanoTime() - startTime) / 1_000_000_000.0;
			double speedMBps = elapsedSec > 0 ? (inSession / 1_048_576.0) / elapsedSec : 0.0;
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
				output = String.format("\r%-20s %6.2f MB downloaded (%6.2f MB/s) [%02d:%02d]%s", displayName,
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
