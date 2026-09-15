///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//FILES META-INF/native-image/typeit/reachability-metadata.json=META-INF/native-image/typeit/reachability-metadata.json
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms4m -Xmx32m -XX:TieredStopAtLevel=1 -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData
//NATIVE_OPTIONS -O2 -march=native --no-fallback --enable-native-access=ALL-UNNAMED

package typeit;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/// Simulates typing clipboard text (or a custom text string) into the active desktop window.
///
/// Supports Wayland and X11 on Linux via direct kernel `/dev/uinput` simulation and CLI helpers
/// (`wtype`, `ydotool`), as well as macOS and Windows via `java.awt.Robot`.
///
/// Designed for remote sessions, VDIs (Citrix, VMware Horizon, RDP), and virtual machines where
/// copy-paste is blocked by security policy, but keyboard input events are accepted.
@Command(name = "typeit", mixinStandardHelpOptions = true, version = "typeit 2.0", description = "Simulates typing clipboard text (or specified string) into the active window after a"
		+ " countdown delay.", footer = { "", "Linux Wayland Note:",
				"  Direct kernel virtual input (/dev/uinput via FFM) requires membership in the",
				"  'input' group: sudo usermod -aG input $USER (requires re-login).",
				"  Alternatively, install wtype or ydotool: sudo apt install wtype ydotool" })
@SuppressWarnings("unused")
class Typeit implements Callable<Integer> {

	/// Supported keyboard input simulation driver backends.
	public enum DriverType {
		AUTO, UINPUT, ROBOT, WTYPE, YDOTOOL
	}

	@Option(names = { "-d", "--delay" }, description = "Countdown delay in seconds before typing starts (default: 5).")
	private int delay = 5;

	@Option(names = { "-s",
			"--speed" }, description = "Typing speed delay in milliseconds between keystrokes (default: 10).")
	private int speed = 10;

	@Option(names = { "-t",
			"--text" }, description = "Custom text to type instead of reading from the system clipboard.")
	private String customText;

	@Option(names = { "-p",
			"--password" }, description = "Prompt securely for password input without echoing characters to terminal.")
	private boolean passwordPrompt;

	@Option(names = { "-e", "--enter" }, description = "Press Enter key after typing completes.")
	private boolean pressEnter;

	@Option(names = { "-v", "--verbose" }, description = "Print characters as they are typed.")
	private boolean verbose;

	@Option(names = { "--driver",
			"--backend" }, description = "Keyboard simulation driver: AUTO, UINPUT, ROBOT, WTYPE, YDOTOOL (default: AUTO).")
	private DriverType driverType = DriverType.AUTO;

	/// Main entry point for the JBang script execution.
  ///
  /// @param args Command-line arguments.
	void main(String... args) {
		int exitCode = new CommandLine(this).execute(args);
		System.exit(exitCode);
	}

	/// Executes the countdown, reads the target text, and simulates character-by-character typing.
  ///
  /// @return Status code 0 for success, 1 for failure or invalid environment.
	@Override
	public Integer call() {
		checkEnvironmentWarnings();

		String textToType = customText;

		if (passwordPrompt) {
			var console = System.console();
			if (console != null) {
				char[] pwd = console.readPassword("Enter password to type: ");
				if (pwd != null) {
					textToType = new String(pwd);
				}
			} else {
				System.err.println("Error: System.console() unavailable for secure password input.");
				return 1;
			}
		} else if (textToType == null || textToType.isEmpty()) {
			textToType = readClipboardText();
		}

		if (textToType == null || textToType.isEmpty()) {
			System.err.println("Error: Clipboard is empty or contains non-text content.");
			return 1;
		}

		KeyboardDriver driver;
		try {
			driver = createKeyboardDriver();
		} catch (Throwable e) {
			System.err.printf("Error initializing keyboard driver: %s%n", e.getMessage());
			printDriverHelpHints();
			return 1;
		}

		try (driver) {
			String preview = textToType.length() > 30 ? textToType.substring(0, 30) + "..." : textToType;
			preview = preview.replace("\n", "\\n").replace("\r", "");
			if (!passwordPrompt) {
				System.out.printf("Text to type: %d characters (\"%s\") [%s driver]%n", textToType.length(),
						preview, driver.name());
			} else {
				System.out.printf("Text to type: %d characters (hidden password) [%s driver]%n",
						textToType.length(), driver.name());
			}

			if (delay > 0) {
				System.out.print("Focus your target window! Typing starts in ");
				for (var i = delay; i > 0; i--) {
					System.out.printf("%d... ", i);
					System.out.flush();
					try {
						Thread.sleep(Duration.ofSeconds(1));
					} catch (InterruptedException _) {
						System.out.println("\nCancelled.");
						Thread.currentThread().interrupt();
						return 0;
					}
				}
				System.out.println();
			}

			System.out.println("Typing...");
			var typedCount = 0;
			for (var ch : textToType.toCharArray()) {
				if (Thread.currentThread().isInterrupted()) {
					System.out.println("\nTyping interrupted.");
					break;
				}
				driver.typeChar(ch);
				typedCount++;
				if (verbose) {
					System.out.print(ch);
					System.out.flush();
				}
			}
			if (verbose) {
				System.out.println();
			}

			if (pressEnter) {
				driver.pressEnter();
			}

			System.out.printf("Done! Typed %d characters.%n", typedCount);
			return 0;
		} catch (Exception e) {
			System.err.printf("Error during typing: %s%n", e.getMessage());
			return 1;
		}
	}

	/// Displays helpful hints when driver initialization fails.
	private void printDriverHelpHints() {
		String osName = System.getProperty("os.name", "").toLowerCase();
		if (osName.contains("linux")) {
			System.err.println();
			System.err.println("Wayland / Linux Troubleshooting:");
			System.err
				.println("  1. Enable kernel uinput access (recommended for GNOME, KDE, Wayland & X11):");
			System.err.println("       sudo usermod -aG input $USER");
			System.err.println("     (Log out and log back in to apply group changes)");
			System.err.println("  2. Or on wlroots compositors (Sway, Hyprland), install 'wtype':");
			System.err.println("       sudo apt install wtype   (or pacman -S wtype)");
			System.err.println("  3. Or start the ydotool background daemon:");
			System.err.println("       systemctl --user start ydotool");
			System.err.println();
		}
	}

	/// Checks for OS-specific desktop security policies (macOS Accessibility, Linux Wayland).
	private void checkEnvironmentWarnings() {
		String osName = System.getProperty("os.name", "").toLowerCase();
		if (osName.contains("mac")) {
			System.out.println(
					"Notice (macOS): If keystrokes are ignored, grant Accessibility permission under:");
			System.out
				.println("  System Settings -> Privacy & Security -> Accessibility -> Terminal / Java");
			System.out.println();
		}
	}

	/// Creates the appropriate keyboard driver based on configuration and operating environment.
  ///
  /// @return Active keyboard simulation driver.
  /// @throws Exception If initialization fails.
	private KeyboardDriver createKeyboardDriver() throws Throwable {
		String osName = System.getProperty("os.name", "").toLowerCase();
		boolean isLinux = osName.contains("linux");
		boolean isWayland = isLinux && isWaylandSession();

		if (driverType == DriverType.UINPUT) {
			return new UInputKeyboardDriver(speed);
		}
		if (driverType == DriverType.WTYPE) {
			return new WTypeKeyboardDriver(speed);
		}
		if (driverType == DriverType.YDOTOOL) {
			return new YDoToolKeyboardDriver(speed);
		}
		if (driverType == DriverType.ROBOT) {
			return new RobotKeyboardDriver(speed);
		}

		// AUTO selection
		if (isLinux) {
			if (UInputKeyboardDriver.isAvailable()) {
				try {
					return new UInputKeyboardDriver(speed);
				} catch (Throwable e) {
					if (verbose) {
						System.err.printf("[debug] uinput driver init failed: %s%n", e.getMessage());
					}
				}
			}
			if (isWayland) {
				if (WTypeKeyboardDriver.isAvailable()) {
					try {
						return new WTypeKeyboardDriver(speed);
					} catch (Throwable _) {
					}
				}
				if (YDoToolKeyboardDriver.isAvailable()) {
					try {
						return new YDoToolKeyboardDriver(speed);
					} catch (Throwable _) {
					}
				}
			}
			try {
				if (!GraphicsEnvironment.isHeadless()) {
					return new RobotKeyboardDriver(speed);
				}
			} catch (Throwable _) {
			}
			throw new IllegalStateException(
					"No compatible keyboard simulation driver found for Linux/Wayland.");
		}

		if (GraphicsEnvironment.isHeadless()) {
			throw new IllegalStateException("Headless environment detected without desktop GUI.");
		}
		return new RobotKeyboardDriver(speed);
	}

	/// Detects whether the current session is running under Wayland.
	private static boolean isWaylandSession() {
		String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
		String sessionType = System.getenv("XDG_SESSION_TYPE");
		return "wayland".equalsIgnoreCase(sessionType)
				|| (waylandDisplay != null && !waylandDisplay.isEmpty());
	}

	/// Reads plain text string content from the system clipboard, supporting Wayland CLI tools
  /// (`wl-paste`), X11 tools (`xclip`, `xsel`), and Java AWT Clipboard.
  ///
  /// @return String content from clipboard, or `null` if clipboard is empty or unreadable.
	private String readClipboardText() {
		String osName = System.getProperty("os.name", "").toLowerCase();
		if (osName.contains("linux")) {
			if (isWaylandSession() || GraphicsEnvironment.isHeadless()) {
				String text = runCommandCaptureOutput("wl-paste", "--no-newline");
				if (text != null && !text.isEmpty()) {
					return text;
				}
			}
		}

		try {
			if (!GraphicsEnvironment.isHeadless()) {
				var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
				if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
					var data = (String) clipboard.getData(DataFlavor.stringFlavor);
					if (data != null && !data.isEmpty()) {
						return data;
					}
				}
			}
		} catch (Throwable e) {
			if (verbose) {
				System.err.printf("[debug] AWT clipboard read error: %s%n", e.getMessage());
			}
		}

		if (osName.contains("linux")) {
			String text = runCommandCaptureOutput("wl-paste", "--no-newline");
			if (text != null && !text.isEmpty()) {
				return text;
			}
			text = runCommandCaptureOutput("xclip", "-selection", "clipboard", "-o");
			if (text != null && !text.isEmpty()) {
				return text;
			}
			text = runCommandCaptureOutput("xsel", "--clipboard", "--output");
			if (text != null && !text.isEmpty()) {
				return text;
			}
		} else if (osName.contains("mac")) {
			String text = runCommandCaptureOutput("pbpaste");
			if (text != null && !text.isEmpty()) {
				return text;
			}
		} else if (osName.contains("win")) {
			String text = runCommandCaptureOutput("powershell", "-NoProfile", "-Command", "Get-Clipboard");
			if (text != null && !text.isEmpty()) {
				return text;
			}
		}

		return null;
	}

	/// Runs a process and captures its standard output as a string.
	private static String runCommandCaptureOutput(String... command) {
		try {
			var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
			var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			boolean finished = process.waitFor(1, TimeUnit.SECONDS);
			if (finished && process.exitValue() == 0) {
				return output;
			}
		} catch (Exception _) {
		}
		return null;
	}

	/// Common interface for keyboard simulation backends.
	interface KeyboardDriver extends AutoCloseable {
		String name();

		void typeChar(char c);

		void pressEnter();

		@Override
		void close();
	}

	/// Pure-Java `/dev/uinput` Virtual Keyboard Driver for Linux (Wayland, X11, Console).
  ///
  /// Uses Java 25 Foreign Function and Memory (FFM) API to create a virtual input device
  /// directly with the Linux kernel without requiring external binaries.
	static class UInputKeyboardDriver implements KeyboardDriver {
		private static final long UI_SET_EVBIT = 0x40045564L;
		private static final long UI_SET_KEYBIT = 0x40045565L;
		private static final long UI_DEV_SETUP = 0x405c5503L;
		private static final long UI_DEV_CREATE = 0x5501L;
		private static final long UI_DEV_DESTROY = 0x5502L;

		private static final short EV_SYN = 0;
		private static final short EV_KEY = 1;
		private static final short SYN_REPORT = 0;

		private static final short KEY_ESC = 1;
		private static final short KEY_1 = 2;
		private static final short KEY_2 = 3;
		private static final short KEY_3 = 4;
		private static final short KEY_4 = 5;
		private static final short KEY_5 = 6;
		private static final short KEY_6 = 7;
		private static final short KEY_7 = 8;
		private static final short KEY_8 = 9;
		private static final short KEY_9 = 10;
		private static final short KEY_0 = 11;
		private static final short KEY_MINUS = 12;
		private static final short KEY_EQUAL = 13;
		private static final short KEY_BACKSPACE = 14;
		private static final short KEY_TAB = 15;
		private static final short KEY_LEFTBRACE = 26;
		private static final short KEY_RIGHTBRACE = 27;
		private static final short KEY_ENTER = 28;
		private static final short KEY_SEMICOLON = 39;
		private static final short KEY_APOSTROPHE = 40;
		private static final short KEY_GRAVE = 41;
		private static final short KEY_LEFTSHIFT = 42;
		private static final short KEY_BACKSLASH = 43;
		private static final short KEY_COMMA = 51;
		private static final short KEY_DOT = 52;
		private static final short KEY_SLASH = 53;
		private static final short KEY_SPACE = 57;

		private final int speed;
		private final int fd;
		private final Arena arena;
		private final MemorySegment eventSegment;
		private final MethodHandle ioctlIntHandle;
		private final MethodHandle writeHandle;
		private final MethodHandle closeHandle;

		public static boolean isAvailable() {
			var uinputPath = Path.of("/dev/uinput");
			return Files.exists(uinputPath) && Files.isWritable(uinputPath);
		}

		public UInputKeyboardDriver(int speed) throws Exception {
			this.speed = Math.max(1, speed);
			var linker = Linker.nativeLinker();
			var lookup = linker.defaultLookup();

			var openHandle = linker.downcallHandle(lookup.find("open").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
			this.ioctlIntHandle = linker.downcallHandle(lookup.find("ioctl").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
							ValueLayout.JAVA_LONG));
			var ioctlPtrHandle = linker.downcallHandle(lookup.find("ioctl").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
							ValueLayout.ADDRESS));
			this.writeHandle = linker.downcallHandle(lookup.find("write").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
							ValueLayout.JAVA_LONG));
			this.closeHandle = linker.downcallHandle(lookup.find("close").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

			this.arena = Arena.ofConfined();
			int O_WRONLY = 1;
			int O_NONBLOCK = 04000;
			var pathSegment = arena.allocateFrom("/dev/uinput");
			try {
				this.fd = (int) openHandle.invokeExact(pathSegment, O_WRONLY | O_NONBLOCK);
			} catch (Throwable t) {
				arena.close();
				throw new RuntimeException("Failed to open /dev/uinput", t);
			}
			if (this.fd < 0) {
				arena.close();
				throw new IllegalAccessException(
						"Permission denied or failed to open /dev/uinput (fd=" + fd + ")");
			}

			try {
				int _ = (int) ioctlIntHandle.invokeExact(fd, UI_SET_EVBIT, (long) EV_KEY);
				int _ = (int) ioctlIntHandle.invokeExact(fd, UI_SET_EVBIT, (long) EV_SYN);

				for (long k = 1; k <= 255; k++) {
					int _ = (int) ioctlIntHandle.invokeExact(fd, UI_SET_KEYBIT, k);
				}

				var setupSegment = arena.allocate(92);
				setupSegment.set(ValueLayout.JAVA_SHORT, 0, (short) 0x03); // BUS_USB
				setupSegment.set(ValueLayout.JAVA_SHORT, 2, (short) 0x1234);
				setupSegment.set(ValueLayout.JAVA_SHORT, 4, (short) 0x5678);
				setupSegment.set(ValueLayout.JAVA_SHORT, 6, (short) 1);
				var nameBytes = "Typeit Virtual Keyboard".getBytes(StandardCharsets.UTF_8);
				for (int i = 0; i < nameBytes.length; i++) {
					setupSegment.set(ValueLayout.JAVA_BYTE, 8 + i, nameBytes[i]);
				}

				int _ = (int) ioctlPtrHandle.invokeExact(fd, UI_DEV_SETUP, setupSegment);
				int _ = (int) ioctlIntHandle.invokeExact(fd, UI_DEV_CREATE, 0L);

				this.eventSegment = arena.allocate(24);
				Thread.sleep(Duration.ofMillis(100)); // Allow compositor/udev to register virtual device
			} catch (Throwable t) {
				close();
				throw new RuntimeException("Failed to initialize uinput device", t);
			}
		}

		@Override
		public String name() {
			return "uinput (Linux kernel)";
		}

		private void emitEvent(short code, int value) {
			try {
				eventSegment.fill((byte) 0);
				eventSegment.set(ValueLayout.JAVA_SHORT, 16, EV_KEY);
				eventSegment.set(ValueLayout.JAVA_SHORT, 18, code);
				eventSegment.set(ValueLayout.JAVA_INT, 20, value);
				long _ = (long) writeHandle.invokeExact(fd, eventSegment, 24L);

				eventSegment.fill((byte) 0);
				eventSegment.set(ValueLayout.JAVA_SHORT, 16, EV_SYN);
				eventSegment.set(ValueLayout.JAVA_SHORT, 18, SYN_REPORT);
				eventSegment.set(ValueLayout.JAVA_INT, 20, 0);
				long _ = (long) writeHandle.invokeExact(fd, eventSegment, 24L);
			} catch (Throwable t) {
				throw new RuntimeException("Failed to emit uinput event", t);
			}
		}

		private void sendKey(short code, boolean shifted) {
			if (shifted) {
				emitEvent(KEY_LEFTSHIFT, 1);
			}
			emitEvent(code, 1);
			emitEvent(code, 0);
			if (shifted) {
				emitEvent(KEY_LEFTSHIFT, 0);
			}
			try {
				Thread.sleep(Duration.ofMillis(speed));
			} catch (InterruptedException _) {
				Thread.currentThread().interrupt();
			}
		}

		@Override
		public void typeChar(char c) {
			if (c == '\n') {
				sendKey(KEY_ENTER, false);
				return;
			}
			if (c == '\r') {
				return;
			}
			if (c == '\t') {
				sendKey(KEY_TAB, false);
				return;
			}
			if (c == ' ') {
				sendKey(KEY_SPACE, false);
				return;
			}

			if (c >= 'a' && c <= 'z') {
				sendKey(getLetterKeycode(c), false);
				return;
			}
			if (c >= 'A' && c <= 'Z') {
				sendKey(getLetterKeycode(c), true);
				return;
			}
			if (c >= '1' && c <= '9') {
				sendKey((short) (KEY_1 + (c - '1')), false);
				return;
			}
			if (c == '0') {
				sendKey(KEY_0, false);
				return;
			}

			switch (c) {
			case '!' -> sendKey(KEY_1, true);
			case '@' -> sendKey(KEY_2, true);
			case '#' -> sendKey(KEY_3, true);
			case '$' -> sendKey(KEY_4, true);
			case '%' -> sendKey(KEY_5, true);
			case '^' -> sendKey(KEY_6, true);
			case '&' -> sendKey(KEY_7, true);
			case '*' -> sendKey(KEY_8, true);
			case '(' -> sendKey(KEY_9, true);
			case ')' -> sendKey(KEY_0, true);
			case '-' -> sendKey(KEY_MINUS, false);
			case '_' -> sendKey(KEY_MINUS, true);
			case '=' -> sendKey(KEY_EQUAL, false);
			case '+' -> sendKey(KEY_EQUAL, true);
			case '[' -> sendKey(KEY_LEFTBRACE, false);
			case '{' -> sendKey(KEY_LEFTBRACE, true);
			case ']' -> sendKey(KEY_RIGHTBRACE, false);
			case '}' -> sendKey(KEY_RIGHTBRACE, true);
			case '\\' -> sendKey(KEY_BACKSLASH, false);
			case '|' -> sendKey(KEY_BACKSLASH, true);
			case ';' -> sendKey(KEY_SEMICOLON, false);
			case ':' -> sendKey(KEY_SEMICOLON, true);
			case '\'' -> sendKey(KEY_APOSTROPHE, false);
			case '"' -> sendKey(KEY_APOSTROPHE, true);
			case ',' -> sendKey(KEY_COMMA, false);
			case '<' -> sendKey(KEY_COMMA, true);
			case '.' -> sendKey(KEY_DOT, false);
			case '>' -> sendKey(KEY_DOT, true);
			case '/' -> sendKey(KEY_SLASH, false);
			case '?' -> sendKey(KEY_SLASH, true);
			case '`' -> sendKey(KEY_GRAVE, false);
			case '~' -> sendKey(KEY_GRAVE, true);
			default -> System.err.printf("Skipping unmappable character: '%c' (0x%04x)%n", c, (int) c);
			}
		}

		private static short getLetterKeycode(char c) {
			char lower = Character.toLowerCase(c);
			return switch (lower) {
			case 'q' -> 16;
			case 'w' -> 17;
			case 'e' -> 18;
			case 'r' -> 19;
			case 't' -> 20;
			case 'y' -> 21;
			case 'u' -> 22;
			case 'i' -> 23;
			case 'o' -> 24;
			case 'p' -> 25;
			case 'a' -> 30;
			case 's' -> 31;
			case 'd' -> 32;
			case 'f' -> 33;
			case 'g' -> 34;
			case 'h' -> 35;
			case 'j' -> 36;
			case 'k' -> 37;
			case 'l' -> 38;
			case 'z' -> 44;
			case 'x' -> 45;
			case 'c' -> 46;
			case 'v' -> 47;
			case 'b' -> 48;
			case 'n' -> 49;
			case 'm' -> 50;
			default -> 0;
			};
		}

		@Override
		public void pressEnter() {
			sendKey(KEY_ENTER, false);
		}

		@Override
		public void close() {
			if (fd >= 0) {
				try {
					int _ = (int) ioctlIntHandle.invokeExact(fd, UI_DEV_DESTROY, 0L);
					int _ = (int) closeHandle.invokeExact(fd);
				} catch (Throwable _) {
				}
			}
			arena.close();
		}
	}

	/// Wayland `wtype` CLI Driver (for wlroots compositors: Sway, Hyprland, Wayfire).
	static class WTypeKeyboardDriver implements KeyboardDriver {
		private final int speed;

		public static boolean isAvailable() {
			return isExecutableOnPath("wtype");
		}

		public WTypeKeyboardDriver(int speed) {
			this.speed = Math.max(1, speed);
			if (!isAvailable()) {
				throw new IllegalStateException("'wtype' binary not found on PATH.");
			}
		}

		@Override
		public String name() {
			return "wtype (Wayland virtual keyboard)";
		}

		@Override
		public void typeChar(char c) {
			try {
				if (c == '\n') {
					new ProcessBuilder("wtype", "-k", "Return").start().waitFor(500, TimeUnit.MILLISECONDS);
				} else if (c == '\t') {
					new ProcessBuilder("wtype", "-k", "Tab").start().waitFor(500, TimeUnit.MILLISECONDS);
				} else if (c != '\r') {
					new ProcessBuilder("wtype", "-d", String.valueOf(speed), String.valueOf(c)).start()
						.waitFor(500, TimeUnit.MILLISECONDS);
				}
				Thread.sleep(Duration.ofMillis(speed));
			} catch (Exception _) {
			}
		}

		@Override
		public void pressEnter() {
			try {
				new ProcessBuilder("wtype", "-k", "Return").start().waitFor(500, TimeUnit.MILLISECONDS);
			} catch (Exception _) {
			}
		}

		@Override
		public void close() {
		}
	}

	/// `ydotool` CLI Driver.
	static class YDoToolKeyboardDriver implements KeyboardDriver {
		private final int speed;

		public static boolean isAvailable() {
			return isExecutableOnPath("ydotool");
		}

		public YDoToolKeyboardDriver(int speed) {
			this.speed = Math.max(1, speed);
			if (!isAvailable()) {
				throw new IllegalStateException("'ydotool' binary not found on PATH.");
			}
		}

		@Override
		public String name() {
			return "ydotool";
		}

		@Override
		public void typeChar(char c) {
			try {
				if (c == '\n') {
					new ProcessBuilder("ydotool", "key", "28:1", "28:0").start()
						.waitFor(500,
								TimeUnit.MILLISECONDS);
				} else if (c == '\t') {
					new ProcessBuilder("ydotool", "key", "15:1", "15:0").start()
						.waitFor(500,
								TimeUnit.MILLISECONDS);
				} else if (c != '\r') {
					new ProcessBuilder("ydotool", "type", "-d", String.valueOf(speed), String.valueOf(c))
						.start()
						.waitFor(500, TimeUnit.MILLISECONDS);
				}
				Thread.sleep(Duration.ofMillis(speed));
			} catch (Exception _) {
			}
		}

		@Override
		public void pressEnter() {
			try {
				new ProcessBuilder("ydotool", "key", "28:1", "28:0").start()
					.waitFor(500,
							TimeUnit.MILLISECONDS);
			} catch (Exception _) {
			}
		}

		@Override
		public void close() {
		}
	}

	/// Standard `java.awt.Robot` Driver (for Windows, macOS, and Linux X11).
	static class RobotKeyboardDriver implements KeyboardDriver {
		private final Robot robot;

		public RobotKeyboardDriver(int speed) throws Exception {
			this.robot = new Robot();
			this.robot.setAutoDelay(Math.max(1, speed));
		}

		@Override
		public String name() {
			return "Robot (AWT)";
		}

		@Override
		public void typeChar(char c) {
			if (c == '\n') {
				robot.keyPress(KeyEvent.VK_ENTER);
				robot.keyRelease(KeyEvent.VK_ENTER);
				return;
			}
			if (c == '\r') {
				return;
			}
			if (c == '\t') {
				robot.keyPress(KeyEvent.VK_TAB);
				robot.keyRelease(KeyEvent.VK_TAB);
				return;
			}

			boolean shift = Character.isUpperCase(c);
			int keyCode = getKeyCode(c);

			if (keyCode != KeyEvent.VK_UNDEFINED) {
				if (shift) {
					robot.keyPress(KeyEvent.VK_SHIFT);
				}
				try {
					robot.keyPress(keyCode);
					robot.keyRelease(keyCode);
				} finally {
					if (shift) {
						robot.keyRelease(KeyEvent.VK_SHIFT);
					}
				}
			} else {
				typeSymbolChar(c);
			}
		}

		private int getKeyCode(char c) {
			char upper = Character.toUpperCase(c);
			if (upper >= 'A' && upper <= 'Z') {
				return KeyEvent.VK_A + (upper - 'A');
			}
			if (c >= '0' && c <= '9') {
				return KeyEvent.VK_0 + (c - '0');
			}
			if (c == ' ') {
				return KeyEvent.VK_SPACE;
			}
			return KeyEvent.VK_UNDEFINED;
		}

		private void typeSymbolChar(char c) {
			switch (c) {
			case '!' -> typeShifted(KeyEvent.VK_1);
			case '@' -> typeShifted(KeyEvent.VK_2);
			case '#' -> typeShifted(KeyEvent.VK_3);
			case '$' -> typeShifted(KeyEvent.VK_4);
			case '%' -> typeShifted(KeyEvent.VK_5);
			case '^' -> typeShifted(KeyEvent.VK_6);
			case '&' -> typeShifted(KeyEvent.VK_7);
			case '*' -> typeShifted(KeyEvent.VK_8);
			case '(' -> typeShifted(KeyEvent.VK_9);
			case ')' -> typeShifted(KeyEvent.VK_0);
			case '-' -> typePlain(KeyEvent.VK_MINUS);
			case '_' -> typeShifted(KeyEvent.VK_MINUS);
			case '=' -> typePlain(KeyEvent.VK_EQUALS);
			case '+' -> typeShifted(KeyEvent.VK_EQUALS);
			case '[' -> typePlain(KeyEvent.VK_OPEN_BRACKET);
			case '{' -> typeShifted(KeyEvent.VK_OPEN_BRACKET);
			case ']' -> typePlain(KeyEvent.VK_CLOSE_BRACKET);
			case '}' -> typeShifted(KeyEvent.VK_CLOSE_BRACKET);
			case '\\' -> typePlain(KeyEvent.VK_BACK_SLASH);
			case '|' -> typeShifted(KeyEvent.VK_BACK_SLASH);
			case ';' -> typePlain(KeyEvent.VK_SEMICOLON);
			case ':' -> typeShifted(KeyEvent.VK_SEMICOLON);
			case '\'' -> typePlain(KeyEvent.VK_QUOTE);
			case '"' -> typeShifted(KeyEvent.VK_QUOTE);
			case ',' -> typePlain(KeyEvent.VK_COMMA);
			case '<' -> typeShifted(KeyEvent.VK_COMMA);
			case '.' -> typePlain(KeyEvent.VK_PERIOD);
			case '>' -> typeShifted(KeyEvent.VK_PERIOD);
			case '/' -> typePlain(KeyEvent.VK_SLASH);
			case '?' -> typeShifted(KeyEvent.VK_SLASH);
			case '`' -> typePlain(KeyEvent.VK_BACK_QUOTE);
			case '~' -> typeShifted(KeyEvent.VK_BACK_QUOTE);
			default -> System.err.printf("Skipping unmappable character: '%c' (0x%04x)%n", c, (int) c);
			}
		}

		private void typePlain(int keyCode) {
			robot.keyPress(keyCode);
			robot.keyRelease(keyCode);
		}

		private void typeShifted(int keyCode) {
			robot.keyPress(KeyEvent.VK_SHIFT);
			try {
				robot.keyPress(keyCode);
				robot.keyRelease(keyCode);
			} finally {
				robot.keyRelease(KeyEvent.VK_SHIFT);
			}
		}

		@Override
		public void pressEnter() {
			robot.keyPress(KeyEvent.VK_ENTER);
			robot.keyRelease(KeyEvent.VK_ENTER);
		}

		@Override
		public void close() {
		}
	}

	/// Helper to verify if an executable exists on the system PATH.
	private static boolean isExecutableOnPath(String executable) {
		String pathEnv = System.getenv("PATH");
		if (pathEnv == null) {
			return false;
		}
		for (String dir : pathEnv.split(File.pathSeparator)) {
			var file = new File(dir, executable);
			if (file.canExecute()) {
				return true;
			}
		}
		return false;
	}
}
