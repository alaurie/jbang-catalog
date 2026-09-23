///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS info.picocli:picocli:4.7.7
//SOURCES Serve.java

package serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import picocli.CommandLine;

public class ServeTest {

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
			var app = new Serve();
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

	private int findFreePort() throws Exception {
		try (var socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	@Test
	void testHelp() {
		var result = runCommand("--help");
		assertEquals(0, result.exitCode());
		assertTrue(
				result.stdout().contains("Simple HTTP file server inspired by python -m http.server"));
	}

	@Test
	void testVersion() {
		var result = runCommand("--version");
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("serve"));
	}

	@Test
	void testInvalidDirectory() {
		var result = runCommand("-d", "/nonexistent_directory_xyz_123");
		assertEquals(1, result.exitCode());
		assertTrue(result.stderr().contains("does not exist"));
	}

	@Test
	void testServeFileAndDirectoryListing(@TempDir Path tempDir) throws Exception {
		Path fileA = tempDir.resolve("hello.txt");
		Files.writeString(fileA, "Hello World Content");

		int port = findFreePort();
		var app = new Serve();
		var executor = Executors.newSingleThreadExecutor();
		Future<Integer> serverFuture = executor.submit(() -> {
			var cmd = new CommandLine(app);
			return cmd.execute("-d", tempDir.toString(), "-p", String.valueOf(port), "-b", "127.0.0.1");
		});

		try {
			var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

			// Wait briefly for server startup
			Thread.sleep(300);

			// 1. Test directory listing
			var dirReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build();
			var dirResp = client.send(dirReq, HttpResponse.BodyHandlers.ofString());
			assertEquals(200, dirResp.statusCode());
			assertTrue(dirResp.body().contains("hello.txt"));

			// 2. Test file retrieval
			var fileReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello.txt"))
				.GET()
				.build();
			var fileResp = client.send(fileReq, HttpResponse.BodyHandlers.ofString());
			assertEquals(200, fileResp.statusCode());
			assertEquals("Hello World Content", fileResp.body());
		} finally {
			app.stop();
			serverFuture.cancel(true);
			executor.shutdownNow();
		}
	}

	@Test
	void testBasicAuthentication(@TempDir Path tempDir) throws Exception {
		Path fileA = tempDir.resolve("secret.txt");
		Files.writeString(fileA, "Top Secret Data");

		int port = findFreePort();
		var app = new Serve();
		var executor = Executors.newSingleThreadExecutor();
		Future<Integer> serverFuture = executor.submit(() -> {
			var cmd = new CommandLine(app);
			return cmd.execute("-d", tempDir.toString(), "-p", String.valueOf(port), "-b", "127.0.0.1",
					"--auth", "admin:supersecret");
		});

		try {
			var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
			Thread.sleep(300);

			// Unauthenticated request should be 401
			var unauthReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/secret.txt"))
				.GET()
				.build();
			var unauthResp = client.send(unauthReq, HttpResponse.BodyHandlers.ofString());
			assertEquals(401, unauthResp.statusCode());

			// Authenticated request should be 200
			var authHeader = "Basic " + Base64.getEncoder()
				.encodeToString("admin:supersecret".getBytes(StandardCharsets.UTF_8));
			var authReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/secret.txt"))
				.header("Authorization", authHeader)
				.GET()
				.build();
			var authResp = client.send(authReq, HttpResponse.BodyHandlers.ofString());
			assertEquals(200, authResp.statusCode());
			assertEquals("Top Secret Data", authResp.body());
		} finally {
			app.stop();
			serverFuture.cancel(true);
			executor.shutdownNow();
		}
	}

	@Test
	void testSinglePageApplicationMode(@TempDir Path tempDir) throws Exception {
		Path indexFile = tempDir.resolve("index.html");
		Files.writeString(indexFile, "<html><body>SPA Root</body></html>");

		int port = findFreePort();
		var app = new Serve();
		var executor = Executors.newSingleThreadExecutor();
		Future<Integer> serverFuture = executor.submit(() -> {
			var cmd = new CommandLine(app);
			return cmd.execute("-d", tempDir.toString(), "-p", String.valueOf(port), "-b", "127.0.0.1",
					"--spa");
		});

		try {
			var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
			Thread.sleep(300);

			// Non-existent route should fallback to index.html with 200 OK
			var spaReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/user/profile/settings"))
				.GET()
				.build();
			var spaResp = client.send(spaReq, HttpResponse.BodyHandlers.ofString());
			assertEquals(200, spaResp.statusCode());
			assertTrue(spaResp.body().contains("SPA Root"));
		} finally {
			app.stop();
			serverFuture.cancel(true);
			executor.shutdownNow();
		}
	}

	@Test
	void testForceDownloadMode(@TempDir Path tempDir) throws Exception {
		Path testFile = tempDir.resolve("download.pdf");
		Files.writeString(testFile, "%PDF-mock");

		int port = findFreePort();
		var app = new Serve();
		var executor = Executors.newSingleThreadExecutor();
		Future<Integer> serverFuture = executor.submit(() -> {
			var cmd = new CommandLine(app);
			return cmd.execute("-d", tempDir.toString(), "-p", String.valueOf(port), "-b", "127.0.0.1",
					"--download");
		});

		try {
			var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
			Thread.sleep(300);

			var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/download.pdf")).GET().build();
			var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
			assertEquals(200, resp.statusCode());
			var disposition = resp.headers().firstValue("Content-Disposition").orElse("");
			assertEquals("attachment", disposition);
		} finally {
			app.stop();
			serverFuture.cancel(true);
			executor.shutdownNow();
		}
	}

	@Test
	void testInvalidPortBoundaries() {
		var zero = runCommand("-p", "0");
		assertEquals(1, zero.exitCode());
		assertTrue(zero.stderr().contains("Invalid port"));

		var tooLarge = runCommand("-p", "65536");
		assertEquals(1, tooLarge.exitCode());
		assertTrue(tooLarge.stderr().contains("Invalid port"));
	}

	@Test
	void testPathIsNotDirectory(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("not_a_dir.txt");
		Files.writeString(file, "hello");

		var result = runCommand("-d", file.toString());
		assertEquals(1, result.exitCode());
		assertTrue(result.stderr().contains("is not a directory"));
	}

	public static void main(String... args) {
		var launcher = LauncherFactory.create();
		var summaryListener = new SummaryGeneratingListener();
		var request = LauncherDiscoveryRequestBuilder.request()
			.selectors(DiscoverySelectors.selectClass(ServeTest.class))
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
