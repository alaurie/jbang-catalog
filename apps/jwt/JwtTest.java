///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS org.junit.jupiter:junit-jupiter:5.11.4
//DEPS org.junit.platform:junit-platform-launcher:1.11.4
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//DEPS info.picocli:picocli:4.7.7
//SOURCES Jwt.java

package jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import picocli.CommandLine;

public class JwtTest {

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
			var app = new Jwt();
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

	private String createSampleJwt(String secret, long expEpochSeconds) throws Exception {
		return signedJwt(secret, "{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
				"{\"sub\":\"1234567890\",\"name\":\"Alex Test\",\"exp\":" + expEpochSeconds + "}");
	}

	private String signedJwt(String secret, String headerJson, String payloadJson) throws Exception {
		var b64Url = Base64.getUrlEncoder().withoutPadding();
		var headerPart = b64Url.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
		var payloadPart = b64Url.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
		var signingInput = headerPart + "." + payloadPart;

		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return signingInput + "." + b64Url.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void testHelp() {
		var result = runCommand("--help");
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("Inspect and decode JSON Web Tokens"));
	}

	@Test
	void testDecodePayloadAndHeader() throws Exception {
		long futureExp = (System.currentTimeMillis() / 1000) + 3600;
		String jwt = createSampleJwt("secretKey123", futureExp);

		var result = runCommand(jwt);
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("Alex Test"));
		assertTrue(result.stdout().contains("HS256"));
	}

	@Test
	void testPayloadOnly() throws Exception {
		long futureExp = (System.currentTimeMillis() / 1000) + 3600;
		String jwt = createSampleJwt("secretKey123", futureExp);

		var result = runCommand("-p", jwt);
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("Alex Test"));
		assertTrue(!result.stdout().contains("HEADER"));
	}

	@Test
	void testHeaderOnly() throws Exception {
		long futureExp = (System.currentTimeMillis() / 1000) + 3600;
		String jwt = createSampleJwt("secretKey123", futureExp);

		var result = runCommand("-H", jwt);
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("HS256"));
		assertTrue(!result.stdout().contains("Alex Test"));
	}

	@Test
	void testOutputModesAreMutuallyExclusive() throws Exception {
		var jwt = createSampleJwt("secretKey123", (System.currentTimeMillis() / 1000) + 3600);

		var result = runCommand("--header-only", "--payload-only", jwt);

		assertEquals(2, result.exitCode());
		assertTrue(result.stdout().contains("exclusive"));
	}

	@Test
	void testHmacSignatureVerification() throws Exception {
		long futureExp = (System.currentTimeMillis() / 1000) + 3600;
		String jwt = createSampleJwt("correctSecret", futureExp);

		// Correct secret
		var successResult = runCommand("-s", "correctSecret", jwt);
		assertEquals(0, successResult.exitCode());
		assertTrue(successResult.stdout().contains("Signature Verification: OK"));

		// Wrong secret must not reveal the claims.
		var failResult = runCommand("-s", "wrongSecret", jwt);
		assertEquals(1, failResult.exitCode());
		assertEquals("", failResult.stdout());
		assertTrue(failResult.stderr().contains("signature verification failed"));
	}

	@Test
	void secretRequiresValidSignatureInEveryMode() throws Exception {
		var signed = createSampleJwt("correctSecret", (System.currentTimeMillis() / 1000) + 3600);
		var unsigned = signed.substring(0, signed.lastIndexOf('.'));
		var badSignature = unsigned + ".AA";
		var modes = List.of("", "--check-exp", "--header-only", "--payload-only", "--env");

		for (var mode : modes) {
			for (var forged : List.of(unsigned, badSignature)) {
				var result = mode.isEmpty()
						? runCommand("--secret", "correctSecret", forged)
						: runCommand("--secret", "correctSecret", mode, forged);
				assertEquals(1, result.exitCode(), "Mode " + mode + " accepted a forged JWT");
				assertEquals("", result.stdout(), "Mode " + mode + " exposed unverified claims");
				assertTrue(result.stderr().contains("signature verification failed"));
			}
			var wrongKey = mode.isEmpty()
					? runCommand("--secret", "wrongSecret", signed)
					: runCommand("--secret", "wrongSecret", mode, signed);
			assertEquals(1, wrongKey.exitCode(), "Mode " + mode + " accepted a bad signature");
			assertEquals("", wrongKey.stdout(), "Mode " + mode + " exposed unverified claims");
			var valid = mode.isEmpty()
					? runCommand("--secret", "correctSecret", signed)
					: runCommand("--secret", "correctSecret", mode, signed);
			assertEquals(0, valid.exitCode(), "Mode " + mode + " rejected a signed JWT");
		}
	}

	@Test
	void testCheckExpiration() throws Exception {
		long pastExp = (System.currentTimeMillis() / 1000) - 3600;
		String expiredJwt = createSampleJwt("secretKey123", pastExp);

		var expiredResult = runCommand("-c", expiredJwt);
		assertEquals(1, expiredResult.exitCode());
		assertTrue(expiredResult.stdout().contains("EXPIRED"));

		long futureExp = (System.currentTimeMillis() / 1000) + 3600;
		String validJwt = createSampleJwt("secretKey123", futureExp);

		var validResult = runCommand("-c", validJwt);
		assertEquals(0, validResult.exitCode());
		assertTrue(validResult.stdout().contains("VALID"));
	}

	private String dangerousEnvToken(String value) throws Exception {
		var jsonValue = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
		return signedJwt("correctSecret", "{\"alg\":\"HS256\"}", "{\"9.bad-key\":\"" + jsonValue + "\"}");
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void posixExportPreservesDangerousValueWithoutExecutingIt() throws Exception {
		var value = "a\"'\\$HOME $(printf EXECUTED) `printf AGAIN`\n; printf BREAK >&2";
		var export = runCommand("--secret", "correctSecret", "--env", dangerousEnvToken(value));
		assertEquals(0, export.exitCode());

		var process = new ProcessBuilder("sh", "-c", "eval \"$1\"; printf '%s' \"$_9_BAD_KEY\"", "sh",
				export.stdout())
			.start();
		var actual = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		var errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor());
		assertEquals(value, actual);
		assertEquals("", errors);
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void windowsExportPreservesDangerousValueWithoutExecutingIt() throws Exception {
		var value = "a\"'\\$HOME $(printf EXECUTED) `printf AGAIN` %PATH% !PATH!\n; Write-Error BREAK";
		var export = runCommand("--secret", "correctSecret", "--env", dangerousEnvToken(value));
		assertEquals(0, export.exitCode());

		var script = export.stdout() + "[Console]::Out.Write(${env:_9_BAD_KEY})";
		var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).start();
		var actual = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		var errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor());
		assertEquals(value, actual);
		assertEquals("", errors);
	}

	@Test
	void testBearerPrefixStripping() throws Exception {
		long futureExp = (System.currentTimeMillis() / 1000) + 3600;
		String jwt = createSampleJwt("secretKey123", futureExp);

		var result = runCommand("Bearer " + jwt);
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("Alex Test"));
	}

	@Test
	void testMalformedTokens() {
		var singlePart = runCommand("not_a_valid_jwt");
		assertEquals(1, singlePart.exitCode());
		assertTrue(singlePart.stderr().contains("Invalid JWT format"));

		var fourParts = runCommand("a.b.c.d");
		assertEquals(1, fourParts.exitCode());
		assertTrue(fourParts.stderr().contains("Invalid JWT format"));

		var badBase64 = runCommand("???invalid???b64.???invalid???b64.sig");
		assertEquals(1, badBase64.exitCode());
		assertTrue(badBase64.stderr().contains("Failed to base64-decode"));

		var invalidUtf8 = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(
					new byte[] { '{', '"', 'x', '"', ':', '"', (byte) 0xff, '"', '}' });
		var badEncoding = runCommand("e30." + invalidUtf8);
		assertEquals(1, badEncoding.exitCode());
		assertTrue(badEncoding.stderr().contains("Failed to base64-decode"));
	}

	@Test
	void malformedJsonCannotBecomeVerifiedClaims() throws Exception {
		var header = "{\"alg\":\"HS256\"}";
		for (var malformed : List.of(
				"{} trailing", "{\"x\" 1}", "{\"x\":1,}", "{\"x\":\"\\q\"}",
				"{\"x\":\"\\u12G4\"}", "{\"x\":01}", "{\"x\":true",
				"{\"x\":\"line\nbreak\"}", "{\"x\":1 \"y\":2}", "{\"x\":1,\"x\":2}", "[]")) {
			var signed = signedJwt("correctSecret", header, malformed);
			for (var mode : List.of("--header-only", "--payload-only", "--check-exp", "--env")) {
				var result = runCommand("--secret", "correctSecret", mode, signed);
				assertEquals(1, result.exitCode(), "Accepted malformed payload: " + malformed);
				assertEquals("", result.stdout());
				assertTrue(result.stderr().contains("Invalid JSON"));
			}
		}
		var malformedHeader = signedJwt("correctSecret", "{\"alg\":\"HS256\"} trailing", "{\"sub\":\"user\"}");
		var result = runCommand("--secret", "correctSecret", "--payload-only", malformedHeader);
		assertEquals(1, result.exitCode());
		assertEquals("", result.stdout());
		assertTrue(result.stderr().contains("Invalid JSON"));
	}

	@Test
	void testTokenFromFile(@TempDir java.nio.file.Path tempDir) throws Exception {
		long futureExp = (System.currentTimeMillis() / 1000) + 3600;
		String jwt = createSampleJwt("secretKey123", futureExp);

		var tokenFile = tempDir.resolve("token.jwt");
		java.nio.file.Files.writeString(tokenFile, jwt);

		var result = runCommand(tokenFile.toString());
		assertEquals(0, result.exitCode());
		assertTrue(result.stdout().contains("Alex Test"));
	}

	@Test
	void testTokenWithoutExpClaim() {
		var b64Url = Base64.getUrlEncoder().withoutPadding();
		var headerJson = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
		var payloadJson = "{\"sub\":\"no_exp_user\",\"iss\":\"test_issuer\",\"aud\":\"test_audience\"}";
		var token = b64Url.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8)) + "."
				+ b64Url.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

		var checkResult = runCommand("-c", token);
		assertEquals(0, checkResult.exitCode());
		assertTrue(checkResult.stdout().contains("No 'exp' claim present"));

		var inspectResult = runCommand(token);
		assertEquals(0, inspectResult.exitCode());
		assertTrue(inspectResult.stdout().contains("Issuer (iss):    test_issuer"));
		assertTrue(inspectResult.stdout().contains("test_audience"));
		assertTrue(inspectResult.stdout().contains("[Unsigned Token]"));
	}

	public static void main(String... args) {
		var launcher = LauncherFactory.create();
		var summaryListener = new SummaryGeneratingListener();
		var request = LauncherDiscoveryRequestBuilder.request()
			.selectors(DiscoverySelectors.selectClass(JwtTest.class))
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
