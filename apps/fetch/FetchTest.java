///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS info.picocli:picocli:4.7.7
//SOURCES Fetch.java

package fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import picocli.CommandLine;

public class FetchTest {

	private record ExecutionResult(int exitCode, String stdout, String stderr) {
	}

	private ExecutionResult runCommand(String... args) {
		var originalOut = System.out;
		var originalErr = System.err;
		var outStream = new ByteArrayOutputStream();
		var errStream = new ByteArrayOutputStream();
		var printOut = new PrintStream(outStream, true, StandardCharsets.UTF_8);
		var printErr = new PrintStream(errStream, true, StandardCharsets.UTF_8);
		var sw = new StringWriter();
		var pw = new PrintWriter(sw);

		try {
			System.setOut(printOut);
			System.setErr(printErr);
			var app = new Fetch();
			var cmd = new CommandLine(app);
			cmd.setOut(pw);
			cmd.setErr(pw);
			int exitCode = cmd.execute(args);
			pw.flush();
			return new ExecutionResult(exitCode,
					outStream.toString(StandardCharsets.UTF_8) + sw.toString(),
					errStream.toString(StandardCharsets.UTF_8));
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
	}

	@Test
	void testHelp() {
		var result = runCommand("--help");
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("High-performance multi-threaded CLI file downloader"));
	}

	@Test
	void testVersion() {
		var result = runCommand("--version");
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("fetch"));
	}

	@Test
	void testDownloadFileWithExplicitHashVerification(@TempDir Path tempDir) throws Exception {
		byte[] testData = "Sample data for Fetch download and checksum test".getBytes(StandardCharsets.UTF_8);
		var md = MessageDigest.getInstance("SHA-256");
		String sha256Hex = HexFormat.of().formatHex(md.digest(testData));

		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/download.dat", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
			exchange.sendResponseHeaders(200, testData.length);
			try (var os = exchange.getResponseBody()) {
				os.write(testData);
			}
		});
		server.start();

		int port = server.getAddress().getPort();
		Path destFile = tempDir.resolve("downloaded.dat");

		try {
			var result = runCommand("http://127.0.0.1:" + port + "/download.dat", "-o",
					destFile.toString(), "--expected-hash", sha256Hex);
			assertEquals(0, result.exitCode());
			assertTrue(Files.exists(destFile));
			assertEquals("Sample data for Fetch download and checksum test", Files.readString(destFile));
			assertTrue(result.stdout().contains("SHA-256") || result.stdout().contains("OK"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testDownloadFileWithAutoChecksumManifestDiscovery(@TempDir Path tempDir) throws Exception {
		byte[] fileData = "File payload for auto checksum probe".getBytes(StandardCharsets.UTF_8);
		var md = MessageDigest.getInstance("SHA-256");
		String sha256Hex = HexFormat.of().formatHex(md.digest(fileData));
		String manifestContent = sha256Hex + "  pkg.tar.gz\n";

		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/pkg.tar.gz", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
			exchange.sendResponseHeaders(200, fileData.length);
			try (var os = exchange.getResponseBody()) {
				os.write(fileData);
			}
		});
		server.createContext("/SHA256SUMS", exchange -> {
			byte[] manifestBytes = manifestContent.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/plain");
			exchange.sendResponseHeaders(200, manifestBytes.length);
			try (var os = exchange.getResponseBody()) {
				os.write(manifestBytes);
			}
		});
		server.start();

		int port = server.getAddress().getPort();
		Path destFile = tempDir.resolve("pkg.tar.gz");

		try {
			var result = runCommand("http://127.0.0.1:" + port + "/pkg.tar.gz", "-o", destFile.toString());
			assertEquals(0, result.exitCode());
			assertTrue(Files.exists(destFile));
			assertEquals("File payload for auto checksum probe", Files.readString(destFile));
			assertTrue(result.stdout().contains("Found manifest")
					|| result.stdout().contains("SHA256SUMS") || result.stdout().contains("OK"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testOversizedManifestFallsThroughToValidChecksum(@TempDir Path tempDir) throws Exception {
		byte[] payload = "trusted payload".getBytes(StandardCharsets.UTF_8);
		byte[] huge = ("#" + "x".repeat(65536) + "\n" + "0".repeat(128) + "  payload.dat\n")
			.getBytes(StandardCharsets.UTF_8);
		byte[] valid = (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))
				+ "  payload.dat\n")
			.getBytes(StandardCharsets.UTF_8);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/payload.dat", exchange -> {
			exchange.sendResponseHeaders(200, payload.length);
			try (var out = exchange.getResponseBody()) {
				out.write(payload);
			}
		});
		server.createContext("/SHA512SUMS", exchange -> {
			exchange.sendResponseHeaders(200, huge.length);
			try (var out = exchange.getResponseBody()) {
				out.write(huge);
			}
		});
		server.createContext("/SHA256SUMS", exchange -> {
			exchange.sendResponseHeaders(200, valid.length);
			try (var out = exchange.getResponseBody()) {
				out.write(valid);
			}
		});
		server.start();
		try {
			Path dest = tempDir.resolve("payload.dat");
			var result = runCommand("http://127.0.0.1:" + server.getAddress().getPort() + "/payload.dat",
					"-o", dest.toString());
			assertEquals(0, result.exitCode(), result.stderr());
			assertEquals("trusted payload", Files.readString(dest));
			assertTrue(result.stdout().contains("SHA256SUMS"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testSlowManifestFallsThroughToValidChecksum(@TempDir Path tempDir) throws Exception {
		byte[] payload = "trusted payload".getBytes(StandardCharsets.UTF_8);
		byte[] bad = ("0".repeat(128) + "  payload.dat\n").getBytes(StandardCharsets.UTF_8);
		byte[] valid = (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))
				+ "  payload.dat\n")
			.getBytes(StandardCharsets.UTF_8);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/payload.dat", exchange -> {
			exchange.sendResponseHeaders(200, payload.length);
			try (var out = exchange.getResponseBody()) {
				out.write(payload);
			}
		});
		server.createContext("/SHA512SUMS", exchange -> {
			exchange.sendResponseHeaders(200, bad.length);
			try (var out = exchange.getResponseBody()) {
				Thread.sleep(java.time.Duration.ofSeconds(4));
				out.write(bad);
			} catch (InterruptedException _) {
				Thread.currentThread().interrupt();
			}
		});
		server.createContext("/SHA256SUMS", exchange -> {
			exchange.sendResponseHeaders(200, valid.length);
			try (var out = exchange.getResponseBody()) {
				out.write(valid);
			}
		});
		server.start();
		try {
			Path dest = tempDir.resolve("payload.dat");
			var result = runCommand("http://127.0.0.1:" + server.getAddress().getPort() + "/payload.dat",
					"-o", dest.toString());
			assertEquals(0, result.exitCode(), result.stderr());
			assertEquals("trusted payload", Files.readString(dest));
			assertTrue(result.stdout().contains("SHA256SUMS"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testQuietMode(@TempDir Path tempDir) throws Exception {
		byte[] testData = "Quiet download payload".getBytes(StandardCharsets.UTF_8);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/quiet.dat", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
			exchange.sendResponseHeaders(200, testData.length);
			try (var os = exchange.getResponseBody()) {
				os.write(testData);
			}
		});
		server.start();

		int port = server.getAddress().getPort();
		Path destFile = tempDir.resolve("quiet.dat");

		try {
			var result = runCommand("http://127.0.0.1:" + port + "/quiet.dat", "-o", destFile.toString(),
					"-q", "--no-checksum");
			assertEquals(0, result.exitCode());
			assertTrue(Files.exists(destFile));
			assertEquals("Quiet download payload", Files.readString(destFile));
			assertEquals("", result.stdout().trim());
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testConcurrentChunkDownloadWithRangeSupport(@TempDir Path tempDir) throws Exception {
		byte[] testData = new byte[256 * 1024];
		for (int i = 0; i < testData.length; i++) {
			testData[i] = (byte) (i % 251);
		}
		var md = MessageDigest.getInstance("SHA-256");
		String sha256Hex = HexFormat.of().formatHex(md.digest(testData));

		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/chunks.dat", new RangeHttpHandler(testData, 0));
		server.start();

		int port = server.getAddress().getPort();
		Path destFile = tempDir.resolve("chunks.dat");

		try {
			var result = runCommand("http://127.0.0.1:" + port + "/chunks.dat", "-o", destFile.toString(),
					"-c", "4", "--expected-hash", sha256Hex);
			assertEquals(0, result.exitCode());
			assertTrue(Files.exists(destFile));
			assertEquals(testData.length, Files.size(destFile));
			var actualMd = MessageDigest.getInstance("SHA-256");
			String actualHex = HexFormat.of().formatHex(actualMd.digest(Files.readAllBytes(destFile)));
			assertEquals(sha256Hex, actualHex);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testResumeInterruptedConcurrentDownload(@TempDir Path tempDir) throws Exception {
		byte[] testData = new byte[1024 * 1024];
		for (int i = 0; i < testData.length; i++) {
			testData[i] = (byte) (i % 241);
		}
		var md = MessageDigest.getInstance("SHA-256");
		String sha256Hex = HexFormat.of().formatHex(md.digest(testData));

		Path destFile = tempDir.resolve("resumable.dat");
		Path partFile = Path.of(destFile.toString() + ".part");
		Path metaFile = Path.of(destFile.toString() + ".part.meta");

		// Run 1: Server aborts on request #3 (one of the workers)
		var failServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		failServer.createContext("/resumable.dat", new RangeHttpHandler(testData, 3));
		failServer.start();
		int port1 = failServer.getAddress().getPort();

		try {
			var result1 = runCommand("http://127.0.0.1:" + port1 + "/resumable.dat", "-o",
					destFile.toString(), "-c", "4", "--expected-hash", sha256Hex);
			assertTrue(result1.exitCode() != 0, "First attempt should fail/interrupt");
			assertTrue(Files.exists(partFile), ".part file must exist after interruption");
			assertTrue(Files.exists(metaFile), ".part.meta file must exist after interruption");
		} finally {
			failServer.stop(0);
		}

		// Run 2: Server succeeds on all requests -> should resume and verify successfully
		var goodServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port1), 0);
		goodServer.createContext("/resumable.dat", new RangeHttpHandler(testData, 0));
		goodServer.start();

		try {
			var result2 = runCommand("http://127.0.0.1:" + port1 + "/resumable.dat", "-o",
					destFile.toString(), "-c", "4", "--expected-hash", sha256Hex);
			assertEquals(0, result2.exitCode(), "Resumed download should succeed: " + result2.stderr());
			assertTrue(result2.stdout().contains("Resuming download"),
					"Output should indicate resumption: " + result2.stdout());
			assertTrue(Files.exists(destFile), "Target file should exist");
			assertFalse(Files.exists(partFile), ".part should be removed after completion");
			assertFalse(Files.exists(metaFile), ".part.meta should be removed after completion");

			assertEquals(sha256Hex, sha256Of(destFile),
					"Final file checksum must match after resumption");
		} finally {
			goodServer.stop(0);
		}
	}

	@Test
	void testMultiInterruptedConcurrentDownload(@TempDir Path tempDir) throws Exception {
		byte[] testData = new byte[1024 * 1024];
		for (int i = 0; i < testData.length; i++) {
			testData[i] = (byte) (i % 239);
		}
		var md = MessageDigest.getInstance("SHA-256");
		String sha256Hex = HexFormat.of().formatHex(md.digest(testData));

		Path destFile = tempDir.resolve("multi-resumable.dat");
		Path partFile = Path.of(destFile.toString() + ".part");
		Path metaFile = Path.of(destFile.toString() + ".part.meta");

		// Run 1: Fails on request #2
		var server1 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server1.createContext("/multi.dat", new RangeHttpHandler(testData, 2));
		server1.start();
		int port = server1.getAddress().getPort();

		try {
			var result1 = runCommand("http://127.0.0.1:" + port + "/multi.dat", "-o", destFile.toString(),
					"-c", "4", "--expected-hash", sha256Hex);
			assertTrue(result1.exitCode() != 0, "Run 1 should fail");
			assertTrue(Files.exists(partFile));
			assertTrue(Files.exists(metaFile));
		} finally {
			server1.stop(0);
		}

		// Run 2: Fails on request #2 (the resumed incomplete worker)
		var server2 = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server2.createContext("/multi.dat", new RangeHttpHandler(testData, 2));
		server2.start();

		try {
			var result2 = runCommand("http://127.0.0.1:" + port + "/multi.dat", "-o", destFile.toString(),
					"-c", "4", "--expected-hash", sha256Hex);
			assertTrue(result2.exitCode() != 0, "Run 2 should fail");
			assertTrue(Files.exists(partFile));
			assertTrue(Files.exists(metaFile));
		} finally {
			server2.stop(0);
		}

		// Run 3: Finishes cleanly
		var server3 = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server3.createContext("/multi.dat", new RangeHttpHandler(testData, 0));
		server3.start();

		try {
			var result3 = runCommand("http://127.0.0.1:" + port + "/multi.dat", "-o", destFile.toString(),
					"-c", "4", "--expected-hash", sha256Hex);
			assertEquals(0, result3.exitCode(), "Run 3 should complete: " + result3.stderr());
			assertTrue(result3.stdout().contains("Resuming download"));
			assertTrue(Files.exists(destFile));
			assertFalse(Files.exists(partFile));
			assertFalse(Files.exists(metaFile));

			assertEquals(sha256Hex, sha256Of(destFile),
					"Final hash must match perfectly after multiple interruptions");
		} finally {
			server3.stop(0);
		}
	}

	@Test
	void testStalePartWithoutMetaRestartsFresh(@TempDir Path tempDir) throws Exception {
		byte[] testData = new byte[64 * 1024];
		for (int i = 0; i < testData.length; i++) {
			testData[i] = (byte) (i % 199);
		}
		var md = MessageDigest.getInstance("SHA-256");
		String sha256Hex = HexFormat.of().formatHex(md.digest(testData));

		Path destFile = tempDir.resolve("orphan.dat");
		Path partFile = Path.of(destFile.toString() + ".part");
		// Create corrupted orphan .part with junk data and no .meta
		Files.write(partFile, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });

		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/orphan.dat", new RangeHttpHandler(testData, 0));
		server.start();
		int port = server.getAddress().getPort();

		try {
			var result = runCommand("http://127.0.0.1:" + port + "/orphan.dat", "-o", destFile.toString(),
					"-c", "2", "--expected-hash", sha256Hex);
			assertEquals(0, result.exitCode());
			assertTrue(result.stdout().contains("lacks resume metadata. Starting fresh"),
					"Should detect missing metadata and restart: " + result.stdout());
			assertTrue(Files.exists(destFile));
			var actualMd = MessageDigest.getInstance("SHA-256");
			String actualHex = HexFormat.of().formatHex(actualMd.digest(Files.readAllBytes(destFile)));
			assertEquals(sha256Hex, actualHex);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testCustomHeadersAndUserAgent(@TempDir Path tempDir) throws Exception {
		byte[] testData = "Secure payload requiring custom header".getBytes(StandardCharsets.UTF_8);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/auth-file.txt", exchange -> {
			String authHeader = exchange.getRequestHeaders().getFirst("X-Custom-Auth");
			String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
			if ("SecretToken123".equals(authHeader) && "TestFetcher/1.0".equals(userAgent)) {
				exchange.sendResponseHeaders(200, testData.length);
				try (var os = exchange.getResponseBody()) {
					os.write(testData);
				}
			} else {
				exchange.sendResponseHeaders(403, -1);
			}
		});
		server.start();

		try {
			int port = server.getAddress().getPort();
			Path destFile = tempDir.resolve("auth-file.txt");
			var result = runCommand("http://127.0.0.1:" + port + "/auth-file.txt", "-o", destFile.toString(), "-H",
					"X-Custom-Auth: SecretToken123", "-A", "TestFetcher/1.0", "--no-checksum");
			assertEquals(0, result.exitCode(),
					"Download should succeed with valid auth headers: " + result.stderr());
			assertEquals("Secure payload requiring custom header", Files.readString(destFile));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testExplicitHashMismatchFailure(@TempDir Path tempDir) throws Exception {
		byte[] testData = "Payload with wrong hash verification".getBytes(StandardCharsets.UTF_8);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/wrong-hash.txt", exchange -> {
			exchange.sendResponseHeaders(200, testData.length);
			try (var os = exchange.getResponseBody()) {
				os.write(testData);
			}
		});
		server.start();

		try {
			int port = server.getAddress().getPort();
			Path destFile = tempDir.resolve("wrong-hash.txt");
			String wrongHash = "0000000000000000000000000000000000000000000000000000000000000000";
			var result = runCommand("http://127.0.0.1:" + port + "/wrong-hash.txt", "-o",
					destFile.toString(), "--expected-hash", wrongHash);
			assertEquals(1, result.exitCode(), "Download should fail on hash mismatch");
			assertTrue(result.stdout().contains("FAILED") || result.stderr().contains("Expected:"));
			assertFalse(Files.exists(destFile), "A failed checksum must not publish the .part file");
			assertEquals("Payload with wrong hash verification",
					Files.readString(Path.of(destFile + ".part")));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testFailedRangedChecksumKeepsDestinationAndResumablePart(@TempDir Path tempDir)
			throws Exception {
		byte[] payload = "Verified new payload".getBytes(StandardCharsets.UTF_8);
		Path destFile = tempDir.resolve("replacement.dat");
		Path partFile = Path.of(destFile + ".part");
		Path metaFile = Path.of(partFile + ".meta");
		Files.writeString(destFile, "Original destination");
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/replacement.dat", new RangeHttpHandler(payload, 0));
		server.start();

		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/replacement.dat";
			var rejected = runCommand(url, "-o", destFile.toString(), "--expected-hash", "0".repeat(64));
			assertEquals(1, rejected.exitCode());
			assertEquals("Original destination", Files.readString(destFile));
			assertEquals("Verified new payload", Files.readString(partFile));
			assertTrue(Files.exists(metaFile),
					"Completed ranged download metadata survives failed verification");

			var accepted = runCommand(url, "-o", destFile.toString(), "--expected-hash",
					HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
			assertEquals(0, accepted.exitCode(), accepted.stderr());
			assertEquals("Verified new payload", Files.readString(destFile));
			assertFalse(Files.exists(partFile));
			assertFalse(Files.exists(metaFile));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testRejectsMismatchedContentRangesBeforeWriting(@TempDir Path tempDir) throws Exception {
		byte[] payload = "abcdefgh".getBytes(StandardCharsets.UTF_8);
		for (String badRange : new String[] { null, "bytes 1-7/8", "bytes 0-6/8", "bytes 0-7/9",
				"bytes */8", "bytes 999999999999999999999-7/8" }) {
			var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/ranges.dat", exchange -> {
				if ("HEAD".equals(exchange.getRequestMethod())) {
					exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
					exchange.getResponseHeaders().set("Content-Length", String.valueOf(payload.length));
					exchange.getResponseHeaders().set("ETag", "\"range-test\"");
					exchange.sendResponseHeaders(200, -1);
					exchange.close();
					return;
				}
				exchange.getResponseHeaders().set("ETag", "\"range-test\"");
				if (badRange != null) {
					exchange.getResponseHeaders().set("Content-Range", badRange);
				}
				exchange.sendResponseHeaders(206, payload.length);
				try (var out = exchange.getResponseBody()) {
					out.write(payload);
				}
			});
			server.start();
			try {
				Path destFile = tempDir.resolve("ranges.dat");
				Files.writeString(destFile, "original");
				var result = runCommand("http://127.0.0.1:" + server.getAddress().getPort() + "/ranges.dat",
						"-o", destFile.toString(), "-c", "1", "--no-checksum", "--no-resume");
				assertEquals(1, result.exitCode(), String.valueOf(badRange));
				assertTrue(result.stderr().contains("Invalid Content-Range"), String.valueOf(badRange));
				assertEquals("original", Files.readString(destFile), String.valueOf(badRange));
				assertEquals(0, Files.size(Path.of(destFile + ".part")), String.valueOf(badRange));
				assertTrue(Files.exists(Path.of(destFile + ".part.meta")), String.valueOf(badRange));
			} finally {
				server.stop(0);
			}
		}
	}

	@Test
	void testHttp404NotFound(@TempDir Path tempDir) throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/not-found.txt", exchange -> exchange.sendResponseHeaders(404, -1));
		server.start();

		try {
			int port = server.getAddress().getPort();
			Path destFile = tempDir.resolve("not-found.txt");
			var result = runCommand("http://127.0.0.1:" + port + "/not-found.txt", "-o",
					destFile.toString(), "--no-checksum");
			assertEquals(1, result.exitCode());
			assertTrue(result.stderr().contains("404") || result.stdout().contains("404"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testAutoProbeReportsMissingManifest(@TempDir Path tempDir) throws Exception {
		byte[] payload = "unverified payload".getBytes(StandardCharsets.UTF_8);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			if (!"/payload.dat".equals(exchange.getRequestURI().getPath())) {
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
				return;
			}
			if ("HEAD".equals(exchange.getRequestMethod())) {
				exchange.getResponseHeaders().set("Content-Length", String.valueOf(payload.length));
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
				return;
			}
			exchange.sendResponseHeaders(200, payload.length);
			try (var out = exchange.getResponseBody()) {
				out.write(payload);
			}
		});
		server.start();
		try {
			Path destination = tempDir.resolve("payload.dat");
			var result = runCommand("http://127.0.0.1:" + server.getAddress().getPort() + "/payload.dat",
					"-o", destination.toString());
			assertEquals(0, result.exitCode(), result.stderr());
			assertEquals("unverified payload", Files.readString(destination));
			assertTrue(result.stdout().contains("No matching checksum manifest detected"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testResumeRequiresMatchingStrongEtag(@TempDir Path tempDir) throws Exception {
		for (String headEtag : new String[] { "", "W/\"old\"", "\"new\"", "\"old\"" }) {
			var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			var requestedRange = new java.util.concurrent.atomic.AtomicReference<String>();
			var ifMatch = new java.util.concurrent.atomic.AtomicReference<String>();
			byte[] payload = "abcdefgh".getBytes(StandardCharsets.UTF_8);
			server.createContext("/entity.dat", exchange -> {
				exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
				exchange.getResponseHeaders().set("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT");
				if (!headEtag.isEmpty()) {
					exchange.getResponseHeaders().set("ETag", headEtag);
				}
				if ("HEAD".equals(exchange.getRequestMethod())) {
					exchange.getResponseHeaders().set("Content-Length", String.valueOf(payload.length));
					exchange.sendResponseHeaders(200, -1);
					exchange.close();
					return;
				}
				String range = exchange.getRequestHeaders().getFirst("Range");
				requestedRange.set(range);
				ifMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
				int start = range == null ? 0 : Integer.parseInt(range.substring(6, range.indexOf('-')));
				if (range != null) {
					exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-7/8");
				}
				exchange.sendResponseHeaders(range == null ? 200 : 206, payload.length - start);
				try (var out = exchange.getResponseBody()) {
					out.write(payload, start, payload.length - start);
				}
			});
			server.start();
			try {
				String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/entity.dat";
				Path destination = tempDir.resolve("entity.dat");
				Files.writeString(Path.of(destination + ".part"), headEtag.equals("\"old\"") ? "abcd" : "OLD!");
				Files.writeString(Path.of(destination + ".part.meta"),
						"version=1\nuri=" + url
								+ "\ncontentLength=8\netag=\"old\"\nlastModified=Wed, 21 Oct 2015 07:28:00 GMT"
								+ "\nchunks=1\nchunk=0,0,7,4\n");
				var result = runCommand(url, "-o", destination.toString(), "-c", "1", "--no-checksum");
				assertEquals(0, result.exitCode(), headEtag + ": " + result.stderr());
				assertEquals("abcdefgh", Files.readString(destination), headEtag);
				if ("\"old\"".equals(headEtag)) {
					assertTrue(result.stdout().contains("Resuming download"));
					assertEquals("bytes=4-7", requestedRange.get());
					assertEquals("\"old\"", ifMatch.get());
				} else {
					assertFalse(result.stdout().contains("Resuming download"), headEtag);
					if ("\"new\"".equals(headEtag)) {
						assertEquals("bytes=0-7", requestedRange.get());
						assertEquals("\"new\"", ifMatch.get());
					} else {
						assertEquals(null, requestedRange.get(), headEtag);
					}
				}
			} finally {
				server.stop(0);
			}
		}
	}

	@Test
	void testRangedEntityChangeIsRejectedBeforeWriting(@TempDir Path tempDir) throws Exception {
		Path destination = tempDir.resolve("entity.dat");
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/entity.dat", exchange -> {
			exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
			exchange.getResponseHeaders().set("Content-Length", "8");
			if ("HEAD".equals(exchange.getRequestMethod())) {
				exchange.getResponseHeaders().set("ETag", "\"before\"");
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
				return;
			}
			exchange.getResponseHeaders().set("ETag", "\"after\"");
			exchange.getResponseHeaders().set("Content-Range", "bytes 0-7/8");
			exchange.sendResponseHeaders(206, 8);
			try (var out = exchange.getResponseBody()) {
				out.write("new data".getBytes(StandardCharsets.UTF_8));
			}
		});
		server.start();
		try {
			Files.writeString(destination, "original");
			var result = runCommand("http://127.0.0.1:" + server.getAddress().getPort() + "/entity.dat",
					"-o", destination.toString(), "-c", "1", "--no-checksum");
			assertEquals(1, result.exitCode());
			assertTrue(result.stderr().contains("ETag changed"));
			assertEquals("original", Files.readString(destination));
			assertEquals(0, Files.size(Path.of(destination + ".part")));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testLargeRangedTransferSpansSeveralBufferReads(@TempDir Path tempDir) throws Exception {
		byte[] payload = new byte[512 * 1024 + 17];
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) (i % 251);
		}
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/large.dat", new RangeHttpHandler(payload, 0));
		server.start();
		try {
			Path destination = tempDir.resolve("large.dat");
			var result = runCommand("http://127.0.0.1:" + server.getAddress().getPort() + "/large.dat",
					"-o", destination.toString(), "-c", "1", "--no-checksum");
			assertEquals(0, result.exitCode(), result.stderr());
			assertTrue(java.util.Arrays.equals(payload, Files.readAllBytes(destination)));
		} finally {
			server.stop(0);
		}
	}

	private static String sha256Of(Path file) throws Exception {
		var md = MessageDigest.getInstance("SHA-256");
		try (var in = Files.newInputStream(file)) {
			byte[] buf = new byte[64 * 1024];
			int read;
			while ((read = in.read(buf)) != -1) {
				md.update(buf, 0, read);
			}
		}
		return HexFormat.of().formatHex(md.digest());
	}

	static class RangeHttpHandler implements com.sun.net.httpserver.HttpHandler {
		private final byte[] data;
		private final java.util.concurrent.atomic.AtomicInteger requestCount = new java.util.concurrent.atomic.AtomicInteger();
		private final int failOnRequestNumber;

		RangeHttpHandler(byte[] data, int failOnRequestNumber) {
			this.data = data;
			this.failOnRequestNumber = failOnRequestNumber;
		}

		@Override
		public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
			int count = requestCount.incrementAndGet();
			if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
				exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
				exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
				exchange.getResponseHeaders().set("ETag", "\"test-etag-123\"");
				exchange.getResponseHeaders().set("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT");
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
				return;
			}

			String range = exchange.getRequestHeaders().getFirst("Range");
			if (range != null && range.startsWith("bytes=")) {
				String[] parts = range.substring(6).split("-");
				int start = Integer.parseInt(parts[0]);
				int end = parts.length > 1 && !parts[1].isBlank() ? Integer.parseInt(parts[1]) : data.length - 1;
				end = Math.min(end, data.length - 1);
				int length = end - start + 1;

				if (failOnRequestNumber > 0 && count == failOnRequestNumber) {
					int partial = Math.max(1, length / 4);
					exchange.getResponseHeaders()
						.set("Content-Range",
								"bytes " + start + "-" + end + "/" + data.length);
					exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
					exchange.getResponseHeaders().set("ETag", "\"test-etag-123\"");
					exchange.sendResponseHeaders(206, length);
					try (var os = exchange.getResponseBody()) {
						os.write(data, start, partial);
						os.flush();
					}
					return;
				}

				exchange.getResponseHeaders()
					.set("Content-Range",
							"bytes " + start + "-" + end + "/" + data.length);
				exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
				exchange.getResponseHeaders().set("ETag", "\"test-etag-123\"");
				exchange.sendResponseHeaders(206, length);
				try (var os = exchange.getResponseBody()) {
					os.write(data, start, length);
				}
				return;
			}

			exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
			exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
			exchange.sendResponseHeaders(200, data.length);
			try (var os = exchange.getResponseBody()) {
				os.write(data);
			}
		}
	}

	public static void main(String... args) {
		var launcher = LauncherFactory.create();
		var summaryListener = new SummaryGeneratingListener();
		var request = LauncherDiscoveryRequestBuilder.request()
			.selectors(DiscoverySelectors.selectClass(FetchTest.class))
			.build();
		launcher.execute(request, summaryListener);

		var summary = summaryListener.getSummary();
		System.out.printf("Tests run: %d, Failures: %d, Errors: %d, Skipped: %d%n",
				summary.getTestsFoundCount(), summary.getTestsFailedCount(),
				summary.getContainersFailedCount(), summary.getTestsSkippedCount());

		if (summary.getTestsFailedCount() > 0 || summary.getContainersFailedCount() > 0) {
			summary.printFailuresTo(new PrintWriter(System.err));
			System.exit(1);
		}
		System.exit(0);
	}
}
