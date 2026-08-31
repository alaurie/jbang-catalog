///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//DEPS com.github.oshi:oshi-core:6.6.5
//DEPS org.slf4j:slf4j-nop:2.0.16
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//NATIVE_OPTIONS -O2 --no-fallback

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.ComputerSystem;
import oshi.hardware.GlobalMemory;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.hardware.PowerSource;
import oshi.hardware.Sensors;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/// slowfetch is a high-detail terminal system information fetcher written in Java.
///
/// A powerful, pure-Java take on fastfetch/neofetch powered by OSHI, featuring hardware sensors,
/// battery diagnostics, load averages, visual ASCII progress gauges, and top resource consumers.
@Command(name = "slowfetch", mixinStandardHelpOptions = true, version = "slowfetch 1.2",
    description = "A thorough, beautiful system information tool written in modern Java.")
@SuppressWarnings("unused")
class Slowfetch implements Callable<Integer> {

  //region CLI Arguments
  @Option(names = {"--no-logo"}, description = "Hide OS ASCII art logo.")
  private boolean noLogo;

  @Option(names = {"--logo"},
      description = "Force a specific logo: debian, ubuntu, arch, fedora, macos, windows, linux, java.")
  private String forceLogo;

  @Option(names = {"--disks"},
      description = "Show all mounted physical disks instead of just root.")
  private boolean showAllDisks;

  @Option(names = {"--top"}, description = "Show top 3 processes by CPU and Memory consumption.")
  private boolean showTop;

  @Option(names = {"--no-bars"},
      description = "Disable visual progress bar gauges for memory/disk/battery.")
  private boolean noBars;
  //endregion

  //region Main & Lifecycle
  void main(String... args) {
    var exitCode = new CommandLine(this).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() {
    var si = new SystemInfo();
    var hal = si.getHardware();
    var os = si.getOperatingSystem();

    var osName = os.getFamily();
    var version = os.getVersionInfo().getVersion();
    var arch = System.getProperty("os.arch", "unknown");

    var user = System.getProperty("user.name", "user");
    var hostname = resolveHostname(os);

    var infoLines = new ArrayList<String>();
    var bold = "\u001B[1m";
    var reset = "\u001B[0m";
    var cyan = "\u001B[36m";
    var green = "\u001B[32m";
    var yellow = "\u001B[33m";
    var blue = "\u001B[34m";
    var magenta = "\u001B[35m";
    var dim = "\u001B[2m";

    infoLines.add(cyan + bold + user + reset + "@" + cyan + bold + hostname + reset);
    infoLines.add(dim + "-".repeat(user.length() + 1 + hostname.length()) + reset);

    infoLines.add("%sOS:%s %s %s %s".formatted(bold, reset, osName, version, arch));

    var cs = hal.getComputerSystem();
    var model = cs.getModel();
    if (model != null && !model.isBlank() && !model.equalsIgnoreCase("System Product Name")) {
      infoLines.add("%sHost:%s %s (%s)".formatted(bold, reset, model, cs.getManufacturer()));
    }

    var kernel = os.getVersionInfo().getBuildNumber();
    if (kernel != null && !kernel.isBlank()) {
      infoLines.add("%sKernel:%s %s".formatted(bold, reset, kernel));
    }

    var uptimeSec = os.getSystemUptime();
    infoLines.add("%sUptime:%s %s".formatted(bold, reset, formatUptime(uptimeSec)));

    var shell = System.getenv("SHELL");
    if (shell != null && !shell.isBlank()) {
      var shellName = Path.of(shell).getFileName().toString();
      infoLines.add("%sShell:%s %s".formatted(bold, reset, shellName));
    }

    var de = detectDesktopEnvironment();
    if (de != null) {
      infoLines.add("%sDE/WM:%s %s".formatted(bold, reset, de));
    }

    var cpu = hal.getProcessor();
    var cpuName = cleanCpuName(cpu.getProcessorIdentifier().getName());
    var physicalCores = cpu.getPhysicalProcessorCount();
    var logicalCores = cpu.getLogicalProcessorCount();
    infoLines
        .add("%sCPU:%s %s (%dC/%dT)".formatted(bold, reset, cpuName, physicalCores, logicalCores));

    var sensors = hal.getSensors();
    var cpuTemp = sensors.getCpuTemperature();
    var fanSpeeds = sensors.getFanSpeeds();
    var sensorParts = new ArrayList<String>();
    if (cpuTemp > 0) {
      sensorParts.add("%.1f°C".formatted(cpuTemp));
    }
    if (fanSpeeds != null && fanSpeeds.length > 0 && fanSpeeds[0] > 0) {
      sensorParts.add("%d RPM".formatted(fanSpeeds[0]));
    }
    if (!sensorParts.isEmpty()) {
      infoLines.add("%sSensors:%s %s".formatted(bold, reset, String.join(" | ", sensorParts)));
    }

    var loadAverages = cpu.getSystemLoadAverage(3);
    if (loadAverages != null && loadAverages.length >= 3 && loadAverages[0] >= 0) {
      infoLines.add("%sLoad Avg:%s %.2f, %.2f, %.2f".formatted(bold, reset, loadAverages[0],
          loadAverages[1], loadAverages[2]));
    }

    for (var gpu : hal.getGraphicsCards()) {
      var gpuName = gpu.getName();
      if (!gpuName.isBlank()) {
        var vramMb = gpu.getVRam() / (1024 * 1024);
        if (vramMb > 0) {
          infoLines.add("%sGPU:%s %s (%d MB)".formatted(bold, reset, gpuName, vramMb));
        } else {
          infoLines.add("%sGPU:%s %s".formatted(bold, reset, gpuName));
        }
      }
    }

    var mem = hal.getMemory();
    var totalMem = mem.getTotal();
    var availMem = mem.getAvailable();
    var usedMem = totalMem - availMem;
    var memPct = (int) Math.round(((double) usedMem / totalMem) * 100.0);
    var memBar = noBars ? "" : renderGauge(memPct) + " ";
    infoLines.add("%sMemory:%s %s%s / %s (%d%%)".formatted(bold, reset, memBar,
        formatBytes(usedMem), formatBytes(totalMem), memPct));

    var swapTotal = mem.getVirtualMemory().getSwapTotal();
    if (swapTotal > 0) {
      var swapUsed = mem.getVirtualMemory().getSwapUsed();
      var swapPct = (int) Math.round(((double) swapUsed / swapTotal) * 100.0);
      var swapBar = noBars ? "" : renderGauge(swapPct) + " ";
      infoLines.add("%sSwap:%s %s%s / %s (%d%%)".formatted(bold, reset, swapBar,
          formatBytes(swapUsed), formatBytes(swapTotal), swapPct));
    }

    var fs = os.getFileSystem();
    var fileStores = fs.getFileStores();
    for (var store : fileStores) {
      var mount = store.getMount();
      if (!showAllDisks && !mount.equals("/") && !mount.equalsIgnoreCase("C:\\")) {
        continue;
      }
      var totalDisk = store.getTotalSpace();
      if (totalDisk > 0) {
        var usedDisk = totalDisk - store.getUsableSpace();
        var diskPct = (int) Math.round(((double) usedDisk / totalDisk) * 100.0);
        var diskBar = noBars ? "" : renderGauge(diskPct) + " ";
        infoLines.add("%sDisk (%s):%s %s%s / %s (%d%%) - %s".formatted(bold, mount, reset, diskBar,
            formatBytes(usedDisk), formatBytes(totalDisk), diskPct, store.getType()));
      }
    }

    for (var ps : hal.getPowerSources()) {
      var cap = (int) Math.round(ps.getRemainingCapacityPercent() * 100);
      if (cap >= 0) {
        var status =
            ps.isCharging() ? "Charging" : (ps.isPowerOnLine() ? "AC Connected" : "Discharging");
        var pBar = noBars ? "" : renderGauge(cap) + " ";
        infoLines.add("%sBattery:%s %s%d%% [%s]".formatted(bold, reset, pBar, cap, status));
      }
    }

    var primaryIp = resolvePrimaryIp(hal);
    if (primaryIp != null) {
      infoLines.add("%sLocal IP:%s %s".formatted(bold, reset, primaryIp));
    }

    if (showTop) {
      var topCpu = os.getProcesses(null, OperatingSystem.ProcessSorting.CPU_DESC, 3);
      if (!topCpu.isEmpty()) {
        var cpuParts = topCpu.stream()
            .map(p -> "%s (%.1f%%)".formatted(p.getName(),
                (100d * (p.getKernelTime() + p.getUserTime()) / Math.max(1, p.getUpTime()))))
            .toList();
        infoLines.add("%sTop CPU:%s %s".formatted(bold, reset, String.join(", ", cpuParts)));
      }

      var topMem = os.getProcesses(null, OperatingSystem.ProcessSorting.RSS_DESC, 3);
      if (!topMem.isEmpty()) {
        var memParts = topMem.stream()
            .map(p -> "%s (%s)".formatted(p.getName(), formatBytes(p.getResidentSetSize())))
            .toList();
        infoLines.add("%sTop Mem:%s %s".formatted(bold, reset, String.join(", ", memParts)));
      }
    }

    var javaVersion = System.getProperty("java.version", "unknown");
    var javaVendor = System.getProperty("java.vendor", "");
    infoLines.add("%sJava Runtime:%s Java %s (%s)".formatted(bold, reset, javaVersion, javaVendor));

    infoLines.add("");
    infoLines.add(renderColorPalette());

    printSideBySide(selectLogo(osName), infoLines);
    return 0;
  }
  //endregion

  //region Helper Methods
  private String resolveHostname(OperatingSystem os) {
    try {
      var local = InetAddress.getLocalHost().getHostName();
      if (local != null && !local.isBlank())
        return local;
    } catch (Exception _) {
    }
    return os.getNetworkParams().getHostName();
  }

  private String formatUptime(long seconds) {
    var d = Duration.ofSeconds(seconds);
    var days = d.toDays();
    var hours = d.toHoursPart();
    var mins = d.toMinutesPart();
    if (days > 0) {
      return "%d days, %d hours, %d mins".formatted(days, hours, mins);
    }
    if (hours > 0) {
      return "%d hours, %d mins".formatted(hours, mins);
    }
    return "%d mins".formatted(mins);
  }

  private String formatBytes(long bytes) {
    if (bytes >= 1024L * 1024L * 1024L * 1024L) {
      return "%.2f TiB".formatted((double) bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0));
    }
    if (bytes >= 1024L * 1024L * 1024L) {
      return "%.2f GiB".formatted((double) bytes / (1024.0 * 1024.0 * 1024.0));
    }
    if (bytes >= 1024L * 1024L) {
      return "%.1f MiB".formatted((double) bytes / (1024.0 * 1024.0));
    }
    return "%.1f KiB".formatted((double) bytes / 1024.0);
  }

  private String cleanCpuName(String raw) {
    if (raw == null)
      return "Unknown CPU";
    return raw.replaceAll("\\s+", " ").replace(" CPU", "").trim();
  }

  private String renderGauge(int percent) {
    var totalBlocks = 10;
    var clamped = Math.clamp(percent, 0, 100);
    var filled = (int) Math.round((clamped / 100.0) * totalBlocks);
    var empty = totalBlocks - filled;

    var color = "\u001B[32m"; // green
    if (clamped >= 85) {
      color = "\u001B[31m"; // red
    } else if (clamped >= 65) {
      color = "\u001B[33m"; // yellow
    }
    var dim = "\u001B[2m";
    var reset = "\u001B[0m";

    return "%s[%s%s%s%s%s]%s".formatted(dim, reset + color, "■".repeat(filled), dim,
        "·".repeat(empty), dim, reset);
  }

  private String detectDesktopEnvironment() {
    var xdgCurrent = System.getenv("XDG_CURRENT_DESKTOP");
    var sessionType = System.getenv("XDG_SESSION_TYPE");
    if (xdgCurrent != null && !xdgCurrent.isBlank()) {
      return sessionType != null ? "%s (%s)".formatted(xdgCurrent, sessionType) : xdgCurrent;
    }
    var desktopSession = System.getenv("DESKTOP_SESSION");
    if (desktopSession != null && !desktopSession.isBlank()) {
      return desktopSession;
    }
    var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("win"))
      return "Windows Explorer (DWM)";
    if (os.contains("mac"))
      return "Aqua (Quartz Compositor)";
    return null;
  }

  private String resolvePrimaryIp(HardwareAbstractionLayer hal) {
    for (var net : hal.getNetworkIFs()) {
      var name = net.getName().toLowerCase(Locale.ROOT);
      if (name.startsWith("lo") || name.startsWith("docker") || name.startsWith("virbr")
          || name.startsWith("veth") || name.startsWith("br-")) {
        continue;
      }
      var addrs = net.getIPv4addr();
      if (addrs != null && addrs.length > 0 && !addrs[0].equals("127.0.0.1")) {
        return "%s (%s)".formatted(addrs[0], net.getName());
      }
    }
    return null;
  }

  private String renderColorPalette() {
    var sb = new StringBuilder();
    for (int i = 40; i <= 47; i++) {
      sb.append("\u001B[").append(i).append("m   \u001B[0m");
    }
    sb.append("\n");
    for (int i = 100; i <= 107; i++) {
      sb.append("\u001B[").append(i).append("m   \u001B[0m");
    }
    return sb.toString();
  }

  private Logo selectLogo(String osFamily) {
    var target = (forceLogo != null) ? forceLogo.toLowerCase(Locale.ROOT)
        : osFamily.toLowerCase(Locale.ROOT);

    if (target.contains("debian"))
      return LOGO_DEBIAN;
    if (target.contains("ubuntu"))
      return LOGO_UBUNTU;
    if (target.contains("arch"))
      return LOGO_ARCH;
    if (target.contains("fedora") || target.contains("red hat") || target.contains("rhel")
        || target.contains("alma") || target.contains("rocky"))
      return LOGO_FEDORA;
    if (target.contains("mac") || target.contains("darwin"))
      return LOGO_MACOS;
    if (target.contains("win"))
      return LOGO_WINDOWS;
    if (target.contains("java"))
      return LOGO_JAVA;
    return LOGO_LINUX;
  }

  private void printSideBySide(Logo logo, List<String> infoLines) {
    if (noLogo) {
      for (var line : infoLines) {
        System.out.println(line);
      }
      return;
    }

    var logoLines = logo.lines();
    var logoColor = logo.colorCode();
    var reset = "\u001B[0m";

    var maxLines = Math.max(logoLines.size(), infoLines.size());
    var logoWidth = logo.width();

    for (int i = 0; i < maxLines; i++) {
      var logoPart = (i < logoLines.size()) ? logoLines.get(i) : "";
      var textPart = (i < infoLines.size()) ? infoLines.get(i) : "";

      var paddedLogo = String.format("%-" + logoWidth + "s", logoPart);
      System.out.println(logoColor + paddedLogo + reset + "  " + textPart);
    }
  }
  //endregion

  //region Logo Records & Art
  private record Logo(String name, String colorCode, int width, List<String> lines) {}

  private static final Logo LOGO_DEBIAN = new Logo("Debian", "\u001B[31m\u001B[1m", 24,
      List.of("       _,met$$$$$gg.    ", "    ,g$$$$$$$$$$$$$$$P. ",
          "  ,g$$P\"\"       \"\"\"Y$$\".", " ,$$P'              `$$$.",
          "',$$P       ,ggs.     `$$b", "`d$$'     ,\"   .    $$$", " $$P      d     ,    $$P",
          " $$:      $   -    ,d$$'", " $$;      Y._   _,d'    ", " Y$$.    `.`\"Y$$$$P\"'   ",
          " `$$b      \"-.__        ", "  `Y$$b                 ", "   `Y$$.                ",
          "     `$$b.              ", "       `Y$$b.           ", "         `\"Y$b._        ",
          "             `\"\"\"\"      "));

  private static final Logo LOGO_UBUNTU = new Logo("Ubuntu", "\u001B[31m", 22,
      List.of("         _         ", "     ---(_)        ", " _/  ---  \\        ",
          "(_) |   |          ", "  \\  --- _/        ", "     ---(_)        ",
          "                   "));

  private static final Logo LOGO_ARCH = new Logo("Arch", "\u001B[36m\u001B[1m", 24,
      List.of("          /\\            ", "         /  \\           ",
          "        /\\   \\          ", "       /      \\         ", "      /   ,,   \\        ",
          "     /   |  |  -\\       ", "    /_-''    ''-_\\      "));

  private static final Logo LOGO_FEDORA = new Logo("Fedora", "\u001B[34m\u001B[1m", 22,
      List.of("        ,'''''.       ", "       |   n   |      ", "     ,-'---+---'-.    ",
          "    /  (       )  \\   ", "   |   /       \\   |  ", "    \\             /   ",
          "     '-._______.-'    "));

  private static final Logo LOGO_MACOS = new Logo("macOS", "\u001B[32m\u001B[1m", 20,
      List.of("        .:'         ", "    __ :'__         ", " .'`  `-'  ``.      ",
          ":          .-'      ", ":         :         ", " :         `-;      ",
          "  `.__.-.__.'       "));

  private static final Logo LOGO_WINDOWS = new Logo("Windows", "\u001B[36m\u001B[1m", 24,
      List.of("  ################  ################", "  ################  ################",
          "  ################  ################", "  ################  ################",
          "                                    ", "  ################  ################",
          "  ################  ################", "  ################  ################",
          "  ################  ################"));

  private static final Logo LOGO_LINUX = new Logo("Linux", "\u001B[33m\u001B[1m", 22,
      List.of("       .---.          ", "      /     \\         ", "      \\.@-@./         ",
          "      /`\\_/`\\         ", "     //  _  \\\\        ", "    | \\     )|_       ",
          "   /`\\_`>  <_/ \\      ", "   \\__/'---'\\__/      "));

  private static final Logo LOGO_JAVA = new Logo("Java", "\u001B[31m\u001B[1m", 24,
      List.of("         `                  ", "        ` `                 ",
          "       ` ` `                ", "     .------.  _            ",
          "    /        \\(_)           ", "   |          |             ",
          "    \\        /              ", "     `------'               "));
  //endregion
}
