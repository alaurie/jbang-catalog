///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//NATIVE_OPTIONS -O2 --no-fallback

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * CLI utility to calculate and verify cryptographic checksums (MD5, SHA-1, SHA-256, SHA-512, etc.).
 *
 * <p>
 * Supports checking files, direct string inputs, stdin, and verification files.
 */
@Command(name = "hash", mixinStandardHelpOptions = true, version = "hash 1.0", description = "Compute and verify cryptographic checksums for files or text input.")
@SuppressWarnings("unused")
class Hash implements Callable<Integer> {

	@Option(names = { "-a",
			"--algorithm" }, description = "Hash algorithm: MD5, SHA-1, SHA-256, SHA-512, SHA3-256, SHA3-512. Default: SHA-256.")
	private String algorithm = "SHA-256";

	@Option(names = { "-t", "--text" }, description = "Compute hash for string text input instead of file.")
	private String textInput;

	@Option(names = { "-c", "--check" }, description = "Verify checksums from specified checksum file.")
	private Path checkFile;

	@Parameters(arity = "0..*", paramLabel = "<file>", description = "One or more file paths to hash, or '-' for stdin.")
	private List<Path> files;

	/**
	 * Main entry point for the JBang script execution.
	 *
	 * @param args Command-line arguments.
	 */
	void main(String... args) {
		var exitCode = new CommandLine(this).execute(args);
		System.exit(exitCode);
	}

	/**
	 * Calculates or verifies hashes according to CLI options.
	 *
	 * @return Status code 0 for success, 1 on hash mismatch or errors.
	 */
	@Override
	public Integer call() {
		try {
			normalizeAlgorithm();
		} catch (NoSuchAlgorithmException e) {
			System.err.println("Error: " + e.getMessage());
			return 1;
		}

		if (checkFile != null) {
			return verifyCheckFile(checkFile);
		}

		if (textInput != null) {
			return hashText(textInput);
		}

		if (files == null || files.isEmpty()) {
			if (System.console() != null) {
				CommandLine.usage(this, System.out);
				return 0;
			}
			return hashStream(System.in, "-");
		}

		var hasError = false;
		for (var file : files) {
			if (file.toString().equals("-")) {
				if (hashStream(System.in, "-") != 0) {
					hasError = true;
				}
			} else if (!Files.exists(file)) {
				System.err.printf("Error: File '%s' does not exist.%n", file);
				hasError = true;
			} else if (Files.isDirectory(file)) {
				System.err.printf("Error: Path '%s' is a directory.%n", file);
				hasError = true;
			} else {
				if (hashFile(file) != 0) {
					hasError = true;
				}
			}
		}

		return hasError ? 1 : 0;
	}

	/**
	 * Normalizes algorithm name to standard JCA provider string.
	 *
	 * @throws NoSuchAlgorithmException If algorithm is not supported.
	 */
	private void normalizeAlgorithm() throws NoSuchAlgorithmException {
		var algoUpper = algorithm.toUpperCase(Locale.ROOT).replace("-", "");
		algorithm = switch (algoUpper) {
		case "MD5" -> "MD5";
		case "SHA1" -> "SHA-1";
		case "SHA256" -> "SHA-256";
		case "SHA512" -> "SHA-512";
		case "SHA3256" -> "SHA3-256";
		case "SHA3512" -> "SHA3-512";
		default -> throw new NoSuchAlgorithmException("Unsupported algorithm: " + algorithm);
		};
		MessageDigest.getInstance(algorithm);
	}

	/**
	 * Hashes plain text string.
	 *
	 * @param input Raw text string.
	 * @return Status code 0 on success, 1 on error.
	 */
	private int hashText(String input) {
		try {
			var digest = MessageDigest.getInstance(algorithm);
			var bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
			var hex = HexFormat.of().formatHex(bytes);
			System.out.printf("%s  \"%s\"%n", hex, input);
			return 0;
		} catch (Exception e) {
			System.err.println("Error calculating hash: " + e.getMessage());
			return 1;
		}
	}

	/**
	 * Hashes single file.
	 *
	 * @param file Target file path.
	 * @return Status code 0 on success, 1 on error.
	 */
	private int hashFile(Path file) {
		try (var is = Files.newInputStream(file)) {
			return hashStream(is, file.toString());
		} catch (Exception e) {
			System.err.printf("Error reading file '%s': %s%n", file, e.getMessage());
			return 1;
		}
	}

	/**
	 * Reads input stream in chunks and prints hex checksum.
	 *
	 * @param is Input stream to process.
	 * @param label Display label for stream/file.
	 * @return Status code 0 on success, 1 on error.
	 */
	private int hashStream(InputStream is, String label) {
		try {
			var digest = MessageDigest.getInstance(algorithm);
			var buffer = new byte[8192];
			int read;
			while ((read = is.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
			var hex = HexFormat.of().formatHex(digest.digest());
			System.out.printf("%s  %s%n", hex, label);
			return 0;
		} catch (Exception e) {
			System.err.printf("Error computing hash for '%s': %s%n", label, e.getMessage());
			return 1;
		}
	}

	/**
	 * Verifies file checksums against specified checksum list file.
	 *
	 * @param file Path to checksum file.
	 * @return Status code 0 if all matched, 1 on mismatches or errors.
	 */
	private int verifyCheckFile(Path file) {
		if (!Files.exists(file)) {
			System.err.printf("Error: Checksum file '%s' does not exist.%n", file);
			return 1;
		}

		var mismatches = 0;
		var total = 0;

		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}

				var parts = line.split("\\s+", 2);
				if (parts.length < 2) {
					continue;
				}

				var expectedHash = parts[0].trim().toLowerCase(Locale.ROOT);
				var targetFilePathStr = parts[1].trim();
				if (targetFilePathStr.startsWith("*")) {
					targetFilePathStr = targetFilePathStr.substring(1);
				}

				var targetPath = file.getParent() != null ? file.getParent().resolve(targetFilePathStr)
						: Path.of(targetFilePathStr);

				total++;
				if (!Files.exists(targetPath)) {
					System.out.printf("%s: FAILED (File not found)%n", targetFilePathStr);
					mismatches++;
					continue;
				}

				var computedHash = computeFileHex(targetPath);
				if (computedHash != null && computedHash.equalsIgnoreCase(expectedHash)) {
					System.out.printf("%s: OK%n", targetFilePathStr);
				} else {
					System.out.printf("%s: FAILED%n", targetFilePathStr);
					mismatches++;
				}
			}
		} catch (Exception e) {
			System.err.println("Error reading checksum file: " + e.getMessage());
			return 1;
		}

		if (total == 0) {
			System.err.println("Warning: No valid checksum entries found in file.");
			return 1;
		}

		if (mismatches > 0) {
			System.err.printf("WARNING: %d of %d computed checksums did NOT match.%n", mismatches, total);
			return 1;
		}

		return 0;
	}

	/**
	 * Computes hex digest string for file path.
	 *
	 * @param path Target file path.
	 * @return Hex checksum string or {@code null} on failure.
	 */
	private String computeFileHex(Path path) {
		try (var is = Files.newInputStream(path)) {
			var digest = MessageDigest.getInstance(algorithm);
			var buffer = new byte[8192];
			int read;
			while ((read = is.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (Exception e) {
			return null;
		}
	}
}
