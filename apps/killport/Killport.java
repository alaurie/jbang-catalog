///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms4m -Xmx32m -XX:TieredStopAtLevel=1 -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData
//NATIVE_OPTIONS -O2 -march=native --no-fallback


package killport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// Cross-platform CLI utility to find and terminate processes listening on specified network ports.
///
/// Supports dry-run inspection, interactive prompts, custom signals, force killing, and multi-port
/// batch termination across Windows, macOS, and Linux.
@Command(name = "killport", mixinStandardHelpOptions = true, version = "killport 1.1",
    description = "Find and terminate processes listening on specified network ports.")
@SuppressWarnings("unused")
class Killport implements Callable<Integer> {

  private static final boolean IS_WINDOWS =
      System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

  @Option(names = {"-f", "--force"},
      description = "Forcefully terminate process (SIGKILL / taskkill /F).")
  private boolean force;

  @Option(names = {"-d", "--dry-run"},
      description = "Show matching processes without killing them.")
  private boolean dryRun;

  @Option(names = {"-i", "--interactive"},
      description = "Prompt confirmation [y/N] before killing each process.")
  private boolean interactive;

  @Option(names = {"-s", "--signal"},
      description = "Termination signal: TERM (graceful) or KILL (forceful). Default: TERM.")
  private String signal;

  @Parameters(arity = "1..*", paramLabel = "<port>",
      description = "One or more port numbers to inspect or kill.")
  private List<Integer> ports;

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
   * Finds and kills processes bound to specified ports.
   *
   * @return Status code 0 for success, 1 if errors occurred.
   */
  @Override
  public Integer call() {
    var hasError = false;

    if (signal != null) {
      var sigUpper = signal.toUpperCase(Locale.ROOT);
      if (sigUpper.contains("KILL") || sigUpper.equals("9")) {
        force = true;
      }
    }

    var scanner = interactive ? new Scanner(System.in) : null;

    for (var port : ports) {
      if (port < 1 || port > 65535) {
        System.err.printf("Error: Invalid port number %d. Must be between 1 and 65535.%n", port);
        hasError = true;
        continue;
      }

      System.out.printf("Searching processes listening on port %d...%n", port);
      var pids = findPidsForPort(port);

      if (pids.isEmpty()) {
        System.out.printf("No active process found on port %d.%n", port);
        continue;
      }

      for (var pid : pids) {
        var optHandle = ProcessHandle.of(pid);
        var name = optHandle.flatMap(ph -> ph.info().command()).orElse("Unknown process");

        if (dryRun) {
          System.out.printf(" [DRY-RUN] Found PID %d (%s) listening on port %d.%n", pid, name,
              port);
        } else {
          if (interactive && scanner != null) {
            System.out.printf("Kill PID %d (%s) on port %d? [y/N]: ", pid, name, port);
            var response = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
            if (!response.equalsIgnoreCase("y") && !response.equalsIgnoreCase("yes")) {
              System.out.println(" Skipped.");
              continue;
            }
          }

          System.out.printf(" Terminating PID %d (%s) on port %d... ", pid, name, port);
          var success = killProcess(pid, force);
          if (success) {
            System.out.println("SUCCESS");
          } else {
            System.out.println("FAILED");
            hasError = true;
          }
        }
      }
    }

    return hasError ? 1 : 0;
  }

  /**
   * Discovers Process IDs listening on specified port.
   *
   * @param port Network port to inspect.
   * @return Set of matching PIDs.
   */
  private Set<Long> findPidsForPort(int port) {
    Set<Long> pids = new HashSet<>();
    if (IS_WINDOWS) {
      findPidsWindows(port, pids);
    } else {
      findPidsUnix(port, pids);
    }
    return pids;
  }

  /**
   * Discovers PIDs listening on port using Windows netstat output.
   *
   * @param port Target port.
   * @param pids Output set to collect PIDs.
   */
  private void findPidsWindows(int port, Set<Long> pids) {
    try {
      var process =
          new ProcessBuilder("cmd.exe", "/c", "netstat -ano -p tcp | findstr :" + port).start();
      try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          var trimmed = line.trim();
          if (trimmed.contains("LISTENING")) {
            var parts = trimmed.split("\\s+");
            if (parts.length >= 5) {
              var localAddr = parts[1];
              if (localAddr.endsWith(":" + port)) {
                try {
                  pids.add(Long.parseLong(parts[parts.length - 1]));
                } catch (NumberFormatException _) {
                }
              }
            }
          }
        }
      }
      process.waitFor();
    } catch (Exception _) {
    }
  }

  /**
   * Discovers PIDs listening on port using lsof or ss on Unix/macOS.
   *
   * @param port Target port.
   * @param pids Output set to collect PIDs.
   */
  private void findPidsUnix(int port, Set<Long> pids) {
    try {
      var pb = new ProcessBuilder("lsof", "-iTCP:" + port, "-sTCP:LISTEN", "-t");
      var process = pb.start();
      try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          try {
            pids.add(Long.parseLong(line.trim()));
          } catch (NumberFormatException _) {
          }
        }
      }
      var exitCode = process.waitFor();
      if (!pids.isEmpty() || exitCode == 0) {
        return;
      }
    } catch (Exception _) {
    }

    try {
      var pb = new ProcessBuilder("ss", "-lptn", "sport = :" + port);
      var process = pb.start();
      try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.contains("pid=")) {
            var idx = line.indexOf("pid=");
            var endIdx = line.indexOf(",", idx);
            if (endIdx == -1)
              endIdx = line.indexOf(")", idx);
            if (idx != -1 && endIdx != -1) {
              var pidStr = line.substring(idx + 4, endIdx);
              try {
                pids.add(Long.parseLong(pidStr));
              } catch (NumberFormatException _) {
              }
            }
          }
        }
      }
      process.waitFor();
    } catch (Exception _) {
    }
  }

  /**
   * Kills process by PID using ProcessHandle or OS command.
   *
   * @param pid Process ID to terminate.
   * @param force Forcefully kill if {@code true}.
   * @return {@code true} if successful, {@code false} otherwise.
   */
  private boolean killProcess(long pid, boolean force) {
    var optHandle = ProcessHandle.of(pid);
    if (optHandle.isPresent()) {
      var handle = optHandle.get();
      if (force) {
        return handle.destroyForcibly();
      }
      return handle.destroy();
    }

    try {
      Process p;
      if (IS_WINDOWS) {
        var flag = force ? "/F" : "";
        p = new ProcessBuilder("taskkill", flag, "/PID", String.valueOf(pid)).start();
      } else {
        var flag = force ? "-9" : "-15";
        p = new ProcessBuilder("kill", flag, String.valueOf(pid)).start();
      }
      return p.waitFor() == 0;
    } catch (Exception _) {
      return false;
    }
  }
}
