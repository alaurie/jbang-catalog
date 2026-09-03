///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//DEPS tools.jackson.core:jackson-databind:3.2.1
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms4m -Xmx32m -XX:TieredStopAtLevel=1 -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData
//NATIVE_OPTIONS -O2 -march=native --no-fallback

package jwt;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.Callable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/// CLI utility to inspect and decode JSON Web Tokens (JWT) safely off-line.
///
/// Splits JWT parts (header, payload, signature), base64url decodes header and payload, pretty
/// prints JSON structures, checks expiration/timestamp claims, verifies HMAC signatures, and
/// exports
/// claims to shell environment variables.
@Command(name = "jwt", mixinStandardHelpOptions = true, version = "jwt 1.1",
    description = "Inspect and decode JSON Web Tokens (JWT) without sending tokens to third parties.")
@SuppressWarnings("unused")
class Jwt implements Callable<Integer> {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

  @Option(names = {"-p", "--payload-only"}, description = "Print payload JSON only.")
  private boolean payloadOnly;

  @Option(names = {"-H", "--header-only"}, description = "Print header JSON only.")
  private boolean headerOnly;

  @Option(names = {"-c", "--check-exp"},
      description = "Validate token expiry state and exit 0 (valid) or 1 (expired).")
  private boolean checkExp;

  @Option(names = {"-e", "--env", "--export"},
      description = "Format payload claims as shell environment variables (export KEY=VAL).")
  private boolean exportEnv;

  @Option(names = {"-s", "--secret"},
      description = "HMAC secret key to verify token signature (HS256, HS384, HS512).")
  private String secret;

  @Parameters(arity = "0..1", paramLabel = "<tokenOrFile>",
      description = "JWT token string, file path containing token, or '-' for stdin.")
  private String tokenOrFile;

  /**
   * Main entry point using Java 25 instance main convention.
   *
   * @param args Command-line arguments.
   */
  void main(String... args) {
    var exitCode = new CommandLine(this).execute(args);
    System.exit(exitCode);
  }

  /**
   * Decodes and displays JWT details.
   *
   * @return Status code 0 for success, 1 for errors/expiration/verification failure.
   */
  @Override
  public Integer call() throws Exception {
    var rawToken = resolveToken();
    if (rawToken == null || rawToken.isBlank()) {
      if (tokenOrFile == null && System.console() != null) {
        CommandLine.usage(this, System.out);
        return 0;
      }
      System.err.println("Error: No JWT token provided via argument, file, or stdin.");
      return 1;
    }

    rawToken = rawToken.trim();
    if (rawToken.startsWith("Bearer ") || rawToken.startsWith("bearer ")) {
      rawToken = rawToken.substring(7).trim();
    }

    var parts = rawToken.split("\\.");
    if (parts.length < 2 || parts.length > 3) {
      System.err.println("Error: Invalid JWT format. Expected 2 or 3 dot-separated parts, found "
          + parts.length + ".");
      return 1;
    }

    var headerJson = decodePart(parts[0]);
    var payloadJson = decodePart(parts[1]);
    var signatureStr = parts.length == 3 ? parts[2] : "";

    if (headerJson == null || payloadJson == null) {
      System.err.println("Error: Failed to base64-decode JWT components.");
      return 1;
    }

    if (headerOnly) {
      System.out.println(prettyPrintJson(headerJson));
      return 0;
    }

    if (payloadOnly) {
      System.out.println(prettyPrintJson(payloadJson));
      return 0;
    }

    var payloadNode = MAPPER.readTree(payloadJson);

    if (exportEnv) {
      exportPayloadAsEnv(payloadNode);
      return 0;
    }

    var nowSec = Instant.now().getEpochSecond();
    var expNode = payloadNode.get("exp");
    var isExpired = false;

    if (expNode != null && expNode.isNumber()) {
      var expSec = expNode.asLong();
      if (nowSec > expSec) {
        isExpired = true;
      }
    }

    if (checkExp) {
      if (expNode == null) {
        System.out.println("No 'exp' claim present in JWT.");
        return 0;
      }
      if (isExpired) {
        System.out.println("EXPIRED");
        return 1;
      } else {
        System.out.println("VALID");
        return 0;
      }
    }

    System.out.println("=== HEADER ===");
    System.out.println(prettyPrintJson(headerJson));

    System.out.println("\n=== PAYLOAD ===");
    System.out.println(prettyPrintJson(payloadJson));

    System.out.println("\n=== CLAIMS SUMMARY ===");
    printClaimTimestamp(payloadNode, "iat", "Issued At", nowSec);
    printClaimTimestamp(payloadNode, "nbf", "Not Before", nowSec);
    printClaimTimestamp(payloadNode, "exp", "Expiration", nowSec);
    if (payloadNode.has("iss")) {
      System.out.println("Issuer (iss):    " + payloadNode.get("iss").asString());
    }
    if (payloadNode.has("sub")) {
      System.out.println("Subject (sub):   " + payloadNode.get("sub").asString());
    }
    if (payloadNode.has("aud")) {
      System.out.println("Audience (aud):  " + payloadNode.get("aud"));
    }

    System.out.println("\n=== SIGNATURE ===");
    if (signatureStr.isBlank()) {
      System.out.println("[Unsigned Token]");
    } else {
      System.out.println(signatureStr);
      if (secret != null && !secret.isBlank()) {
        var headerNode = MAPPER.readTree(headerJson);
        var algNode = headerNode.get("alg");
        var alg = algNode != null ? algNode.asString() : "";
        var verified = verifyHmacSignature(parts[0] + "." + parts[1], signatureStr, secret, alg);
        if (verified) {
          System.out.println("Signature Verification: OK (HMAC " + alg + ")");
        } else {
          System.out.println("Signature Verification: FAILED (HMAC " + alg + " mismatch)");
          return 1;
        }
      }
    }

    return isExpired ? 1 : 0;
  }

  /**
   * Verifies HMAC signature for HS256, HS384, or HS512 JWTs.
   *
   * @param signingInput Header and payload joined by dot (parts[0] + "." + parts[1]).
   * @param signature Base64url signature from JWT.
   * @param secretKey Secret key byte array string.
   * @param alg Algorithm name from header (e.g. HS256).
   * @return {@code true} if signature matches, {@code false} otherwise.
   */
  private static boolean verifyHmacSignature(String signingInput, String signature,
      String secretKey, String alg) {
    String hmacAlg = switch (alg.toUpperCase()) {
      case "HS256" -> "HmacSHA256";
      case "HS384" -> "HmacSHA384";
      case "HS512" -> "HmacSHA512";
      default -> null;
    };

    if (hmacAlg == null) {
      System.err.println("Unsupported HMAC algorithm for verification: " + alg);
      return false;
    }
    try {
      var mac = Mac.getInstance(hmacAlg);
      var keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), hmacAlg);
      mac.init(keySpec);
      var computedBytes = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
      var computedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(computedBytes);
      return computedSignature.equals(signature.replace("=", ""));
    } catch (Exception e) {
      return false;
    }
  }

  private static void exportPayloadAsEnv(JsonNode payload) {
    var isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
    var propertyNames = payload.propertyNames();
    for (var key : propertyNames) {
      var envKey =
          key.replaceAll("([a-z])([A-Z])", "$1_$2").replaceAll("[^a-zA-Z0-9_]", "_").toUpperCase();
      var value = payload.get(key);
      var valStr = value.isTextual() ? value.asString() : value.toString();

      if (isWindows) {
        System.out.printf("SET %s=%s%n", envKey, valStr);
      } else {
        System.out.printf("export %s=\"%s\"%n", envKey, valStr.replace("\"", "\\\""));
      }
    }
  }

  /**
   * Resolves token string from command positional argument, file path, or stdin.
   *
   * @return Token string or {@code null} if unresolvable.
   * @throws Exception On I/O reading errors.
   */
  private String resolveToken() throws Exception {
    if (tokenOrFile != null && !tokenOrFile.isBlank() && !tokenOrFile.equals("-")) {
      var path = Path.of(tokenOrFile);
      if (Files.exists(path)) {
        return Files.readString(path, StandardCharsets.UTF_8);
      }
      return tokenOrFile;
    }

    if (System.in.available() > 0 || (tokenOrFile != null && tokenOrFile.equals("-"))) {
      try (var br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
        var sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
          sb.append(line.trim());
        }
        return sb.toString();
      }
    }
    return null;
  }

  /**
   * Base64url decodes a JWT section.
   *
   * @param part Base64url encoded string slice.
   * @return Decoded UTF-8 string, or {@code null} on failure.
   */
  private static String decodePart(String part) {
    try {
      var bytes = Base64.getUrlDecoder().decode(part);
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Formats raw JSON string into pretty indented JSON string.
   *
   * @param rawJson Raw JSON string.
   * @return Indented JSON string or original string if parsing fails.
   */
  private static String prettyPrintJson(String rawJson) {
    try {
      var tree = MAPPER.readTree(rawJson);
      return MAPPER.writeValueAsString(tree);
    } catch (Exception e) {
      return rawJson;
    }
  }

  /**
   * Prints claim timestamp and relative duration if present in payload JSON.
   *
   * @param node JSON payload root node.
   * @param key Claim key name.
   * @param label Display label.
   * @param nowSec Current epoch timestamp in seconds.
   */
  private static void printClaimTimestamp(JsonNode node, String key, String label, long nowSec) {
    if (!node.has(key) || !node.get(key).isNumber()) {
      return;
    }
    var epochSec = node.get(key).asLong();
    var zdt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZoneId.systemDefault());
    var formattedDate = zdt.format(DATE_FORMATTER);
    var diffSec = epochSec - nowSec;
    var relative = getRelativeTimeString(diffSec);

    System.out.printf("%-16s %s (%s)%n", label + " (" + key + "):", formattedDate, relative);
  }

  /**
   * Computes human-readable relative time string.
   *
   * @param diffSec Difference in seconds from now.
   * @return Formatted relative time string (e.g. "in 2 hours", "5 minutes ago").
   */
  private static String getRelativeTimeString(long diffSec) {
    if (diffSec == 0) {
      return "now";
    }
    var ago = diffSec < 0;
    var absSec = Math.abs(diffSec);
    String unit;
    long val;

    if (absSec < 60) {
      val = absSec;
      unit = val == 1 ? "second" : "seconds";
    } else if (absSec < 3600) {
      val = absSec / 60;
      unit = val == 1 ? "minute" : "minutes";
    } else if (absSec < 86400) {
      val = absSec / 3600;
      unit = val == 1 ? "hour" : "hours";
    } else {
      val = absSec / 86400;
      unit = val == 1 ? "day" : "days";
    }

    return ago ? val + " " + unit + " ago" : "in " + val + " " + unit;
  }
}
