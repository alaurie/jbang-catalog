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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Cross-platform CLI utility to find and terminate processes listening on specified network ports.
 *
 * <p>
 * Supports dry-run inspection, force killing, and multi-port batch termination across Windows,
 * macOS, and Linux.
 */
@Command(name = "killport", mixinStandardHelpOptions = true, version = "killport 1.0", description = "Find and terminate processes listening on specified network ports.")
@SuppressWarnings("unused")
class Killport implements Callable<Integer> {

	private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

	@Option(names = { "-f", "--force" }, description = "Forcefully terminate process (SIGKILL / taskkill /F).")
	private boolean force;

	@Option(names = { "-d", "--dry-run" }, description = "Show matching processes without killing them.")
	private boolean dryRun;

	@Parameters(arity = "1..*", paramLabel = "<port>", description = "One or more port numbers to inspect or kill.")
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
	 * @param port Target port number.
	 * @return Set of unique Process IDs.
	 */
	private Set<Long> findPidsForPort(int port) {
		var pids = new HashSet<Long>();
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
	 * @param port Target port number.
	 * @param pids Target set to populate.
	 */
	private void findPidsWindows(int port, Set<Long> pids) {
		try {
			var pb = new ProcessBuilder("cmd.exe", "/c", "netstat -ano -p tcp & netstat -ano -p udp");
			var process = pb.start();
			try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String line;
				var portTarget = ":" + port;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if ((line.startsWith("TCP") || line.startsWith("UDP"))
							&& (line.contains("LISTENING") || line.startsWith("UDP"))) {
						var parts = line.split("\\s+");
						if (parts.length >= 4) {
							var localAddr = parts[1];
							if (localAddr.endsWith(portTarget)) {
								var pidStr = parts[parts.length - 1];
								try {
									var pid = Long.parseLong(pidStr);
									if (pid > 0) {
										pids.add(pid);
									}
								} catch (NumberFormatException ignored) {
								}
							}
						}
					}
				}
			}
		} catch (Exception e) {
			System.err.println("Warning: Failed to execute netstat: " + e.getMessage());
		}
	}

	/**
	 * Discovers PIDs listening on port using lsof or ss on Unix/macOS.
	 *
	 * @param port Target port number.
	 * @param pids Target set to populate.
	 */
	private void findPidsUnix(int port, Set<Long> pids) {
		try {
			var pb = new ProcessBuilder("lsof", "-t", "-i", ":" + port);
			var process = pb.start();
			try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (!line.isEmpty()) {
						try {
							pids.add(Long.parseLong(line));
						} catch (NumberFormatException ignored) {
						}
					}
				}
			}
			process.waitFor();
		} catch (Exception ignored) {
		}

		if (!pids.isEmpty()) {
			return;
		}

		try {
			var pb = new ProcessBuilder("ss", "-tulpn");
			var process = pb.start();
			try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String line;
				var portTarget = ":" + port;
				while ((line = reader.readLine()) != null) {
					if (line.contains(portTarget) && line.contains("pid=")) {
						var idx = line.indexOf("pid=");
						if (idx != -1) {
							var sub = line.substring(idx + 4);
							var endIdx = sub.indexOf(",");
							if (endIdx == -1) {
								endIdx = sub.indexOf(" ");
							}
							if (endIdx != -1) {
								sub = sub.substring(0, endIdx);
							}
							try {
								pids.add(Long.parseLong(sub));
							} catch (NumberFormatException ignored) {
							}
						}
					}
				}
			}
		} catch (Exception ignored) {
		}
	}

	/**
	 * Kills process by PID using ProcessHandle or OS command.
	 *
	 * @param pid Process ID to terminate.
	 * @param force Whether to force termination.
	 * @return {@code true} if process was successfully terminated.
	 */
	private boolean killProcess(long pid, boolean force) {
		var optHandle = ProcessHandle.of(pid);
		if (optHandle.isPresent()) {
			var handle = optHandle.get();
			if (force) {
				return handle.destroyForcibly();
			} else {
				return handle.destroy();
			}
		}

		try {
			Process pb;
			if (IS_WINDOWS) {
				var cmd = force ? new String[] { "taskkill", "/F", "/PID", String.valueOf(pid) }
						: new String[] { "taskkill", "/PID", String.valueOf(pid) };
				pb = new ProcessBuilder(cmd).start();
			} else {
				var sig = force ? "-9" : "-15";
				pb = new ProcessBuilder("kill", sig, String.valueOf(pid)).start();
			}
			return pb.waitFor() == 0;
		} catch (Exception e) {
			return false;
		}
	}
}
