///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.7

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;

@Command(
        name = "keep-presence",
        mixinStandardHelpOptions = true,
        version = "keep-presence 1.0",
        description = "Simulates user activity (mouse movement, key press, scrolling) when idle to keep your presence status active."
)
class keep_presence implements Callable<Integer> {

    public enum Mode {
        mouse, keyboard, both, scroll
    }

    @Option(names = {"-s", "--seconds"}, description = "Define in seconds how long to wait between idle checks. Default 300.")
    private int seconds = 300;

    @Option(names = {"-p", "--pixels"}, description = "Set how many pixels the mouse should move. Default 1.")
    private int pixels = 1;

    @Option(names = {"-c", "--circular"}, description = "Move mouse in a circle. Default move diagonally.")
    private boolean circular;

    @Option(names = {"-m", "--mode"}, description = "Action mode: mouse, keyboard, both, scroll. Default: mouse.")
    private Mode mode = Mode.mouse;

    @Option(names = {"-r", "--random"}, arity = "2", paramLabel = "<START> <STOP>", description = "Execute actions using a random interval between START and STOP seconds. Overrides --seconds.")
    private List<Integer> randomRange;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Robot robot;
    private final Random random = new Random();
    private int mouseDirection = 0;

    public static void main(String... args) {
        int exitCode = new CommandLine(new keep_presence()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Error: Headless environment detected. java.awt.Robot requires a GUI environment to simulate input.");
            return 1;
        }

        int randStart = 0;
        int randStop = 0;
        if (randomRange != null && !randomRange.isEmpty()) {
            if (randomRange.size() != 2) {
                System.err.println("Error: --random requires exactly two integer arguments (e.g. -r 3 10).");
                return 1;
            }
            randStart = randomRange.get(0);
            randStop = randomRange.get(1);
            if (randStart > randStop) {
                System.err.println("Error: Random initial number needs to be lower than random limit number.");
                return 1;
            }
        }

        try {
            robot = new Robot();
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
            log(String.format("Mouse is enabled, moving %d pixels%s", pixels, circular ? " (circularly)" : ""));
        }
        if (randomRange != null) {
            log(String.format("Random timing is enabled between %d and %d seconds.", randStart, randStop));
        } else {
            log(String.format("Running every %d seconds", seconds));
        }
        System.out.println("--------");

        Point lastPosition = getMousePosition();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nBye bye ;-)\n");
        }));

        while (!Thread.currentThread().isInterrupted()) {
            Point currentPosition = getMousePosition();
            boolean isUserAway = currentPosition != null && currentPosition.equals(lastPosition);

            if (isUserAway) {
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
                Thread.sleep(delaySeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return 0;
    }

    private Point getMousePosition() {
        var info = MouseInfo.getPointerInfo();
        return info != null ? info.getLocation() : null;
    }

    private Point moveMouse(Point current) {
        if (current == null) return null;

        int deltaX, deltaY;
        if (circular) {
            deltaX = (mouseDirection == 0 || mouseDirection == 3) ? pixels : -pixels;
            deltaY = (mouseDirection == 0 || mouseDirection == 1) ? pixels : -pixels;
            mouseDirection = (mouseDirection + 1) % 4;
        } else {
            deltaX = (mouseDirection == 0) ? pixels : -pixels;
            deltaY = (mouseDirection == 0) ? pixels : -pixels;
            mouseDirection = (mouseDirection + 1) % 2;
        }

        int targetX = current.x + deltaX;
        int targetY = current.y + deltaY;
        robot.mouseMove(targetX, targetY);

        Point newPos = getMousePosition();
        log(String.format("Moved mouse to: %s", newPos != null ? String.format("(%d, %d)", newPos.x, newPos.y) : "unknown"));
        return newPos;
    }

    private void scrollMouse() {
        robot.mouseWheel(2);
        log("Mouse wheel scrolled");
    }

    private void pressShiftKey() {
        robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyRelease(KeyEvent.VK_SHIFT);
        log("Shift key pressed");
    }

    private void log(String message) {
        System.out.printf("%s %s%n", LocalTime.now().format(TIME_FORMATTER), message);
    }
}
