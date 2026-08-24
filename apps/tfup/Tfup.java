///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//DEPS tools.jackson.core:jackson-databind:3.2.1
//JAVAC_OPTIONS -proc:full
//NATIVE_OPTIONS -O2 --no-fallback

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Cross-platform installer utility for HashiCorp Terraform.
 *
 * <p>
 * Fetches release metadata from GitHub, compares current installed binary version, and downloads
 * the official Terraform release binary for Windows, macOS, or Linux.
 */
@Command(name = "tfup", version = "tfup 1.0", description = "Fetches and installs Terraform.")
@SuppressWarnings("unused")
class Tfup implements Callable<Integer> {

	private static final String GITHUB_API = "https://api.github.com/repos/hashicorp/terraform/releases/latest";
	private static final String DOWNLOAD_BASE = "https://releases.hashicorp.com/terraform/";
	private static final Path DEFAULT_BIN_DIR = Path.of(System.getProperty("user.home"), ".local", "bin");
	private static final String OS_NAME = getOsName();
	private static final String OS_ARCH = getOsArch();
	private static final String TF_PLATFORM = OS_NAME + "_" + OS_ARCH;
	private static final String EXE_NAME = OS_NAME.equals("windows") ? "terraform.exe" : "terraform";
	private static final ObjectMapper MAPPER = JsonMapper.builder().build();
	@Option(names = { "-h", "--help" }, usageHelp = true, description = "Show this help message and exit.")
	private boolean helpRequested;
	@Option(names = { "-V" }, versionHelp = true, description = "Print version information and exit.")
	private boolean versionRequested;
	@Option(names = { "-f", "--force" }, description = "Force update even if versions match.")
	private boolean force;
	@Option(names = { "-p", "--path" }, description = "Custom installation directory path.")
	private Path customPath;
	@Option(names = { "-v",
			"--version" }, description = "Specific version to install (e.g., 1.9.0). If omitted, latest is fetched.")
	private String versionToInstall;

	/**
	 * Resolves normalized OS name string.
	 *
	 * @return Normalized OS string ("windows", "darwin", or "linux").
	 */
	private static String getOsName() {
		var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		if (os.contains("win"))
			return "windows";
		if (os.contains("mac") || os.contains("darwin"))
			return "darwin";
		return "linux";
	}

	/**
	 * Resolves normalized CPU architecture string.
	 *
	 * @return Normalized architecture string ("amd64" or "arm64").
	 */
	private static String getOsArch() {
		var arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
		if (arch.contains("aarch64") || arch.contains("arm64"))
			return "arm64";
		return "amd64";
	}

	/**
	 * Checks if installation binary directory is present in PATH environment variable.
	 *
	 * @param binDir Directory path to check.
	 */
	private static void checkPath(Path binDir) {
		var pathEnv = System.getenv("PATH");
		if (pathEnv == null)
			return;

		var absBinDir = binDir.toAbsolutePath().normalize().toString();
		var inPath = Arrays.stream(pathEnv.split(File.pathSeparator))
			.anyMatch(
					p -> Path.of(p).toAbsolutePath().normalize().toString().equalsIgnoreCase(absBinDir));

		if (!inPath) {
			System.out.println("\n[NOTICE] " + absBinDir + " is not in your PATH.");
			var githubPath = System.getenv("GITHUB_PATH");
			if (githubPath != null && !githubPath.isBlank()) {
				try {
					Files.writeString(Path.of(githubPath), absBinDir + System.lineSeparator(),
							StandardOpenOption.APPEND);
					System.out.println("[NOTICE] Added " + absBinDir
							+ " to $GITHUB_PATH for subsequent GitHub Actions steps.");
				} catch (Exception e) {
					System.err.println("Failed to write to GITHUB_PATH: " + e.getMessage());
				}
			} else if (OS_NAME.equals("windows")) {
				System.out.println("Add it via System Properties -> Environment Variables.");
			} else {
				System.out.println("Add it to your shell profile (e.g. ~/.bashrc or ~/.zshrc):");
				System.out.println("  export PATH=\"" + absBinDir + ":$PATH\"");
			}
		}
	}

	/**
	 * Retrieves version string of locally installed Terraform executable.
	 *
	 * @param tfExe Path to terraform executable binary.
	 * @return Installed version string, or {@code null} if not found or executable fails.
	 */
	private static String getLocalVersion(Path tfExe) {
		if (!Files.exists(tfExe))
			return null;
		try {
			var pb = new ProcessBuilder(tfExe.toString(), "version");
			var process = pb.start();
			try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				var line = reader.readLine();
				if (line != null && line.contains("Terraform v")) {
					return line.split("v")[1].trim();
				}
			}
		} catch (Exception e) {
			// Ignored
		}
		return null;
	}

	/**
	 * Fetches latest release tag version string from HashiCorp Terraform GitHub API.
	 *
	 * @return Latest version string, or {@code null} on failure.
	 * @throws IOException          On I/O errors.
	 * @throws InterruptedException On request interruption.
	 */
	private static String getLatestVersion() throws IOException, InterruptedException {
        HttpResponse<String> response;
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_API))
                    .header("User-Agent", "jbang-tfup-script")
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() == 200) {
			var node = MAPPER.readTree(response.body());
			var tag = node.get("tag_name").asString();
			return tag.startsWith("v") ? tag.substring(1) : tag;
		}
		return null;
	}

	/**
	 * Downloads official Terraform release zip archive and extracts binary into target directory.
	 *
	 * @param version Version string to download.
	 * @param binDir  Directory to install binary into.
	 * @param tfExe   Target executable file path.
	 * @throws IOException          On I/O or network errors.
	 * @throws InterruptedException On request interruption.
	 */
	private static void updateTerraform(String version, Path binDir, Path tfExe)
			throws IOException, InterruptedException {
		var zipName = String.format("terraform_%s_%s.zip", version, TF_PLATFORM);
		var url = DOWNLOAD_BASE + version + "/" + zipName;

		System.out.println("Downloading: " + url);
		var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
		var request = HttpRequest.newBuilder().uri(URI.create(url)).build();

		var tempZip = Files.createTempFile("tf_update", ".zip");
		client.send(request, HttpResponse.BodyHandlers.ofFile(tempZip));

		System.out.println("Extracting to: " + binDir);
		try (var zis = new ZipInputStream(Files.newInputStream(tempZip))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (entry.getName().equals(EXE_NAME)) {
					Files.copy(zis, tfExe, StandardCopyOption.REPLACE_EXISTING);
					zis.closeEntry();
					break;
				}
			}
		}
		Files.deleteIfExists(tempZip);

		if (!OS_NAME.equals("windows")) {
			boolean ignored = tfExe.toFile().setExecutable(true);
		}

		System.out.println("Installation of v" + version + " complete.");
	}

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
	 * Executes local version check, resolves latest release, and performs download/update.
	 *
	 * @return Status code 0 for success, 1 for failure.
	 * @throws Exception On unexpected HTTP, I/O, or execution errors.
	 */
	@Override
	public Integer call() throws Exception {
		var binDir = customPath != null ? customPath : DEFAULT_BIN_DIR;
		Files.createDirectories(binDir);
		var tfExe = binDir.resolve(EXE_NAME);

		System.out.println("Checking local version...");
		var localVer = getLocalVersion(tfExe);

		String targetVer;
		if (versionToInstall != null) {
			targetVer = versionToInstall;
			if (targetVer.startsWith("v"))
				targetVer = targetVer.substring(1);
			System.out.println("Requested specific version: " + targetVer);
		} else {
			System.out.println("Checking GitHub for latest release...");
			targetVer = getLatestVersion();
			if (targetVer == null) {
				System.err.println("Error: Failed to fetch latest version from GitHub.");
				return 1;
			}
		}

		if (localVer != null) {
			System.out.println("Installed version: " + localVer);
			System.out.println("Target version:    " + targetVer);
			if (localVer.equals(targetVer) && !force) {
				System.out.println("Terraform is already up to date. Use --force to reinstall.");
				checkPath(binDir);
				return 0;
			}
		} else {
			System.out.println("Terraform is not installed. Target version: " + targetVer);
		}

		updateTerraform(targetVer, binDir, tfExe);
		checkPath(binDir);

		return 0;
	}
}
