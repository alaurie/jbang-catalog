///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms4m -Xmx32m -XX:TieredStopAtLevel=1 -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData
//NATIVE_OPTIONS -O2 -march=native --no-fallback

package nudge;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/// Utility to keep your desktop presence active (preventing status from changing to "Away").
///
/// Periodically checks for user idle status. When idle, it simulates subtle mouse movements
/// (out-and-back or circular), Shift key presses, or scrolling.
///
/// Supports Linux Wayland natively via direct kernel virtual input (`/dev/uinput` via FFM),
/// CLI tools (`ydotool`, `wtype`, `dotool`), D-Bus idle inhibition, and standard `java.awt.Robot`
/// on X11, macOS, and Windows.
@Command(name = "nudge", mixinStandardHelpOptions = true, version = "nudge 1.3",
    description = "Simulates user activity (mouse movement, key press, scrolling) when idle to keep your"
        + " presence status active.")
@SuppressWarnings("unused")
class Nudge implements Callable<Integer> {

  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
  private final Random random = new Random();

  @Option(names = {"-s", "--seconds"},
      description = "Define in seconds how long to wait between idle checks. Default: 300.")
  private int seconds = 300;

  @Option(names = {"-b", "--buffer"},
      description = "Initial buffer delay in seconds before the first check. Default: same as check"
          + " interval.")
  private Integer buffer;

  @Option(names = {"-p", "--pixels"},
      description = "Set how many pixels the mouse should move. Default: 5.")
  private int pixels = 5;

  @Option(names = {"-c", "--circular"},
      description = "Move mouse in a circle pattern. Default: move out-and-back.")
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

  private InputBackend backend;
  private int mouseDirection = 0;

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
   * Initializes the appropriate input backend, starts the initial delay buffer, and enters the idle
   * detection loop.
   *
   * @return Status code 0 for success, 1 for errors.
   */
  @Override
  public Integer call() {
    int randStart = 0;
    int randStop = 0;
    if (randomRange != null && !randomRange.isEmpty()) {
      if (randomRange.size() != 2) {
        System.err
            .println("Error: --random requires exactly two integer arguments (e.g. -r 3 10).");
        return 1;
      }
      randStart = randomRange.getFirst();
      randStop = randomRange.getLast();
      if (randStart > randStop) {
        System.err
            .println("Error: Random initial number needs to be lower than random limit number.");
        return 1;
      }
    }

    backend = initializeBackend();
    if (backend == null) {
      System.err.println("Error: Could not initialize any input or presence backend.");
      return 1;
    }

    boolean isKeyboardEnabled = mode == Mode.keyboard || mode == Mode.both;
    boolean isMouseEnabled = mode == Mode.mouse || mode == Mode.both;
    boolean isScrollEnabled = mode == Mode.scroll;

    System.out.println("--------");
    log("Backend: " + backend.name());
    if (isKeyboardEnabled) {
      log("Keyboard is enabled (Shift key)");
    }
    if (isScrollEnabled) {
      log("Mouse wheel scroll is enabled");
    }
    if (isMouseEnabled) {
      log("Mouse is enabled, moving %d pixels%s".formatted(pixels,
          circular ? " (circularly)" : " (out-and-back)"));
    }

    int initialDelay = buffer != null ? buffer
        : (randomRange != null ? random.nextInt(randStop - randStart + 1) + randStart : seconds);

    if (randomRange != null) {
      log("Random timing is enabled between %d and %d seconds.".formatted(randStart, randStop));
    } else {
      log("Running every %d seconds".formatted(seconds));
    }
    log("Initial start buffer: waiting %d seconds before first check...".formatted(initialDelay));
    System.out.println("--------");

    Point lastPosition = backend.getPointerPosition();

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      if (backend != null) {
        try {
          backend.close();
        } catch (Exception _) {
          // Cleanup
        }
      }
      System.out.println("\nBye bye ;-)\n");
    }));

    try {
      Thread.sleep(initialDelay * 1000L);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return 0;
    }

    while (!Thread.currentThread().isInterrupted()) {
      Point currentPosition = backend.getPointerPosition();
      boolean isUserAway;

      if (currentPosition != null && lastPosition != null) {
        isUserAway = currentPosition.equals(lastPosition);
      } else {
        // Pointer tracking unavailable on native Wayland; execute scheduled nudge
        isUserAway = true;
      }

      var isOutsideHours = false;
      if (betweenHours != null && betweenHours.size() == 2) {
        try {
          var now = LocalTime.now();
          var start = LocalTime.parse(betweenHours.getFirst());
          var stop = LocalTime.parse(betweenHours.getLast());
          if (now.isBefore(start) || now.isAfter(stop)) {
            isOutsideHours = true;
          }
        } catch (Exception _) {
          System.err.println(
              "Warning: Invalid --between time format. Expected HH:mm (e.g. 09:00 17:00).");
        }
      }

      if (isOutsideHours) {
        log("Outside active hours window (" + betweenHours.getFirst() + " - "
            + betweenHours.getLast() + "). Skipping nudge.");
      } else if (isUserAway) {
        log(currentPosition != null ? "Idle detection" : "Idle check (scheduled interval)");
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

      int delaySeconds =
          randomRange != null ? random.nextInt(randStop - randStart + 1) + randStart : seconds;
      log("Delay: %d seconds".formatted(delaySeconds));
      System.out.println("--------");

      try {
        //noinspection BusyWait
        Thread.sleep(delaySeconds * 1000L);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    return 0;
  }

  /**
   * Selects and initializes the most capable presence/input backend for the current platform.
   *
   * @return Active {@link InputBackend} instance.
   */
  private InputBackend initializeBackend() {
    var os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac")) {
      log("Note: On macOS, ensure Terminal/Java has Accessibility permissions (System Settings ->"
          + " Privacy & Security -> Accessibility).");
    }

    var isWayland = isWaylandSession();
    if (isWayland) {
      // 1. Try direct Linux kernel virtual input (/dev/uinput via FFM)
      try {
        var uinput = UinputBackend.create();
        if (uinput != null) {
          return uinput;
        }
      } catch (Exception _) {
        // uinput creation failed, attempt CLI tools or fallback
      }

      // 2. Try CLI simulation tools (ydotool, wtype, dotool)
      var cliBackend = CliToolBackend.detect();
      if (cliBackend != null) {
        return cliBackend;
      }

      // 3. Fallback: D-Bus Inhibit + AWT Robot if DISPLAY is set
      log("Notice: Wayland session detected without /dev/uinput or CLI simulation tools.");
      log("To enable direct kernel input simulation (for Teams/Slack):");
      log("  1. Add user to input group: sudo usermod -aG input $USER");
      log("  2. Or install ydotool: sudo apt install ydotool");
      log("Activating D-Bus ScreenSaver Inhibit to keep desktop session active.");

      return FallbackWaylandBackend.create();
    }

    if (GraphicsEnvironment.isHeadless()) {
      System.err
          .println("Error: Headless environment detected. Desktop GUI environment is required.");
      return null;
    }

    try {
      return new AwtRobotBackend();
    } catch (Exception e) {
      System.err.printf("Error initializing AWT Robot: %s%n", e.getMessage());
      return null;
    }
  }

  /**
   * Determines if the current environment is running a Wayland display server.
   *
   * @return {@code true} if Wayland is detected.
   */
  private static boolean isWaylandSession() {
    var sessionType = System.getenv("XDG_SESSION_TYPE");
    var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
    return "wayland".equalsIgnoreCase(sessionType)
        || (waylandDisplay != null && !waylandDisplay.isBlank());
  }

  /**
   * Checks whether a system CLI executable is available in PATH.
   *
   * @param command Command name.
   * @return {@code true} if command is found.
   */
  private static boolean hasCommand(String command) {
    try {
      var process =
          new ProcessBuilder("which", command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD).start();
      return process.waitFor() == 0;
    } catch (Exception _) {
      return false;
    }
  }

  /**
   * Moves mouse cursor according to configured mode (out-and-back or circular).
   *
   * @param current The starting cursor position if known.
   * @return The updated cursor position after movement, or {@code null}.
   */
  private Point moveMouse(Point current) {
    int step = Math.max(1, pixels);
    int deltaX;
    int deltaY;

    if (circular) {
      deltaX = (mouseDirection == 0 || mouseDirection == 3) ? step : -step;
      deltaY = (mouseDirection == 0 || mouseDirection == 1) ? step : -step;
      mouseDirection = (mouseDirection + 1) % 4;
      backend.moveMouse(deltaX, deltaY);
      log("Moved mouse relative (%+d, %+d)".formatted(deltaX, deltaY));
    } else {
      backend.moveMouse(step, step);
      try {
        Thread.sleep(80);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
      backend.moveMouse(-step, -step);
      log("Moved mouse relative (%+d, %+d) and back".formatted(step, step));
    }

    return backend.getPointerPosition();
  }

  /** Simulates a mouse wheel scroll. */
  private void scrollMouse() {
    backend.scrollMouse(2);
    log("Mouse wheel scrolled");
  }

  /** Simulates pressing and releasing the Shift key. */
  private void pressShiftKey() {
    backend.pressShiftKey();
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

  /// Available action modes executed upon idle detection.
  public enum Mode {
    mouse, keyboard, both, scroll
  }

  /// Abstraction for input simulation and desktop presence.
  private interface InputBackend extends AutoCloseable {
    void moveMouse(int dx, int dy);

    void scrollMouse(int clicks);

    void pressShiftKey();

    Point getPointerPosition();

    String name();

    @Override
    default void close() {}
  }

  /// Native Linux kernel virtual input backend via /dev/uinput using Foreign Function & Memory.
  private static final class UinputBackend implements InputBackend {
    private static final int UI_SET_EVBIT = 0x40045564;
    private static final int UI_SET_KEYBIT = 0x40045565;
    private static final int UI_SET_RELBIT = 0x40045566;
    private static final int UI_DEV_SETUP = 0x405c5503;
    private static final int UI_DEV_CREATE = 0x5501;
    private static final int UI_DEV_DESTROY = 0x5502;

    private static final short EV_SYN = 0x00;
    private static final short EV_KEY = 0x01;
    private static final short EV_REL = 0x02;

    private static final short REL_X = 0x00;
    private static final short REL_Y = 0x01;
    private static final short REL_WHEEL = 0x08;
    private static final short KEY_LEFTSHIFT = 42;

    private final int fd;
    private final MethodHandle ioctl;
    private final MethodHandle write;
    private final MethodHandle close;

    private UinputBackend(int fd, MethodHandle ioctl, MethodHandle write, MethodHandle close) {
      this.fd = fd;
      this.ioctl = ioctl;
      this.write = write;
      this.close = close;
    }

    public static UinputBackend create() {
      try {
        var linker = Linker.nativeLinker();
        var lookup = linker.defaultLookup();

        var openHandle = linker.downcallHandle(lookup.find("open").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        var ioctlHandle = linker.downcallHandle(lookup.find("ioctl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG));
        var writeHandle = linker.downcallHandle(lookup.find("write").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG));
        var closeHandle = linker.downcallHandle(lookup.find("close").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        int fd;
        try (var arena = Arena.ofConfined()) {
          var path = arena.allocateFrom("/dev/uinput");
          // O_WRONLY | O_NONBLOCK = 01 | 04000 = 2049
          fd = (int) openHandle.invokeExact(path, 2049);
          if (fd < 0) {
            return null;
          }

          ioctlHandle.invoke(fd, (long) UI_SET_EVBIT, (long) EV_REL);
          ioctlHandle.invoke(fd, (long) UI_SET_RELBIT, (long) REL_X);
          ioctlHandle.invoke(fd, (long) UI_SET_RELBIT, (long) REL_Y);
          ioctlHandle.invoke(fd, (long) UI_SET_RELBIT, (long) REL_WHEEL);
          ioctlHandle.invoke(fd, (long) UI_SET_EVBIT, (long) EV_KEY);
          ioctlHandle.invoke(fd, (long) UI_SET_KEYBIT, (long) KEY_LEFTSHIFT);

          var setupBuf = arena.allocate(92);
          setupBuf.set(ValueLayout.JAVA_SHORT, 0, (short) 0x03); // BUS_USB
          setupBuf.set(ValueLayout.JAVA_SHORT, 2, (short) 0x01); // vendor
          setupBuf.set(ValueLayout.JAVA_SHORT, 4, (short) 0x01); // product
          setupBuf.set(ValueLayout.JAVA_SHORT, 6, (short) 0x01); // version
          setupBuf.setString(8, "Nudge Virtual Input");

          ioctlHandle.invoke(fd, (long) UI_DEV_SETUP, setupBuf.address());
          ioctlHandle.invoke(fd, (long) UI_DEV_CREATE, 0L);
        }

        return new UinputBackend(fd, ioctlHandle, writeHandle, closeHandle);
      } catch (Throwable _) {
        return null;
      }
    }

    @Override
    public void moveMouse(int dx, int dy) {
      emit(EV_REL, REL_X, dx);
      emit(EV_REL, REL_Y, dy);
      emit(EV_SYN, (short) 0, 0);
    }

    @Override
    public void scrollMouse(int clicks) {
      emit(EV_REL, REL_WHEEL, clicks);
      emit(EV_SYN, (short) 0, 0);
    }

    @Override
    public void pressShiftKey() {
      emit(EV_KEY, KEY_LEFTSHIFT, 1);
      emit(EV_SYN, (short) 0, 0);
      try {
        Thread.sleep(40);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
      emit(EV_KEY, KEY_LEFTSHIFT, 0);
      emit(EV_SYN, (short) 0, 0);
    }

    private void emit(short type, short code, int value) {
      try (var arena = Arena.ofConfined()) {
        var event = arena.allocate(24);
        event.set(ValueLayout.JAVA_SHORT, 16, type);
        event.set(ValueLayout.JAVA_SHORT, 18, code);
        event.set(ValueLayout.JAVA_INT, 20, value);
        write.invoke(fd, event, 24L);
      } catch (Throwable _) {
        // Ignore write failures
      }
    }

    @Override
    public Point getPointerPosition() {
      var info = MouseInfo.getPointerInfo();
      return info != null ? info.getLocation() : null;
    }

    @Override
    public String name() {
      return "Linux Kernel Virtual Device (/dev/uinput via FFM)";
    }

    @Override
    public void close() {
      try {
        ioctl.invoke(fd, (long) UI_DEV_DESTROY, 0L);
        close.invoke(fd);
      } catch (Throwable _) {
        // Cleanup
      }
    }
  }

  /// External Wayland CLI tool simulation backend (ydotool, wtype, dotool).
  private static final class CliToolBackend implements InputBackend {
    private final String tool;

    private CliToolBackend(String tool) {
      this.tool = tool;
    }

    public static CliToolBackend detect() {
      if (hasCommand("ydotool")) {
        return new CliToolBackend("ydotool");
      }
      if (hasCommand("wtype")) {
        return new CliToolBackend("wtype");
      }
      if (hasCommand("dotool")) {
        return new CliToolBackend("dotool");
      }
      return null;
    }

    @Override
    public void moveMouse(int dx, int dy) {
      switch (tool) {
        case "ydotool" -> run("ydotool", "mousemove", "--", String.valueOf(dx), String.valueOf(dy));
        case "dotool" -> runPipe("echo 'mrel %d %d' | dotool".formatted(dx, dy));
        default -> {
        }
      }
    }

    @Override
    public void scrollMouse(int clicks) {
      switch (tool) {
        case "ydotool" -> run("ydotool", "mousemove", "--wheel", String.valueOf(clicks));
        case "dotool" -> runPipe("echo 'wheel %d' | dotool".formatted(clicks));
        default -> {
        }
      }
    }

    @Override
    public void pressShiftKey() {
      switch (tool) {
        case "ydotool" -> run("ydotool", "key", "42:1", "42:0");
        case "wtype" -> run("wtype", "-k", "Shift_L");
        case "dotool" -> runPipe("echo 'key shift' | dotool");
        default -> {
        }
      }
    }

    private void run(String... args) {
      try {
        new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();
      } catch (Exception _) {
        // Ignored
      }
    }

    private void runPipe(String shellCmd) {
      try {
        new ProcessBuilder("sh", "-c", shellCmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();
      } catch (Exception _) {
        // Ignored
      }
    }

    @Override
    public Point getPointerPosition() {
      var info = MouseInfo.getPointerInfo();
      return info != null ? info.getLocation() : null;
    }

    @Override
    public String name() {
      return "Wayland CLI Simulation Tool (%s)".formatted(tool);
    }
  }

  /// Fallback Wayland backend using D-Bus ScreenSaver Inhibit alongside AWT Robot.
  private static final class FallbackWaylandBackend implements InputBackend {
    private final String cookie;
    private final Process systemdProcess;
    private final Robot robot;

    private FallbackWaylandBackend(String cookie, Process systemdProcess, Robot robot) {
      this.cookie = cookie;
      this.systemdProcess = systemdProcess;
      this.robot = robot;
    }

    public static FallbackWaylandBackend create() {
      String cookie = null;
      Process proc = null;

      // 1. Try busctl
      try {
        var pb = new ProcessBuilder("busctl", "--user", "call", "org.freedesktop.ScreenSaver",
            "/org/freedesktop/ScreenSaver", "org.freedesktop.ScreenSaver", "Inhibit", "ss", "nudge",
            "keep presence active");
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() == 0 && out.startsWith("u ")) {
          cookie = out.substring(2).trim();
        }
      } catch (Exception _) {
        // Try gdbus
      }

      // 2. Try gdbus
      if (cookie == null) {
        try {
          var pb = new ProcessBuilder("gdbus", "call", "--session", "--dest",
              "org.freedesktop.ScreenSaver", "--object-path", "/org/freedesktop/ScreenSaver",
              "--method", "org.freedesktop.ScreenSaver.Inhibit", "nudge", "keep presence active");
          var p = pb.start();
          var out = new String(p.getInputStream().readAllBytes()).trim();
          if (p.waitFor() == 0 && out.contains("uint32 ")) {
            var matcher = Pattern.compile("\\d+").matcher(out);
            if (matcher.find()) {
              cookie = matcher.group();
            }
          }
        } catch (Exception _) {
          // Try systemd-inhibit
        }
      }

      // 3. Try systemd-inhibit
      if (cookie == null) {
        try {
          proc = new ProcessBuilder("systemd-inhibit", "--what=idle:sleep", "--who=nudge",
              "--why=Keep presence active", "--mode=block", "sleep", "infinity").start();
        } catch (Exception _) {
          // Fallback only
        }
      }

      Robot r = null;
      if (!GraphicsEnvironment.isHeadless()) {
        try {
          r = new Robot();
          r.setAutoDelay(40);
        } catch (Exception _) {
          // Headless or Robot init failed
        }
      }

      return new FallbackWaylandBackend(cookie, proc, r);
    }

    @Override
    public void moveMouse(int dx, int dy) {
      if (robot != null) {
        var current = getPointerPosition();
        if (current != null) {
          robot.mouseMove(current.x + dx, current.y + dy);
        }
      }
    }

    @Override
    public void scrollMouse(int clicks) {
      if (robot != null) {
        robot.mouseWheel(clicks);
      }
    }

    @Override
    public void pressShiftKey() {
      if (robot != null) {
        robot.keyPress(KeyEvent.VK_SHIFT);
        robot.delay(40);
        robot.keyRelease(KeyEvent.VK_SHIFT);
      }
    }

    @Override
    public Point getPointerPosition() {
      var info = MouseInfo.getPointerInfo();
      return info != null ? info.getLocation() : null;
    }

    @Override
    public String name() {
      return "Wayland D-Bus ScreenSaver Inhibit" + (robot != null ? " + XWayland Robot" : "");
    }

    @Override
    public void close() {
      if (cookie != null) {
        try {
          new ProcessBuilder("busctl", "--user", "call", "org.freedesktop.ScreenSaver",
              "/org/freedesktop/ScreenSaver", "org.freedesktop.ScreenSaver", "UnInhibit", "u",
              cookie).start().waitFor();
        } catch (Exception _) {
          try {
            new ProcessBuilder("gdbus", "call", "--session", "--dest",
                "org.freedesktop.ScreenSaver", "--object-path", "/org/freedesktop/ScreenSaver",
                "--method", "org.freedesktop.ScreenSaver.UnInhibit", cookie).start().waitFor();
          } catch (Exception _) {
            // Cleanup error ignored
          }
        }
      }
      if (systemdProcess != null && systemdProcess.isAlive()) {
        systemdProcess.destroy();
      }
    }
  }

  /// Standard AWT Robot backend for X11, macOS, and Windows.
  private static final class AwtRobotBackend implements InputBackend {
    private final Robot robot;
    private final Dimension screenSize;

    public AwtRobotBackend() throws Exception {
      this.robot = new Robot();
      this.robot.setAutoDelay(40);
      this.screenSize = Toolkit.getDefaultToolkit().getScreenSize();
    }

    @Override
    public void moveMouse(int dx, int dy) {
      var current = getPointerPosition();
      if (current != null) {
        int cx = Math.clamp(current.x + dx, 0, screenSize.width - 1);
        int cy = Math.clamp(current.y + dy, 0, screenSize.height - 1);
        robot.mouseMove(cx, cy);
      }
    }

    @Override
    public void scrollMouse(int clicks) {
      robot.mouseWheel(clicks);
    }

    @Override
    public void pressShiftKey() {
      robot.keyPress(KeyEvent.VK_SHIFT);
      robot.delay(40);
      robot.keyRelease(KeyEvent.VK_SHIFT);
    }

    @Override
    public Point getPointerPosition() {
      var info = MouseInfo.getPointerInfo();
      return info != null ? info.getLocation() : null;
    }

    @Override
    public String name() {
      return "AWT Robot (X11/macOS/Windows)";
    }
  }
}
