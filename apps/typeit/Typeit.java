///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//NATIVE_OPTIONS -O2 --no-fallback

import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Simulates typing clipboard text (or a custom text string) into the active desktop window.
 *
 * <p>
 * Designed for remote sessions, VDIs (Citrix, VMware Horizon, RDP), and virtual machines where
 * copy-paste is blocked by security policy, but keyboard input events are accepted.
 */
@Command(name = "typeit", mixinStandardHelpOptions = true, version = "typeit 1.1",
    description = "Simulates typing clipboard text (or specified string) into the active window after a"
        + " countdown delay.")
@SuppressWarnings("unused")
class Typeit implements Callable<Integer> {

  @Option(names = {"-d", "--delay"},
      description = "Countdown delay in seconds before typing starts (default: 5).")
  private int delay = 5;

  @Option(names = {"-s", "--speed"},
      description = "Typing speed delay in milliseconds between keystrokes (default: 10).")
  private int speed = 10;

  @Option(names = {"-t", "--text"},
      description = "Custom text to type instead of reading from the system clipboard.")
  private String customText;

  @Option(names = {"-p", "--password"},
      description = "Prompt securely for password input without echoing characters to terminal.")
  private boolean passwordPrompt;

  @Option(names = {"-e", "--enter"}, description = "Press Enter key after typing completes.")
  private boolean pressEnter;

  @Option(names = {"-v", "--verbose"}, description = "Print characters as they are typed.")
  private boolean verbose;

  private Robot robot;

  /**
   * Main entry point for the JBang script execution.
   *
   * @param args Command-line arguments.
   */
  void main(String... args) {
    int exitCode = new CommandLine(this).execute(args);
    System.exit(exitCode);
  }

  /**
   * Executes the countdown, reads the target text, and simulates character-by-character typing.
   *
   * @return Status code 0 for success, 1 for failure or invalid environment.
   */
  @Override
  public Integer call() {
    if (GraphicsEnvironment.isHeadless()) {
      System.err
          .println("Error: Headless environment detected. java.awt.Robot requires a desktop GUI"
              + " environment.");
      return 1;
    }

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

    try {
      robot = new Robot();
      robot.setAutoDelay(Math.max(1, speed));
    } catch (Exception e) {
      System.err.printf("Error initializing Robot: %s%n", e.getMessage());
      return 1;
    }

    String preview = textToType.length() > 30 ? textToType.substring(0, 30) + "..." : textToType;
    preview = preview.replace("\n", "\\n").replace("\r", "");
    if (!passwordPrompt) {
      System.out.printf("Text to type: %d characters (\"%s\")%n", textToType.length(), preview);
    } else {
      System.out.printf("Text to type: %d characters (hidden password)%n", textToType.length());
    }

    if (delay > 0) {
      System.out.print("Focus your target window! Typing starts in ");
      for (var i = delay; i > 0; i--) {
        System.out.printf("%d... ", i);
        System.out.flush();
        try {
          Thread.sleep(1000L);
        } catch (InterruptedException e) {
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
      typeChar(ch);
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
      robot.keyPress(KeyEvent.VK_ENTER);
      robot.keyRelease(KeyEvent.VK_ENTER);
    }

    System.out.printf("Done! Typed %d characters.%n", typedCount);
    return 0;
  }

  /** Checks for OS-specific desktop security policies (macOS Accessibility, Linux Wayland). */
  private void checkEnvironmentWarnings() {
    String osName = System.getProperty("os.name", "").toLowerCase();
    if (osName.contains("mac")) {
      System.out.println(
          "Notice (macOS): If keystrokes are ignored, grant Accessibility permission under:");
      System.out
          .println("  System Settings -> Privacy & Security -> Accessibility -> Terminal / Java");
      System.out.println();
    } else if (osName.contains("linux")) {
      String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
      String sessionType = System.getenv("XDG_SESSION_TYPE");
      if ("wayland".equalsIgnoreCase(sessionType) || waylandDisplay != null) {
        System.out.println(
            "Notice (Linux): Wayland session detected. java.awt.Robot requires X11 / XWayland.");
        System.out.println();
      }
    }
  }

  /**
   * Reads plain text string content from the system clipboard.
   *
   * @return String content from clipboard, or {@code null} if clipboard is empty or non-text.
   */
  private String readClipboardText() {
    try {
      var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
      if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
        return (String) clipboard.getData(DataFlavor.stringFlavor);
      }
    } catch (Exception e) {
      System.err.printf("Error reading clipboard: %s%n", e.getMessage());
    }
    return null;
  }

  /**
   * Translates a single character into AWT virtual key codes and simulates key press and release.
   *
   * @param c The character to type.
   */
  private void typeChar(char c) {
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

  /**
   * Maps standard ASCII character to VK keycode.
   *
   * @param c Character to map.
   * @return VK constant or {@code VK_UNDEFINED}.
   */
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

  /**
   * Maps common ASCII punctuation and special characters to key stroke sequences.
   *
   * @param c Symbol character.
   */
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
}
