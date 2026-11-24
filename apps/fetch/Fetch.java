///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms16m -Xmx256m -XX:CICompilerCount=2 -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData
//NATIVE_OPTIONS -O2 -march=native --no-fallback

package fetch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// High-performance multi-threaded CLI file downloader with auto-checksum
/// verification.
///
/// Supports concurrent chunked range requests and automatic remote
/// manifest probing.
@Command(name = "fetch", mixinStandardHelpOptions = true, version = "fetch 2.1.0", description = "High-performance multi-threaded CLI file downloader with auto-checksum verification")
@SuppressWarnings("unused")
class Fetch implements Callable<Integer> {

	@Parameters(index = "0", arity = "0..1", description = "Target URL (or HTML page URL in bulk mode) to download")
	private URI uri;

	@Option(names = { "-o",
			"--output" }, description = "Target file output path (or destination directory in bulk mode)")
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
	@Option(names = {
			"--print-hash" }, description = "Compute and print SHA-256 hash of the downloaded file upon completion")
	private boolean printHash;
	@Option(names = {
			"--retries" }, defaultValue = "0", description = "Number of retries for failed chunk requests (default: 0)")
	private int retries = 0;

	@Option(names = {
			"--bulk" }, description = "Bulk download mode: extract and download matching links from an HTML page or directory index")
	private boolean bulk;

	@Option(names = { "-i",
			"--input" }, description = "Read target URLs from a file (or '-' for stdin) for bulk download")
	private String inputSource;

	@Option(names = {
			"--type" }, split = ",", description = "Bulk filter by category: video, image, audio, archive, doc")
	private List<String> filterTypes = new ArrayList<>();

	@Option(names = { "--ext" }, split = ",", description = "Bulk filter by file extension(s), e.g. --ext mp4,mkv")
	private List<String> filterExts = new ArrayList<>();

	@Option(names = {
			"--match" }, description = "Bulk filter: include filenames matching glob pattern (e.g. '*1080p*')")
	private List<String> includeGlobs = new ArrayList<>();

	@Option(names = {
			"--exclude" }, description = "Bulk filter: exclude filenames matching glob pattern (e.g. '*sample*')")
	private List<String> excludeGlobs = new ArrayList<>();

	@Option(names = { "-r",
			"--recursive" }, description = "Recursively crawl subdirectories on the same host/path in bulk mode")
	private boolean recursive;

	@Option(names = {
			"--max-depth" }, defaultValue = "3", description = "Maximum recursion depth for --recursive (default: 3)")
	private int maxDepth = 3;

	@Option(names = { "--dry-run" }, description = "List matched bulk URLs without downloading")
	private boolean dryRun;

	private static final List<String> CANDIDATE_MANIFESTS = List.of("SHA512SUMS", "SHA256SUMS", "SHA512", "SHA256",
			"MD5SUMS", "MD5", "CHECKSUMS",
			"CHECKSUM", "sha512sums.txt", "sha256sums.txt", "sha512sum.txt", "sha256sum.txt");
	private static final int MAX_MANIFEST_BYTES = 64 * 1024;
	private static final int MAX_HTML_BYTES = 4 * 1024 * 1024;
	private static final Duration MANIFEST_TIMEOUT = Duration.ofSeconds(3);
	private static final Pattern CONTENT_RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
	private static final Pattern HTML_LINK_PATTERN = Pattern
		.compile("(?i)\\b(?:href|src)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

	private static final Map<String, Set<String>> TYPE_PRESETS = Map.of(
			"video", Set.of("mp4", "mkv", "webm", "avi", "mov", "m4v", "flv", "wmv", "ts", "m2ts", "ogv"),
			"image", Set.of("jpg", "jpeg", "png", "gif", "webp", "svg", "avif", "bmp", "tiff", "tif", "ico", "heic"),
			"audio", Set.of("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma", "aiff"),
			"archive", Set.of("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst"),
			"doc", Set.of("pdf", "epub", "txt", "md", "csv", "json", "xml", "docx", "xlsx", "pptx"));

	private record CachedManifest(String candidate, String algorithm, String body) {
	}

	private final Map<URI, List<CachedManifest>> directoryManifestCache = new HashMap<>();

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

	void main(String... args) {
		int exitCode = new CommandLine(this).execute(args);
		System.exit(exitCode);
	}

	@Override
	public Integer call() throws Exception {
		if (uri == null && (inputSource == null || inputSource.isBlank())) {
			System.err.println("Error: Either a target URL or --input (-i) must be specified.");
			return 1;
		}
		if (skipChecksum && explicitHash != null && !explicitHash.isBlank()) {
			System.err.println("Error: --expected-hash cannot be combined with --no-checksum.");
			return 1;
		}

		boolean hasFilters = !filterTypes.isEmpty() || !filterExts.isEmpty()
				|| !includeGlobs.isEmpty() || !excludeGlobs.isEmpty();
		boolean isBulkMode = bulk || inputSource != null || hasFilters || recursive || dryRun;

		if (isBulkMode) {
			return runBulkDownload();
		}
		return downloadSingleFile(uri, outputPath);
	}

	private int runBulkDownload() throws Exception {
		if (explicitHash != null && !explicitHash.isBlank()) {
			System.err.println("Error: --expected-hash cannot be used in bulk download mode.");
			return 1;
		}
		if (outputPath != null && Files.isRegularFile(outputPath)) {
			System.err.printf("Error: Output path '%s' is an existing file; bulk mode requires a directory.%n",
					outputPath);
			return 1;
		}

		Set<String> allowedExts;
		try {
			allowedExts = resolveAllowedExtensions();
		} catch (IllegalArgumentException e) {
			System.err.println("Error: " + e.getMessage());
			return 1;
		}

		var includeMatchers = compileGlobs(includeGlobs);
		var excludeMatchers = compileGlobs(excludeGlobs);

		List<URI> candidates;
		if (inputSource != null && !inputSource.isBlank()) {
			candidates = readUrlsFromInput(inputSource);
		} else {
			candidates = discoverBulkLinks(uri, Math.max(0, maxDepth));
		}

		var matched = new ArrayList<URI>();
		for (var candidate : candidates) {
			if (matchesFilters(candidate, allowedExts, includeMatchers, excludeMatchers)) {
				matched.add(candidate);
			}
		}

		if (matched.isEmpty()) {
			System.out.println("No matching files found.");
			return 0;
		}

		if (dryRun) {
			System.out.printf("Matched %d file(s):%n", matched.size());
			for (var item : matched) {
				System.out.println(item);
			}
			return 0;
		}

		Path targetDir = outputPath != null ? outputPath : Path.of(".");
		Files.createDirectories(targetDir);

		int total = matched.size();
		int succeeded = 0;
		int failed = 0;
		URI baseRoot = uri != null ? uri.resolve("./") : null;

		for (int i = 0; i < total; i++) {
			var itemUri = matched.get(i);
			Path itemOut = resolveBulkTargetPath(targetDir, baseRoot, itemUri);
			if (!quiet) {
				System.out.printf("%n[%d/%d] %s%n", i + 1, total, itemUri);
			}
			int rc = downloadSingleFile(itemUri, itemOut);
			if (rc == 0) {
				succeeded++;
			} else {
				failed++;
			}
		}

		if (!quiet) {
			System.out.printf("%nBulk download complete: %d succeeded, %d failed (out of %d).%n",
					succeeded, failed, total);
		}
		return failed == 0 ? 0 : 1;
	}

	private Set<String> resolveAllowedExtensions() {
		var exts = new LinkedHashSet<String>();
		for (var rawType : filterTypes) {
			var key = rawType.trim().toLowerCase(Locale.ROOT);
			if (key.isEmpty()) {
				continue;
			}
			var normalized = switch (key) {
			case "videos" -> "video";
			case "images" -> "image";
			case "audios" -> "audio";
			case "archives" -> "archive";
			case "docs", "document", "documents" -> "doc";
			default -> key;
			};
			var preset = TYPE_PRESETS.get(normalized);
			if (preset == null) {
				throw new IllegalArgumentException("Unknown --type category '" + rawType
						+ "'. Supported: video, image, audio, archive, doc.");
			}
			exts.addAll(preset);
		}
		for (var rawExt : filterExts) {
			var ext = rawExt.trim().toLowerCase(Locale.ROOT);
			if (ext.startsWith(".")) {
				ext = ext.substring(1);
			}
			if (!ext.isEmpty()) {
				exts.add(ext);
			}
		}
		return exts;
	}

	private static List<PathMatcher> compileGlobs(List<String> patterns) {
		var fs = FileSystems.getDefault();
		var matchers = new ArrayList<PathMatcher>(patterns.size());
		for (var pattern : patterns) {
			if (pattern != null && !pattern.isBlank()) {
				matchers.add(fs.getPathMatcher("glob:" + pattern.trim()));
			}
		}
		return matchers;
	}

	private List<URI> readUrlsFromInput(String source) throws IOException {
		var urls = new LinkedHashSet<URI>();
		BufferedReader reader;
		boolean closeReader;
		if ("-".equals(source.trim())) {
			reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
			closeReader = false;
		} else {
			reader = Files.newBufferedReader(Path.of(source), StandardCharsets.UTF_8);
			closeReader = true;
		}
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				var trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}
				try {
					var parsed = URI.create(trimmed);
					if (isHttpUri(parsed)) {
						urls.add(stripFragment(parsed));
					}
				} catch (Exception _) {
					System.err.println("Warning: Skipping invalid URL: " + trimmed);
				}
			}
		} finally {
			if (closeReader) {
				reader.close();
			}
		}
		return List.copyOf(urls);
	}

	private List<URI> discoverBulkLinks(URI rootUri, int maxCrawlDepth) throws Exception {
		record PageNode(URI pageUri, int depth) {
		}

		var discoveredFiles = new LinkedHashSet<URI>();
		var visitedPages = new HashSet<URI>();
		var queue = new ArrayDeque<PageNode>();

		URI scopeRoot = rootUri.resolve("./");
		String scopeHost = scopeRoot.getHost();
		String scopePath = scopeRoot.getPath() != null ? scopeRoot.getPath() : "/";

		queue.add(new PageNode(stripFragment(rootUri), 0));

		while (!queue.isEmpty()) {
			var node = queue.poll();
			if (!visitedPages.add(node.pageUri())) {
				continue;
			}

			var req = applyHeaders(HttpRequest.newBuilder(node.pageUri()))
				.timeout(Duration.ofSeconds(15))
				.GET()
				.build();
			HttpResponse<String> res = client.send(req,
					HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), MAX_HTML_BYTES));
			if (res.statusCode() >= 400) {
				System.err.printf("Warning: HTTP %d while scanning %s%n", res.statusCode(), node.pageUri());
				continue;
			}

			String contentType = res.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
			if (!contentType.contains("html") && !contentType.isEmpty()) {
				// Direct file URL passed with filters/bulk: treat it as a candidate file.
				discoveredFiles.add(node.pageUri());
				continue;
			}

			var matcher = HTML_LINK_PATTERN.matcher(res.body());
			while (matcher.find()) {
				String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
				if (raw == null) {
					continue;
				}
				String trimmed = raw.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("?")
						|| trimmed.toLowerCase(Locale.ROOT).startsWith("javascript:")
						|| trimmed.toLowerCase(Locale.ROOT).startsWith("mailto:")
						|| trimmed.toLowerCase(Locale.ROOT).startsWith("data:")) {
					continue;
				}

				URI resolved;
				try {
					resolved = stripFragment(node.pageUri().resolve(trimmed));
				} catch (Exception _) {
					continue;
				}
				if (!isHttpUri(resolved)) {
					continue;
				}

				String resolvedPath = resolved.getPath();
				if (resolvedPath == null || resolvedPath.isEmpty()) {
					continue;
				}

				if (resolvedPath.endsWith("/")) {
					if (recursive && node.depth() < maxCrawlDepth
							&& isWithinScope(resolved, scopeHost, scopePath)
							&& !visitedPages.contains(resolved)) {
						queue.add(new PageNode(resolved, node.depth() + 1));
					}
					continue;
				}

				discoveredFiles.add(resolved);
			}
		}

		return List.copyOf(discoveredFiles);
	}

	private static boolean isHttpUri(URI u) {
		String scheme = u.getScheme();
		return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
	}

	private static URI stripFragment(URI u) {
		if (u.getFragment() == null) {
			return u;
		}
		return URI.create(u.toString().substring(0, u.toString().indexOf('#')));
	}

	private static boolean isWithinScope(URI candidate, String scopeHost, String scopePath) {
		if (scopeHost == null || !scopeHost.equalsIgnoreCase(candidate.getHost())) {
			return false;
		}
		String p = candidate.getPath();
		return p != null && p.startsWith(scopePath);
	}

	private static boolean matchesFilters(URI candidate, Set<String> allowedExts,
			List<PathMatcher> includeMatchers, List<PathMatcher> excludeMatchers) {
		String filename = extractDecodedFileName(candidate);
		if (filename == null || filename.isBlank()) {
			return false;
		}

		if (!allowedExts.isEmpty()) {
			int dot = filename.lastIndexOf('.');
			if (dot <= 0 || dot == filename.length() - 1) {
				return false;
			}
			String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
			if (!allowedExts.contains(ext)) {
				return false;
			}
		}

		Path namePath = Path.of(filename);
		if (!includeMatchers.isEmpty()) {
			boolean anyMatch = false;
			for (var m : includeMatchers) {
				if (m.matches(namePath)) {
					anyMatch = true;
					break;
				}
			}
			if (!anyMatch) {
				return false;
			}
		}

		for (var m : excludeMatchers) {
			if (m.matches(namePath)) {
				return false;
			}
		}

		return true;
	}

	private static String extractDecodedFileName(URI u) {
		String path = u.getPath();
		if (path == null || path.isBlank() || path.endsWith("/")) {
			return null;
		}
		int slash = path.lastIndexOf('/');
		String rawName = slash >= 0 ? path.substring(slash + 1) : path;
		if (rawName.isEmpty() || ".".equals(rawName) || "..".equals(rawName)) {
			return null;
		}
		try {
			return URLDecoder.decode(rawName, StandardCharsets.UTF_8);
		} catch (Exception _) {
			return rawName;
		}
	}

	private Path resolveBulkTargetPath(Path targetDir, URI baseRoot, URI itemUri) {
		String fileName = extractDecodedFileName(itemUri);
		if (fileName == null || fileName.isBlank()) {
			fileName = "downloaded_file";
		}
		fileName = Path.of(fileName).getFileName().toString();

		if (recursive && baseRoot != null && isWithinScope(itemUri, baseRoot.getHost(),
				baseRoot.getPath() != null ? baseRoot.getPath() : "/")) {
			URI rel = baseRoot.relativize(itemUri);
			String relPath = rel.getPath();
			if (relPath != null && !relPath.isBlank() && !rel.isAbsolute()) {
				Path resolved = targetDir.resolve(relPath).normalize();
				if (resolved.startsWith(targetDir.normalize())) {
					return resolved;
				}
			}
		}
		return targetDir.resolve(fileName);
	}

	private int downloadSingleFile(URI targetUri, Path targetOutput) throws Exception {
		this.uri = targetUri;
		this.outputPath = targetOutput;

		String pathStr = uri.getPath();
		String defaultFileName = (pathStr == null || pathStr.isBlank() || pathStr.endsWith("/")) ? "downloaded_file"
				: Path.of(pathStr).getFileName().toString();

		boolean isDirectoryTarget = outputPath == null || Files.isDirectory(outputPath)
				|| outputPath.toString().endsWith("/") || outputPath.toString().endsWith("\\");
		Path targetDir = isDirectoryTarget ? outputPath : null;

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
		if (isDirectoryTarget) {
			String disposition = headRes.headers().firstValue("content-disposition").orElse(null);
			String extractedFilename = parseContentDispositionFilename(disposition);
			String resolvedName = (extractedFilename != null && !extractedFilename.isBlank())
					? Path.of(extractedFilename).getFileName().toString()
					: defaultFileName;
			outputPath = targetDir != null ? targetDir.resolve(resolvedName) : Path.of(resolvedName);
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

		long contentLength = headRes.headers().firstValueAsLong("content-length").orElse(-1L);
		boolean acceptsRanges = headRes.headers()
			.firstValue("accept-ranges")
			.map(v -> v.equalsIgnoreCase("bytes"))
			.orElse(false);
		String etag = headRes.headers().firstValue("etag").orElse(null);
		String strongEtag = isStrongEtag(etag) ? etag : null;
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
			if (!isResumeValid(meta, uri, contentLength, strongEtag, lastModified)) {
				if (!quiet) {
					System.out.println(
							"Previous download metadata mismatch or remote file changed. Starting fresh...");
				}
				Files.deleteIfExists(partPath);
				Files.deleteIfExists(metaPath);
				meta = null;
			}
			// A completed .part with a bad checksum cannot be repaired by resuming its chunks.
			if (meta != null && expectedHash != null
					&& meta.chunks().stream().allMatch(DownloadChunk::isComplete) && !expectedHash.hash()
						.equalsIgnoreCase(computeFileHash(partPath, expectedHash.algorithm()))) {
				if (!quiet) {
					System.out.println("Completed partial download failed checksum. Starting fresh...");
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
		if (acceptsRanges && contentLength > 0 && strongEtag != null) {
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
			boolean success = downloadChunks(partPath, metaPath, chunks, contentLength, strongEtag, lastModified);
			if (!success) {
				return 1;
			}
		} else {
			// A streaming retry truncates the partial file; range progress no longer describes it.
			Files.deleteIfExists(metaPath);
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

		if (expectedHash == null && !skipChecksum && !quiet) {
			System.out.println("No matching checksum manifest detected on remote server.");
		}

		if (expectedHash != null && !verifyAutoChecksum(expectedHash, streamedHash, partPath)) {
			return 1;
		}

		// Only replace an existing destination after the downloaded bytes have passed verification.
		try {
			Files.move(partPath, outputPath, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException _) {
			Files.move(partPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
		}
		Files.deleteIfExists(metaPath);
		Files.deleteIfExists(Path.of(metaPath.toString() + ".tmp"));

		if (!quiet) {
			System.out.println("Saved: " + outputPath.toAbsolutePath());
		}
		if (printHash) {
			String finalHash = (expectedHash != null && "SHA-256".equalsIgnoreCase(expectedHash.algorithm())
					&& streamedHash != null) ? streamedHash : computeFileHash(outputPath, "SHA-256");
			System.out.printf("SHA-256: %s%n", finalHash);
		}

		return 0;
	}

	private record ExpectedHash(String algorithm, String hash, String candidate) {
	}

	private ExpectedHash findExpectedHash(String remoteFilename, String localFilename) {
		URI baseUri = uri.resolve("./");

		var cachedManifests = directoryManifestCache.computeIfAbsent(baseUri, this::fetchDirectoryManifests);
		for (var manifest : cachedManifests) {
			String expectedHash = extractHash(manifest.body(), remoteFilename);
			if (expectedHash == null && !localFilename.equals(remoteFilename)) {
				expectedHash = extractHash(manifest.body(), localFilename);
			}
			if (expectedHash != null) {
				return new ExpectedHash(manifest.algorithm(), expectedHash, manifest.candidate());
			}
		}

		List<String> perFileCandidates = new ArrayList<>();
		perFileCandidates.add(remoteFilename + ".sha256");
		perFileCandidates.add(remoteFilename + ".sha512");
		if (!localFilename.equals(remoteFilename)) {
			perFileCandidates.add(localFilename + ".sha256");
			perFileCandidates.add(localFilename + ".sha512");
		}

		for (String candidate : perFileCandidates) {
			URI manifestUri = baseUri.resolve(candidate);
			HttpRequest req = applyHeaders(HttpRequest.newBuilder(manifestUri))
				.timeout(MANIFEST_TIMEOUT)
				.GET()
				.build();

			try {
				HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers
					.limiting(HttpResponse.BodyHandlers.ofString(), MAX_MANIFEST_BYTES));
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

	private List<CachedManifest> fetchDirectoryManifests(URI baseUri) {
		var found = new ArrayList<CachedManifest>();
		for (String candidate : CANDIDATE_MANIFESTS) {
			URI manifestUri = baseUri.resolve(candidate);
			HttpRequest req = applyHeaders(HttpRequest.newBuilder(manifestUri))
				.timeout(MANIFEST_TIMEOUT)
				.GET()
				.build();
			try {
				HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers
					.limiting(HttpResponse.BodyHandlers.ofString(), MAX_MANIFEST_BYTES));
				if (res.statusCode() == 200) {
					found.add(new CachedManifest(candidate, determineAlgorithm(candidate), res.body()));
				}
			} catch (Exception _) {
				// Continue scanning candidates if request fails
			}
		}
		return List.copyOf(found);
	}

	private boolean verifyAutoChecksum(ExpectedHash expectedHash, String streamedHash,
			Path partPath) {

		if (!quiet) {
			System.out.printf("Found manifest: %s (Algorithm: %s)%n", expectedHash.candidate(),
					expectedHash.algorithm());
			System.out.print("Verifying checksum... ");
		}

		try {
			String actualHash = streamedHash;
			if (actualHash == null) {
				actualHash = computeFileHash(partPath, expectedHash.algorithm());
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
			System.err.println("Error verifying checksum: " + e.getMessage());
			return false;
		}
	}

	private boolean downloadSingleStream(Path partPath, MessageDigest digest) {
		try {
			// JDK 26+ applies request timeouts to the body too; downloads have no total-duration limit.
			HttpRequest req = applyHeaders(HttpRequest.newBuilder(uri))
				.GET()
				.build();
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
				ByteBuffer buffer = ByteBuffer.wrap(buf);
				int read;
				while ((read = in.read(buf)) != -1) {
					buffer.clear();
					buffer.limit(read);
					while (buffer.hasRemaining()) {
						out.write(buffer);
					}
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
					int maxRetries = Math.max(0, retries);
					for (int attempt = 0; attempt <= maxRetries; attempt++) {
						try {
							long currentDownloaded = chunk.getDownloaded();
							long startOffset = chunk.start + currentDownloaded;
							long endOffset = chunk.end;
							if (startOffset > endOffset) {
								return;
							}

							// Keep the connect timeout, but allow large ranges to take as long as needed.
							HttpRequest req = applyHeaders(HttpRequest.newBuilder(uri))
								.header("Range", "bytes=" + startOffset + "-" + endOffset)
								.header("If-Match", etag)
								.GET()
								.build();

							HttpResponse<InputStream> response = client.send(req,
									HttpResponse.BodyHandlers.ofInputStream());

							try (InputStream in = response.body()) {
								int statusCode = response.statusCode();
								if (statusCode != 206) {
									throw new IOException("Server returned HTTP " + statusCode + " for range bytes="
											+ startOffset + "-" + endOffset);
								}
								String responseEtag = response.headers().firstValue("etag").orElse(null);
								if (!etag.equals(responseEtag)) {
									throw new IOException("ETag changed during ranged download");
								}
								String contentRange = response.headers().firstValue("content-range").orElse("");
								if (!matchesContentRange(contentRange, startOffset, endOffset, totalSize)) {
									throw new IOException("Invalid Content-Range for bytes=" + startOffset + "-"
											+ endOffset + ": " + contentRange);
								}
								byte[] buf = new byte[128 * 1024];
								ByteBuffer buffer = ByteBuffer.wrap(buf);
								long bytesRemaining = endOffset - startOffset + 1;
								long currentOffset = startOffset;
								while (bytesRemaining > 0) {
									int toRead = (int) Math.min(buf.length, bytesRemaining);
									int bytesRead = in.read(buf, 0, toRead);
									if (bytesRead == -1) {
										break;
									}
									buffer.clear();
									buffer.limit(bytesRead);
									while (buffer.hasRemaining()) {
										currentOffset += fileChannel.write(buffer, currentOffset);
									}
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
							return;
						} catch (Exception e) {
							if (attempt >= maxRetries) {
								throw new RuntimeException(e);
							}
							try {
								Thread.sleep(500L * (attempt + 1));
							} catch (InterruptedException _) {
								throw new RuntimeException(e);
							}
						}
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

	private static boolean matchesContentRange(String header, long start, long end, long total) {
		var match = CONTENT_RANGE.matcher(header);
		if (!match.matches()) {
			return false;
		}
		try {
			return Long.parseLong(match.group(1)) == start && Long.parseLong(match.group(2)) == end
					&& Long.parseLong(match.group(3)) == total;
		} catch (NumberFormatException _) {
			return false;
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

	private static boolean isStrongEtag(String etag) {
		return etag != null && etag.length() >= 2 && etag.charAt(0) == '"'
				&& etag.indexOf('"', 1) == etag.length() - 1;
	}

	private boolean isResumeValid(DownloadMeta meta, URI currentUri, long currentLength,
			String currentEtag, String currentLastModified) {
		if (meta == null) {
			return false;
		}
		if (meta.contentLength() != currentLength || currentLength <= 0) {
			return false;
		}
		if (!currentUri.toString().equals(meta.uri())) {
			return false;
		}
		if (currentEtag == null || !currentEtag.equals(meta.etag())) {
			return false;
		}
		return currentLastModified == null || meta.lastModified() == null
				|| currentLastModified.equals(meta.lastModified());
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
		var it = manifestBody.lines().map(String::trim).iterator();
		while (it.hasNext()) {
			String line = it.next();
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
		private final int terminalWidth;
		private volatile boolean closed = false;

		private final boolean isInteractive;
		private int lastLoggedPercent = -1;

		ProgressBar(String taskName, long totalBytes, long initialOffset) {
			this.taskName = taskName;
			this.totalBytes = totalBytes;
			this.initialOffset = Math.max(0L, initialOffset);
			this.isInteractive = System.console() != null;
			this.terminalWidth = detectTerminalWidth();
			if (isInteractive) {
				this.shutdownHook = new Thread(() -> System.out.print(SHOW_CURSOR));
				try {
					Runtime.getRuntime().addShutdownHook(shutdownHook);
				} catch (IllegalStateException _) {
					// VM already shutting down
				}
				System.out.print(HIDE_CURSOR);
				System.out.flush();
			} else {
				this.shutdownHook = null;
			}
			this.renderThread = Thread.ofVirtual().name("progress-render").start(this::renderLoop);
		}

		void stepBy(long bytes) {
			sessionDownloaded.add(bytes);
		}

		private void renderLoop() {
			while (!closed) {
				render();
				try {
					Thread.sleep(Duration.ofMillis(isInteractive ? 75 : 1000));
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

			if (isInteractive) {
				System.out.print(
						"\r" + formatProgressLine(taskName, totalBytes, current, speedMBps, terminalWidth)
								+ ERASE_TO_EOL);
				System.out.flush();
			} else if (totalBytes > 0) {
				int percent = (int) Math.min(100, (current * 100) / totalBytes);
				if (percent >= lastLoggedPercent + 20 || percent == 100) {
					lastLoggedPercent = percent;
					System.out.printf("%s: %d%% (%.2f / %.2f MB) at %.2f MB/s%n", truncate(taskName, 20),
							percent, current / 1_048_576.0, totalBytes / 1_048_576.0, speedMBps);
				}
			}
		}

		static String formatProgressLine(String taskName, long totalBytes, long current,
				double speedMBps, int terminalWidth) {
			int width = Math.max(1, terminalWidth);
			double percent = totalBytes > 0 ? Math.min(100.0, (current * 100.0) / totalBytes) : 0.0;
			if (totalBytes <= 0) {
				return fit("%s %6.2f MB (%5.2f MB/s)".formatted(taskName, current / 1_048_576.0, speedMBps),
						width);
			}

			long remainingBytes = Math.max(0, totalBytes - current);
			long etaSec = speedMBps > 0 ? (long) ((remainingBytes / 1_048_576.0) / speedMBps) : 0;
			int barWidth = Math.clamp(width - 68, 0, 30);
			int completed = (int) Math.round((percent / 100.0) * barWidth);
			String bar = "█".repeat(completed) + "░".repeat(barWidth - completed);
			String line = "%s [%s] %5.1f%% (%6.2f / %6.2f MB) %5.2f MB/s %02d:%02d".formatted(
					truncate(taskName, 20), bar, percent, current / 1_048_576.0, totalBytes / 1_048_576.0,
					speedMBps, etaSec / 60, etaSec % 60);
			if (line.length() <= width) {
				return line;
			}

			line = "%5.1f%% %6.2f/%6.2f MB %5.2f MB/s".formatted(percent, current / 1_048_576.0,
					totalBytes / 1_048_576.0, speedMBps);
			if (line.length() <= width) {
				return line;
			}
			return fit("%5.1f%% %6.2f/%6.2f MB".formatted(percent, current / 1_048_576.0,
					totalBytes / 1_048_576.0), width);
		}

		private static String fit(String text, int width) {
			return text.length() <= width ? text : text.substring(0, width);
		}

		private static String truncate(String text, int maxLength) {
			return text.length() <= maxLength ? text : text.substring(0, maxLength - 3) + "...";
		}

		private static int detectTerminalWidth() {
			try {
				var process = new ProcessBuilder("stty", "size")
					.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/tty").toFile()))
					.start();
				try (BufferedReader reader = process.inputReader()) {
					String dimensions = reader.readLine();
					if (process.waitFor(Duration.ofMillis(100)) && process.exitValue() == 0
							&& dimensions != null) {
						String[] fields = dimensions.trim().split("\\s+");
						return Integer.parseInt(fields[fields.length - 1]);
					}
				}
			} catch (Exception _) {
				// Fall back to the conventional terminal width.
			}
			try {
				return Integer.parseInt(System.getenv().getOrDefault("COLUMNS", "80"));
			} catch (NumberFormatException _) {
				return 80;
			}
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
			if (isInteractive) {
				render(); // final 100% frame
				if (shutdownHook != null) {
					try {
						Runtime.getRuntime().removeShutdownHook(shutdownHook);
					} catch (IllegalStateException _) {
						// VM already shutting down
					}
				}
				System.out.println(SHOW_CURSOR);
				System.out.flush();
			}
		}
	}
}
