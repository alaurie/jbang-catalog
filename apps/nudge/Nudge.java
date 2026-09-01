///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//NATIVE_OPTIONS -O2 --no-fallback


package nudge;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;

/// Utility to keep your desktop presence active (preventing status from changing to "Away").
///
/// Periodically checks for user idle status by inspecting pointer coordinates. When idle, it
/// simulates subtle mouse movements (out-and-back or circular), Shift key presses, or scrolling.
@Command(name = "nudge", mixinStandardHelpOptions = true, version = "nudge 1.2",
    description = "Simulates user activity (mouse movement, key press, scrolling) when idle to keep your"
        + " presence status active.")
@SuppressWarnings("unused")
class Nudge implements Callable<Integer> {

  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
  private final Random random = new Random();
  @Option(names = {"-s", "--seconds"},
      description = "Define in seconds how long to wait between idle checks. Default 300.")
  private int seconds = 300;
  @Option(names = {"-b", "--buffer"},
      description = "Initial buffer delay in seconds before the first check. Default: same as check"
          + " interval.")
  private Integer buffer;
  @Option(names = {"-p", "--pixels"},
      description = "Set how many pixels the mouse should move. Default 5.")
  private int pixels = 5;
  @Option(names = {"-c", "--circular"},
      description = "Move mouse in a circle pattern. Default move out-and-back.")
  private boolean circular;
  @Option(names = {"-m", "--mode"},
      description = "Action mode: mouse, keyboard, both, scroll. Default: mouse.")
  private Mode mode = Mode.mouse;
  @Option(names = {"-r", "--random"}, arity = "2", paramLabel = "<START> <STOP>",
      description = "Execute actions using a random interval between START and STOP seconds. Overrides"
          + " --seconds.")
  private List<Integer> randomRange;
  @Option(names = {"--between"}, arity = "2", paramLabel = "<START> <STOP>",
      description = "Only perform nudges between HH:mm and HH:mm working hours window.")
  private List<String> betweenHours;
  private Robot robot;
  private int mouseDirection = 0;
  private Dimension screenSize;

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
   * Initializes AWT Robot, starts the initial buffer delay, and enters the idle detection loop.
   *
   * @return Status code 0 for success, 1 for errors.
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

    int randStart = 0;
    int randStop = 0;
    if (randomRange != null && !randomRange.isEmpty()) {
      if (randomRange.size() != 2) {
        System.err
            .println("Error: --random requires exactly two integer arguments (e.g. -r 3 10).");
        return 1;
      }
      randStart = randomRange.get(0);
      randStop = randomRange.get(1);
      if (randStart > randStop) {
        System.err
            .println("Error: Random initial number needs to be lower than random limit number.");
        return 1;
      }
    }

    try {
      robot = new Robot();
      robot.setAutoDelay(40);
      screenSize = Toolkit.getDefaultToolkit().getScreenSize();
    } catch (Exception e) {
      System.err.printf("Error initializing Robot: %s%n", e.getMessage());
      return 1;
    }

    boolean isKeyboardEnabled = mode == Mode.keyboard || mode == Mode.both;
    boolean isMouseEnabled = mode == Mode.mouse || mode == Mode.both;
    boolean isScrollEnabled = mode == Mode.scroll;

    System.out.println("--------");
    if (isKeyboardEnabled) {
      log("Keyboard is enabled (Shift key)");
    }
    if (isScrollEnabled) {
      log("Mouse wheel scroll is enabled");
    }
    if (isMouseEnabled) {
      log(String.format("Mouse is enabled, moving %d pixels%s", pixels,
          circular ? " (circularly)" : " (out-and-back)"));
    }

    int initialDelay = buffer != null ? buffer
        : (randomRange != null ? random.nextInt(randStop - randStart + 1) + randStart : seconds);

    if (randomRange != null) {
      log(String.format("Random timing is enabled between %d and %d seconds.", randStart,
          randStop));
    } else {
      log(String.format("Running every %d seconds", seconds));
    }
    log(String.format("Initial start buffer: waiting %d seconds before first check...",
        initialDelay));
    System.out.println("--------");

    Point lastPosition = getMousePosition();

    Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("\nBye bye ;-)\n")));

    try {
      Thread.sleep(initialDelay * 1000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 0;
    }

    while (!Thread.currentThread().isInterrupted()) {
      Point currentPosition = getMousePosition();
      boolean isUserAway = currentPosition != null && currentPosition.equals(lastPosition);

      var isOutsideHours = false;
      if (betweenHours != null && betweenHours.size() == 2) {
        try {
          var now = LocalTime.now();
          var start = LocalTime.parse(betweenHours.get(0));
          var stop = LocalTime.parse(betweenHours.get(1));
          if (now.isBefore(start) || now.isAfter(stop)) {
            isOutsideHours = true;
          }
        } catch (Exception e) {
          System.err.println(
              "Warning: Invalid --between time format. Expected HH:mm (e.g. 09:00 17:00).");
        }
      }

      if (isOutsideHours) {
        log("Outside active hours window (" + betweenHours.get(0) + " - " + betweenHours.get(1)
            + "). Skipping nudge.");
      } else if (isUserAway) {
        log("Idle detection");
        if (isMouseEnabled) {
          currentPosition = moveMouse(currentPosition);
        }
        if (isScrollEnabled) {
          scrollMouse();
        }
        if (isKeyboardEnabled) {
          pressShiftKey();
        }
      } else {
        log("User activity detected");
      }

      lastPosition = currentPosition != null ? currentPosition : lastPosition;

      int delaySeconds;
      if (randomRange != null) {
        delaySeconds = random.nextInt(randStop - randStart + 1) + randStart;
        log(String.format("Delay: %d seconds", delaySeconds));
      } else {
        delaySeconds = seconds;
      }

      System.out.println("--------");

      try {
        //noinspection BusyWait
        Thread.sleep(delaySeconds * 1000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    return 0;
  }

  /**
   * Checks for OS-specific environment warnings (macOS Accessibility, Linux Wayland).
   */
  private void checkEnvironmentWarnings() {
    var os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac")) {
      log("Note: On macOS, ensure Terminal/Java has Accessibility permissions (System Settings ->"
          + " Privacy & Security -> Accessibility).");
    }
    var sessionType = System.getenv("XDG_SESSION_TYPE");
    var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
    if ("wayland".equalsIgnoreCase(sessionType)
        || (waylandDisplay != null && !waylandDisplay.isEmpty())) {
      log("Warning: Wayland display server detected. Wayland compositors may block simulated"
          + " mouse/keyboard events.");
      log("If mouse movement fails, switch to an X11 session or use --mode keyboard.");
    }
  }

  /**
   * Retrieves the current mouse pointer screen coordinates.
   *
   * @return {@link Point} location of cursor, or {@code null} if pointer info unavailable.
   */
  private Point getMousePosition() {
    var info = MouseInfo.getPointerInfo();
    return info != null ? info.getLocation() : null;
  }

  /**
   * Moves mouse cursor according to configured mode (out-and-back or circular).
   *
   * @param current The starting cursor position.
   * @return The updated cursor position after movement.
   */
  private Point moveMouse(Point current) {
    if (current == null)
      return null;

    int step = Math.max(1, pixels);

    int deltaX;
    int deltaY;
    if (circular) {
      deltaX = (mouseDirection == 0 || mouseDirection == 3) ? step : -step;
      deltaY = (mouseDirection == 0 || mouseDirection == 1) ? step : -step;
      mouseDirection = (mouseDirection + 1) % 4;

      var target = clampToScreen(current.x + deltaX, current.y + deltaY);
      robot.mouseMove(target.x, target.y);
    } else {
      deltaX = (current.x + step < screenSize.width) ? step : -step;
      deltaY = (current.y + step < screenSize.height) ? step : -step;

      Point out = clampToScreen(current.x + deltaX, current.y + deltaY);
      robot.mouseMove(out.x, out.y);
      robot.delay(80);

      robot.mouseMove(current.x, current.y);
    }
    robot.delay(50);

    Point actual = getMousePosition();
    if (actual != null && actual.equals(current) && circular) {
      var fallback = clampToScreen(current.x + 10, current.y + 10);
      robot.mouseMove(fallback.x, fallback.y);
      robot.delay(50);
      actual = getMousePosition();
    }

    if (actual != null && actual.equals(current) && !circular) {
      log(String.format("Moved mouse out (%d, %d) and back to (%d, %d)", current.x + step,
          current.y + step, current.x, current.y));
    } else if (actual != null) {
      log(String.format("Moved mouse from (%d, %d) to (%d, %d)", current.x, current.y, actual.x,
          actual.y));
    } else {
      log("Moved mouse");
    }

    if (actual != null && actual.equals(current) && circular) {
      log("Warning: Mouse position did not change. Check system permissions (e.g. macOS Accessibility).");
    }

    return actual;
  }

  /**
   * Clamps given coordinates within screen boundaries.
   *
   * @param x X coordinate.
   * @param y Y coordinate.
   * @return Clamped {@link Point} on screen.
   */
  private Point clampToScreen(int x, int y) {
    int cx = Math.clamp(x, 0, screenSize.width - 1);
    int cy = Math.clamp(y, 0, screenSize.height - 1);
    return new Point(cx, cy);
  }

  /**
   * Simulates a mouse wheel scroll.
   */
  private void scrollMouse() {
    robot.mouseWheel(2);
    robot.delay(50);
    log("Mouse wheel scrolled");
  }

  /**
   * Simulates pressing and releasing the Shift key.
   */
  private void pressShiftKey() {
    robot.keyPress(KeyEvent.VK_SHIFT);
    robot.delay(40);
    robot.keyRelease(KeyEvent.VK_SHIFT);
    robot.delay(50);
    log("Shift key pressed");
  }

  /**
   * Prints timestamped log message to stdout.
   *
   * @param message Message string.
   */
  private void log(String message) {
    System.out.printf("%s %s%n", LocalTime.now().format(TIME_FORMATTER), message);
  }

  /**
   * Available action modes executed upon idle detection.
   */
  public enum Mode {
    mouse, keyboard, both, scroll
  }
}
