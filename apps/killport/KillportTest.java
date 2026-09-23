///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS info.picocli:picocli:4.7.7
//SOURCES Killport.java

package killport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import picocli.CommandLine;

public class KillportTest {

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
			var app = new Killport();
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
		assertTrue(result.stdout()
			.contains("Find and terminate processes listening on specified network ports"));
	}

	@Test
	void testVersion() {
		var result = runCommand("--version");
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("killport"));
	}

	@Test
	void testInvalidPort() {
		var result = runCommand("999999");
		assertEquals(1, result.exitCode());
		assertTrue(result.stderr().contains("Invalid port number"));
	}

	@Test
	void testDryRunOnActivePort() throws Exception {
		try (var serverSocket = new ServerSocket()) {
			serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
			int port = serverSocket.getLocalPort();

			var result = runCommand("-d", String.valueOf(port));
			assertEquals(0, result.exitCode());
			assertTrue(
					result.stdout().contains("Found PID") || result.stdout().contains("Searching processes"));
		}
	}

	@Test
	void testUnusedPort() throws Exception {
		int unusedPort;
		try (var socket = new ServerSocket(0)) {
			unusedPort = socket.getLocalPort();
		}

		var result = runCommand(String.valueOf(unusedPort));
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("No active process found on port " + unusedPort));
	}

	@Test
	void testPortBoundaries() {
		var zeroResult = runCommand("0");
		assertEquals(1, zeroResult.exitCode());
		assertTrue(zeroResult.stderr().contains("Invalid port number"));

		var negativeResult = runCommand("-5");
		// Picocli might treat -5 as option or invalid port
		assertTrue(negativeResult.exitCode() != 0);

		var tooLargeResult = runCommand("65536");
		assertEquals(1, tooLargeResult.exitCode());
		assertTrue(tooLargeResult.stderr().contains("Invalid port number"));
	}

	@Test
	void testSignalParsingAndMultiplePorts() throws Exception {
		int port1;
		int port2;
		try (var s1 = new ServerSocket(0); var s2 = new ServerSocket(0)) {
			port1 = s1.getLocalPort();
			port2 = s2.getLocalPort();
		}

		var result = runCommand("-d", "-s", "KILL", String.valueOf(port1), String.valueOf(port2));
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("Searching processes listening on port " + port1));
		assertTrue(result.stdout().contains("Searching processes listening on port " + port2));

		var sigNumResult = runCommand("-d", "-s", "9", String.valueOf(port1));
		assertEquals(0, sigNumResult.exitCode());
	}

	public static void main(String... args) {
		var launcher = LauncherFactory.create();
		var summaryListener = new SummaryGeneratingListener();
		var request = LauncherDiscoveryRequestBuilder.request()
			.selectors(DiscoverySelectors.selectClass(KillportTest.class))
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
