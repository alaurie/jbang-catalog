///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS info.picocli:picocli:4.7.7
//SOURCES Reach.java

package reach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import picocli.CommandLine;

public class ReachTest {

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
			var app = new Reach();
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
		assertTrue(result.stdout().contains("Network diagnostic CLI utility"));
	}

	@Test
	void testVersion() {
		var result = runCommand("--version");
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("reach"));
	}

	@Test
	void testProbeOpenTcpPort() throws Exception {
		try (var serverSocket = new ServerSocket()) {
			serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
			int port = serverSocket.getLocalPort();

			var result = runCommand("127.0.0.1", String.valueOf(port), "-n", "1", "-t", "500", "-i", "10");
			assertEquals(0, result.exitCode());
			assertTrue(result.stdout().contains("Connected to 127.0.0.1:" + port));
		}
	}

	@Test
	void testProbeClosedTcpPort() throws Exception {
		int unusedPort;
		try (var socket = new ServerSocket(0)) {
			unusedPort = socket.getLocalPort();
		} // Socket closed now

		var result = runCommand("127.0.0.1", String.valueOf(unusedPort), "-n", "1", "-t", "200", "-i", "10");
		// When port is closed/unreachable, exit code should indicate failure (1)
		assertEquals(1, result.exitCode());
		assertTrue(result.stdout().contains("refused") || result.stdout().contains("timeout")
				|| result.stdout().contains("100.0% packet loss"));
	}

	@Test
	void testJsonOutput() throws Exception {
		try (var serverSocket = new ServerSocket()) {
			serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
			int port = serverSocket.getLocalPort();

			var result = runCommand("127.0.0.1", String.valueOf(port), "-n", "1", "-j", "-t", "500");
			assertEquals(0, result.exitCode());
			assertTrue(result.stdout().contains("\"host\": \"127.0.0.1\"")
					|| result.stdout().contains("\"host\":\"127.0.0.1\"")
					|| result.stdout().contains("127.0.0.1"));
			assertTrue(result.stdout().contains("\"port\": " + port)
					|| result.stdout().contains("\"port\":" + port));
		}
	}

	@Test
	void testJsonNumbersAreValidInCommaDecimalLocale() throws Exception {
		var originalLocale = Locale.getDefault();
		try (var serverSocket = new ServerSocket(0)) {
			Locale.setDefault(Locale.GERMANY);
			var result = runCommand("127.0.0.1", String.valueOf(serverSocket.getLocalPort()), "-n", "1",
					"-j", "-t", "500");
			assertEquals(0, result.exitCode());
			assertTrue(result.stdout().matches("(?s).*\"dns_time_ms\": [0-9]+\\.[0-9]{2},.*"));
			assertTrue(result.stdout().matches("(?s).*\"loss_percent\": [0-9]+\\.[0-9],.*"));
			assertFalse(result.stdout().contains("\"rtt_avg_ms\": 0,"));
		} finally {
			Locale.setDefault(originalLocale);
		}
	}

	@Test
	void testFailedTlsHandshakeDoesNotTriggerExpiryWarningOrHang() throws Exception {
		try (var serverSocket = new ServerSocket(0)) {
			var peer = Thread.ofVirtual().start(() -> {
				try (var accepted = serverSocket.accept()) {
					Thread.sleep(java.time.Duration.ofMillis(1500));
				} catch (Exception _) {
				}
			});
			var start = System.nanoTime();
			var result = runCommand("127.0.0.1", String.valueOf(serverSocket.getLocalPort()), "-n", "1",
					"-s", "--warn-days", "30", "-t", "200");
			assertEquals(0, result.exitCode(), result.stderr());
			assertTrue(result.stdout().contains("Certificate Failed"));
			assertFalse(result.stderr().contains("certificates expire"));
			assertTrue((System.nanoTime() - start) < 1_000_000_000L, "TLS handshake exceeded timeout");
			peer.join();
		}
	}

	@Test
	void testHostWithEmbeddedPort() throws Exception {
		try (var serverSocket = new ServerSocket()) {
			serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
			int port = serverSocket.getLocalPort();

			var result = runCommand("127.0.0.1:" + port, "-n", "1", "-t", "500");
			assertEquals(0, result.exitCode());
			assertTrue(result.stdout().contains("Connected to 127.0.0.1:" + port));
		}
	}

	@Test
	void testIpv6LiteralAndBracketedPort() throws Exception {
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), 0), 0);
		} catch (java.io.IOException e) {
			assumeTrue(false, "IPv6 loopback is unavailable: " + e.getMessage());
			return;
		}
		try {
			server.createContext("/", exchange -> {
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
			});
			server.start();
			var port = String.valueOf(server.getAddress().getPort());
			var plain = runCommand("::1", port, "-6", "-n", "1", "-t", "500", "-H");
			assertEquals(0, plain.exitCode(), plain.stderr());
			assertTrue(plain.stdout().contains("HTTP: Port " + port + " -> 200"));

			var bracketed = runCommand("[::1]:" + port, "-6", "-n", "1", "-t", "500");
			assertEquals(0, bracketed.exitCode(), bracketed.stderr());
			assertTrue(bracketed.stdout().contains("Connected to"));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void testPortRangeAndList() throws Exception {
		try (var s1 = new ServerSocket(); var s2 = new ServerSocket()) {
			s1.bind(new InetSocketAddress("127.0.0.1", 0));
			s2.bind(new InetSocketAddress("127.0.0.1", 0));
			int p1 = s1.getLocalPort();
			int p2 = s2.getLocalPort();

			var result = runCommand("127.0.0.1", p1 + "," + p2, "-n", "1", "-t", "500");
			assertTrue(result.stdout().contains(":" + p1 + ":"));
			assertTrue(result.stdout().contains(":" + p2 + ":"));
		}
	}

	@Test
	void testUnresolvableHost() {
		var result = runCommand("definitely.invalid.hostname.nonexistent.fake", "80");
		assertEquals(1, result.exitCode());
		assertTrue(result.stderr().contains("Could not resolve hostname"));
	}

	@Test
	void testInvalidPortSpec() {
		var result = runCommand("127.0.0.1", "invalid_not_a_port");
		assertEquals(1, result.exitCode());
		assertTrue(result.stderr().contains("No valid ports specified"));
	}

	public static void main(String... args) {
		var launcher = LauncherFactory.create();
		var summaryListener = new SummaryGeneratingListener();
		var request = LauncherDiscoveryRequestBuilder.request()
			.selectors(DiscoverySelectors.selectClass(ReachTest.class))
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
	}
}
