//usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS tools.jackson.core:jackson-databind:3.1.0
//DEPS info.picocli:picocli:4.7.5
//NATIVE_OPTIONS -O2 --no-fallback

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.zip.*;

@Command(name = "tfup", mixinStandardHelpOptions = true, version = "tfup 1.0",
        description = "Fetches and installs Terraform.")
public class tfup implements Callable<Integer> {

    @Option(names = {"-f", "--force"}, description = "Force update even if versions match.")
    private boolean force;

    @Option(names = {"-p", "--path"}, description = "Custom installation directory path.")
    private Path customPath;

    @Option(names = {"-v", "--version"}, description = "Specific version to install (e.g., 1.9.0). If omitted, latest is fetched.")
    private String versionToInstall;

    private static final String GITHUB_API = "https://api.github.com/repos/hashicorp/terraform/releases/latest";
    private static final String DOWNLOAD_BASE = "https://releases.hashicorp.com/terraform/";
    private static final Path DEFAULT_BIN_DIR = Paths.get(System.getProperty("user.home"), ".local", "bin");
    private static final String OS_NAME = getOsName();
    private static final String OS_ARCH = getOsArch();
    private static final String TF_PLATFORM = OS_NAME + "_" + OS_ARCH;
    private static final String EXE_NAME = OS_NAME.equals("windows") ? "terraform.exe" : "terraform";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String... args) {
        int exitCode = new CommandLine(new tfup()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        Path binDir = customPath != null ? customPath : DEFAULT_BIN_DIR;
        Files.createDirectories(binDir);
        Path tfExe = binDir.resolve(EXE_NAME);

        System.out.println("Checking local version...");
        String localVer = getLocalVersion(tfExe);

        String targetVer;
        if (versionToInstall != null) {
            targetVer = versionToInstall;
            if (targetVer.startsWith("v")) targetVer = targetVer.substring(1);
            System.out.println("Requested specific version: " + targetVer);
        } else {
            System.out.println("Checking GitHub for latest release...");
            targetVer = getLatestVersion();
            if (targetVer == null) {
                System.err.println("Error: Failed to fetch latest version from GitHub.");
                return 1;
            }
        }

        if (!force && targetVer.equals(localVer)) {
            System.out.println("Terraform is already at requested version (v" + localVer + ").");
            checkPath(binDir);
            return 0;
        }

        if (force) {
            System.out.printf("Forcing installation of: %s%n", targetVer);
        } else {
            System.out.printf("Installation required: %s -> %s%n", (localVer == null ? "None" : localVer), targetVer);
        }
        
        updateTerraform(targetVer, binDir, tfExe);

        System.out.println("Verifying installation...");
        String installedVer = getLocalVersion(tfExe);
        System.out.println("Current Version: " + installedVer);
        
        checkPath(binDir);
        return 0;
    }

    private static String getOsName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "darwin";
        return "linux";
    }

    private static String getOsArch() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.contains("amd64") || arch.contains("x86_64")) return "amd64";
        if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64";
        if (arch.contains("x86") || arch.contains("i386") || arch.contains("i686")) return "386";
        if (arch.contains("arm")) return "arm";
        return "amd64";
    }

    private static void checkPath(Path binDir) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] paths = pathEnv.split(File.pathSeparator);
            for (String p : paths) {
                if (Paths.get(p).normalize().equals(binDir.normalize())) {
                    return; // binDir is already in PATH
                }
            }
        }
        
        System.out.println("\n--- WARNING ---");
        System.out.println("The directory " + binDir + " is not in your PATH.");
        if (OS_NAME.equals("windows")) {
            System.out.println("To use terraform, add it to your PATH via System Properties > Environment Variables.");
            System.out.println("Or run this in PowerShell:");
            System.out.println("[Environment]::SetEnvironmentVariable(\"Path\", [Environment]::GetEnvironmentVariable(\"Path\", \"User\") + \";" + binDir + "\", \"User\")");
        } else {
            System.out.println("To use terraform, add this line to your ~/.bashrc or ~/.zshrc:");
            System.out.println("export PATH=\"" + binDir + ":$PATH\"");
        }
        System.out.println("---------------\n");
    }

    private static String getLocalVersion(Path tfExe) {
        if (!Files.exists(tfExe))
            return null;
        try {
            Process process = new ProcessBuilder(tfExe.toString(), "-v", "-json").start();
            byte[] bytes = process.getInputStream().readAllBytes();
            JsonNode node = MAPPER.readTree(bytes);
            return node.get("terraform_version").asString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String getLatestVersion() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(GITHUB_API))
            .header("User-Agent", "jbang-tfup-script")
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonNode node = MAPPER.readTree(response.body());
            String tag = node.get("tag_name").asString();
            return tag.startsWith("v") ? tag.substring(1) : tag;
        }
        return null;
    }

    private static void updateTerraform(String version, Path binDir, Path tfExe) throws IOException, InterruptedException {
        String zipName = String.format("terraform_%s_%s.zip", version, TF_PLATFORM);
        String url = DOWNLOAD_BASE + version + "/" + zipName;

        System.out.println("Downloading: " + url);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();

        Path tempZip = Files.createTempFile("tf_update", ".zip");
        client.send(request, HttpResponse.BodyHandlers.ofFile(tempZip));

        System.out.println("Extracting to: " + binDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempZip))) {
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
            // Ignored standard java way of doing this
            tfExe.toFile().setExecutable(true);
        }

        System.out.println("Installation of v" + version + " complete.");
    }
}