///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.util.concurrent.Callable;

@Command(name = "type-clipboard", mixinStandardHelpOptions = true, version = "type-clipboard 1.0",
    description = "Simulates typing clipboard text (or specified string) into the active window after a countdown delay.")
class type_clipboard implements Callable<Integer> {

  @Option(names = {"-d", "--delay"},
      description = "Countdown delay in seconds before typing starts (default: 5).")
  private int delay = 5;

  @Option(names = {"-s", "--speed"},
      description = "Typing speed delay in milliseconds between keystrokes (default: 10).")
  private int speed = 10;

  @Option(names = {"-t", "--text"},
      description = "Custom text to type instead of reading from the system clipboard.")
  private String customText;

  @Option(names = {"-v", "--verbose"}, description = "Print characters as they are typed.")
  private boolean verbose;

  private Robot robot;

  void main(String... args) {
    var exitCode = new CommandLine(this).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() {
    if (GraphicsEnvironment.isHeadless()) {
      System.err.println(
          "Error: Headless environment detected. java.awt.Robot requires a desktop GUI environment.");
      return 1;
    }

    checkEnvironmentWarnings();

    String textToType = customText;
    if (textToType == null || textToType.isEmpty()) {
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
    System.out.printf("Clipboard text: %d characters (\"%s\")%n", textToType.length(), preview);

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

    System.out.printf("Done! Typed %d characters.%n", typedCount);
    return 0;
  }

  private void checkEnvironmentWarnings() {
    var os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac")) {
      System.out.println(
          "Note: On macOS, ensure Terminal/Java has Accessibility permissions (System Settings -> Privacy & Security -> Accessibility).");
    }
    var sessionType = System.getenv("XDG_SESSION_TYPE");
    var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
    if ("wayland".equalsIgnoreCase(sessionType)
        || (waylandDisplay != null && !waylandDisplay.isEmpty())) {
      System.out.println(
          "Warning: Wayland display server detected. Wayland compositors may block simulated key events.");
    }
  }

  private String readClipboardText() {
    try {
      var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
      if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
        return (String) clipboard.getData(DataFlavor.stringFlavor);
      }
    } catch (Exception e) {
      System.err.printf("Warning: Could not read system clipboard: %s%n", e.getMessage());
    }
    return null;
  }

  private void typeChar(char c) {
    if (c == '\r') {
      return; // Handled by \n
    }
    if (c == '\n') {
      robot.keyPress(KeyEvent.VK_ENTER);
      robot.keyRelease(KeyEvent.VK_ENTER);
      return;
    }
    if (c == '\t') {
      robot.keyPress(KeyEvent.VK_TAB);
      robot.keyRelease(KeyEvent.VK_TAB);
      return;
    }

    boolean shift = false;
    int keyCode = -1;

    if (c >= 'a' && c <= 'z') {
      keyCode = KeyEvent.VK_A + (c - 'a');
    } else if (c >= 'A' && c <= 'Z') {
      keyCode = KeyEvent.VK_A + (c - 'A');
      shift = true;
    } else if (c >= '0' && c <= '9') {
      keyCode = KeyEvent.VK_0 + (c - '0');
    } else {
      switch (c) {
        case ' ' -> keyCode = KeyEvent.VK_SPACE;
        case '-' -> keyCode = KeyEvent.VK_MINUS;
        case '_' -> {
          keyCode = KeyEvent.VK_MINUS;
          shift = true;
        }
        case '=' -> keyCode = KeyEvent.VK_EQUALS;
        case '+' -> {
          keyCode = KeyEvent.VK_EQUALS;
          shift = true;
        }
        case '[' -> keyCode = KeyEvent.VK_OPEN_BRACKET;
        case '{' -> {
          keyCode = KeyEvent.VK_OPEN_BRACKET;
          shift = true;
        }
        case ']' -> keyCode = KeyEvent.VK_CLOSE_BRACKET;
        case '}' -> {
          keyCode = KeyEvent.VK_CLOSE_BRACKET;
          shift = true;
        }
        case '\\' -> keyCode = KeyEvent.VK_BACK_SLASH;
        case '|' -> {
          keyCode = KeyEvent.VK_BACK_SLASH;
          shift = true;
        }
        case ';' -> keyCode = KeyEvent.VK_SEMICOLON;
        case ':' -> {
          keyCode = KeyEvent.VK_SEMICOLON;
          shift = true;
        }
        case '\'' -> keyCode = KeyEvent.VK_QUOTE;
        case '"' -> {
          keyCode = KeyEvent.VK_QUOTE;
          shift = true;
        }
        case ',' -> keyCode = KeyEvent.VK_COMMA;
        case '<' -> {
          keyCode = KeyEvent.VK_COMMA;
          shift = true;
        }
        case '.' -> keyCode = KeyEvent.VK_PERIOD;
        case '>' -> {
          keyCode = KeyEvent.VK_PERIOD;
          shift = true;
        }
        case '/' -> keyCode = KeyEvent.VK_SLASH;
        case '?' -> {
          keyCode = KeyEvent.VK_SLASH;
          shift = true;
        }
        case '`' -> keyCode = KeyEvent.VK_BACK_QUOTE;
        case '~' -> {
          keyCode = KeyEvent.VK_BACK_QUOTE;
          shift = true;
        }
        case '!' -> {
          keyCode = KeyEvent.VK_1;
          shift = true;
        }
        case '@' -> {
          keyCode = KeyEvent.VK_2;
          shift = true;
        }
        case '#' -> {
          keyCode = KeyEvent.VK_3;
          shift = true;
        }
        case '$' -> {
          keyCode = KeyEvent.VK_4;
          shift = true;
        }
        case '%' -> {
          keyCode = KeyEvent.VK_5;
          shift = true;
        }
        case '^' -> {
          keyCode = KeyEvent.VK_6;
          shift = true;
        }
        case '&' -> {
          keyCode = KeyEvent.VK_7;
          shift = true;
        }
        case '*' -> {
          keyCode = KeyEvent.VK_8;
          shift = true;
        }
        case '(' -> {
          keyCode = KeyEvent.VK_9;
          shift = true;
        }
        case ')' -> {
          keyCode = KeyEvent.VK_0;
          shift = true;
        }
        default -> {
          // Unsupported characters ignored or fallback
          return;
        }
      }
    }

    if (keyCode != -1) {
      if (shift) {
        robot.keyPress(KeyEvent.VK_SHIFT);
      }
      robot.keyPress(keyCode);
      robot.keyRelease(keyCode);
      if (shift) {
        robot.keyRelease(KeyEvent.VK_SHIFT);
      }
    }
  }
}
