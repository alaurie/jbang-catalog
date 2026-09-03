///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//DEPS com.github.oshi:oshi-core:6.6.5
//DEPS org.slf4j:slf4j-nop:2.0.16
//JAVAC_OPTIONS -proc:full
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -Xms16m -Xmx64m -XX:TieredStopAtLevel=1
//NATIVE_OPTIONS -O2 --no-fallback

package slowfetch;

import java.net.InetAddress;
import java.nio.file.Files;
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

    var packages = detectPackages();
    if (packages != null) {
      infoLines.add("%sPackages:%s %s".formatted(bold, reset, packages));
    }

    var shell = System.getenv("SHELL");
    if (shell != null && !shell.isBlank()) {
      var shellName = Path.of(shell).getFileName().toString();
      infoLines.add("%sShell:%s %s".formatted(bold, reset, shellName));
    }

    var displays = detectDisplays();
    for (var display : displays) {
      infoLines.add("%sDisplay:%s %s".formatted(bold, reset, display));
    }

    var de = detectDesktopEnvironment();
    if (de != null) {
      infoLines.add("%sDE/WM:%s %s".formatted(bold, reset, de));
    }

    var wmTheme = detectWmTheme();
    if (wmTheme != null) {
      infoLines.add("%sWM Theme:%s %s".formatted(bold, reset, wmTheme));
    }

    var theme = detectGtkTheme();
    if (theme != null) {
      infoLines.add("%sTheme:%s %s".formatted(bold, reset, theme));
    }

    var icons = detectIcons();
    if (icons != null) {
      infoLines.add("%sIcons:%s %s".formatted(bold, reset, icons));
    }

    var font = detectFont();
    if (font != null) {
      infoLines.add("%sFont:%s %s".formatted(bold, reset, font));
    }

    var cursor = detectCursor();
    if (cursor != null) {
      infoLines.add("%sCursor:%s %s".formatted(bold, reset, cursor));
    }

    var terminal = detectTerminal();
    if (terminal != null) {
      infoLines.add("%sTerminal:%s %s".formatted(bold, reset, terminal));
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
        var driverVer = detectGpuDriver(gpu);
        var driverSuffix =
            (driverVer != null && !driverVer.isBlank()) ? " [%s]".formatted(driverVer) : "";
        infoLines.add("%sGPU:%s %s%s".formatted(bold, reset, gpuName, driverSuffix));
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
    var containers = detectContainers();
    if (containers != null) {
      infoLines.add("%sContainers:%s %s".formatted(bold, reset, containers));
    }


    var locale = System.getenv("LANG");
    if (locale != null && !locale.isBlank()) {
      infoLines.add("%sLocale:%s %s".formatted(bold, reset, locale));
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
    infoLines.addAll(renderColorPaletteLines());

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

  private List<String> renderColorPaletteLines() {
    var line1 = new StringBuilder();
    for (int i = 40; i <= 47; i++) {
      line1.append("\u001B[").append(i).append("m   \u001B[0m");
    }
    var line2 = new StringBuilder();
    for (int i = 100; i <= 107; i++) {
      line2.append("\u001B[").append(i).append("m   \u001B[0m");
    }
    return List.of(line1.toString(), line2.toString());
  }


  private String detectPackages() {
    var parts = new ArrayList<String>();

    // Debian / Ubuntu (dpkg)
    var dpkgStatus = Path.of("/var/lib/dpkg/status");
    if (Files.isRegularFile(dpkgStatus)) {
      try (var lines = Files.lines(dpkgStatus)) {
        long count = lines.filter(l -> l.startsWith("Package: ")).count();
        if (count > 0)
          parts.add("%d (dpkg)".formatted(count));
      } catch (Exception _) {
      }
    }

    // Red Hat / Fedora (rpm)
    var rpmDb = Path.of("/var/lib/rpm/rpmdb.sqlite");
    if (!Files.exists(rpmDb))
      rpmDb = Path.of("/var/lib/rpm/Packages");
    if (Files.exists(rpmDb)) {
      try {
        var p =
            new ProcessBuilder("rpm", "-qa").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var count = new String(p.getInputStream().readAllBytes()).lines().count();
        if (p.waitFor() == 0 && count > 0)
          parts.add("%d (rpm)".formatted(count));
      } catch (Exception _) {
      }
    }

    // Arch Linux (pacman)
    var pacmanDb = Path.of("/var/lib/pacman/local");
    if (Files.isDirectory(pacmanDb)) {
      try (var stream = Files.list(pacmanDb)) {
        long count = stream.filter(Files::isDirectory).count();
        if (count > 0)
          parts.add("%d (pacman)".formatted(count));
      } catch (Exception _) {
      }
    }

    // Flatpak
    var flatpakDir = Path.of("/var/lib/flatpak/app");
    if (Files.isDirectory(flatpakDir)) {
      try (var stream = Files.list(flatpakDir)) {
        long count = stream.filter(Files::isDirectory).count();
        if (count > 0)
          parts.add("%d (flatpak)".formatted(count));
      } catch (Exception _) {
      }
    }

    // Homebrew (macOS / Linux)
    var brewCellar = Path.of("/opt/homebrew/Cellar");
    if (!Files.isDirectory(brewCellar))
      brewCellar = Path.of("/usr/local/Cellar");
    if (Files.isDirectory(brewCellar)) {
      try (var stream = Files.list(brewCellar)) {
        long count = stream.filter(Files::isDirectory).count();
        if (count > 0)
          parts.add("%d (brew)".formatted(count));
      } catch (Exception _) {
      }
    }

    return parts.isEmpty() ? null : String.join(", ", parts);
  }

  private List<String> detectDisplays() {
    var list = new ArrayList<String>();
    try {
      if (!java.awt.GraphicsEnvironment.isHeadless()) {
        var ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
        var devices = ge.getScreenDevices();
        for (var gd : devices) {
          var dm = gd.getDisplayMode();
          var rate = dm.getRefreshRate() > 0 ? " @ %d Hz".formatted(dm.getRefreshRate()) : "";
          list.add("%dx%d%s".formatted(dm.getWidth(), dm.getHeight(), rate));
        }
      }
    } catch (Exception _) {
    }
    return list;
  }

  private String detectGtkTheme() {
    var theme = gsettingsGet("org.gnome.desktop.interface", "gtk-theme");
    if (theme != null)
      return theme;
    var xfce = xfconfQuery("xsettings", "/Net/ThemeName");
    if (xfce != null)
      return xfce;
    return null;
  }

  private String detectIcons() {
    var icons = gsettingsGet("org.gnome.desktop.interface", "icon-theme");
    if (icons != null)
      return icons;
    var xfce = xfconfQuery("xsettings", "/Net/IconThemeName");
    if (xfce != null)
      return xfce;
    return null;
  }

  private String detectFont() {
    var font = gsettingsGet("org.gnome.desktop.interface", "font-name");
    if (font != null)
      return font;
    var xfce = xfconfQuery("xsettings", "/Gtk/FontName");
    if (xfce != null)
      return xfce;
    return null;
  }

  private String detectCursor() {
    var cursor = gsettingsGet("org.gnome.desktop.interface", "cursor-theme");
    var size = gsettingsGet("org.gnome.desktop.interface", "cursor-size");
    if (cursor != null) {
      return (size != null && !size.isBlank()) ? "%s (%spx)".formatted(cursor, size) : cursor;
    }
    return null;
  }

  private String detectWmTheme() {
    var wmTheme = gsettingsGet("org.gnome.desktop.wm.preferences", "theme");
    if (wmTheme != null && !wmTheme.isBlank())
      return wmTheme;
    return null;
  }

  private String gsettingsGet(String schema, String key) {
    try {
      var p = new ProcessBuilder("gsettings", "get", schema, key)
          .redirectError(ProcessBuilder.Redirect.DISCARD).start();
      var out = new String(p.getInputStream().readAllBytes()).trim();
      if (p.waitFor() == 0 && !out.isBlank() && !out.contains("No such schema")) {
        return out.replace("'", "").replace("\"", "").trim();
      }
    } catch (Exception _) {
    }
    return null;
  }

  private String xfconfQuery(String channel, String property) {
    try {
      var p = new ProcessBuilder("xfconf-query", "-c", channel, "-p", property)
          .redirectError(ProcessBuilder.Redirect.DISCARD).start();
      var out = new String(p.getInputStream().readAllBytes()).trim();
      if (p.waitFor() == 0 && !out.isBlank()) {
        return out;
      }
    } catch (Exception _) {
    }
    return null;
  }

  private String detectTerminal() {
    var termProg = System.getenv("TERM_PROGRAM");

    var termProgVer = System.getenv("TERM_PROGRAM_VERSION");
    if (termProg != null && !termProg.isBlank()) {
      return (termProgVer != null && !termProgVer.isBlank())
          ? "%s %s".formatted(termProg, termProgVer)
          : termProg;
    }
    var term = System.getenv("TERM");
    if (term != null && !term.isBlank() && !term.equals("dumb")) {
      return term;
    }
    return null;
  }

  private String detectGpuDriver(GraphicsCard gpu) {
    var oshiVersion = gpu.getVersionInfo();
    if (oshiVersion != null && !oshiVersion.isBlank() && !oshiVersion.equalsIgnoreCase("unknown")) {
      return oshiVersion;
    }


    var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("linux")) {
      var vendor = gpu.getVendor().toLowerCase(Locale.ROOT);
      var name = gpu.getName().toLowerCase(Locale.ROOT);

      if (vendor.contains("nvidia") || name.contains("nvidia") || name.contains("geforce")) {
        var nvidiaVer = Path.of("/sys/module/nvidia/version");
        if (Files.exists(nvidiaVer)) {
          try {
            var v = Files.readString(nvidiaVer).trim();
            if (!v.isBlank())
              return "NVIDIA " + v;
          } catch (Exception _) {
          }
        }
      }

      if (vendor.contains("amd") || vendor.contains("advanced micro") || name.contains("radeon")) {
        var amdVer = Path.of("/sys/module/amdgpu/version");
        if (Files.exists(amdVer)) {
          try {
            var v = Files.readString(amdVer).trim();
            if (!v.isBlank())
              return "amdgpu " + v;
          } catch (Exception _) {
          }
        }
      }

      if (vendor.contains("intel") || name.contains("intel") || name.contains("arc")) {
        for (var path : List.of(Path.of("/sys/module/i915/version"),
            Path.of("/sys/module/xe/version"))) {
          if (Files.exists(path)) {
            try {
              var v = Files.readString(path).trim();
              if (!v.isBlank())
                return path.getParent().getFileName() + " " + v;
            } catch (Exception _) {
            }
          }
        }
      }

      // Mesa driver fallback check via glxinfo
      try {
        var p = new ProcessBuilder("glxinfo", "-B").redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        var out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        for (var line : out.lines().toList()) {
          if (line.contains("OpenGL version string:")) {
            var ver = line.substring(line.indexOf(":") + 1).trim();
            if (ver.toLowerCase(Locale.ROOT).contains("mesa")) {
              return ver;
            }
          }
        }
      } catch (Exception _) {
      }
    }
    return null;
  }

  private String detectContainers() {
    var found = new ArrayList<String>();

    // 1. Check Docker
    try {
      var p = new ProcessBuilder("docker", "info", "--format",
          "{{.ContainersRunning}} running, {{.Containers}} total")
          .redirectError(ProcessBuilder.Redirect.DISCARD).start();
      var out = new String(p.getInputStream().readAllBytes()).trim();
      if (p.waitFor() == 0 && !out.isBlank()) {
        found.add("Docker (%s)".formatted(out));
      }
    } catch (Exception _) {
    }

    // 2. Check Podman
    try {
      var p = new ProcessBuilder("podman", "info", "--format",
          "{{.Host.Containers.Running}} running, {{.Host.Containers.Total}} total")
          .redirectError(ProcessBuilder.Redirect.DISCARD).start();
      var out = new String(p.getInputStream().readAllBytes()).trim();
      if (p.waitFor() == 0 && !out.isBlank()) {
        found.add("Podman (%s)".formatted(out));
      }
    } catch (Exception _) {
    }

    return found.isEmpty() ? null : String.join(", ", found);
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

    var logoWidth = logo.width() + 2;
    var gap = "    "; // 4 spaces clean margin
    var emptyLogoPad = " ".repeat(logoWidth);

    var maxLines = Math.max(logoLines.size(), infoLines.size());

    for (int i = 0; i < maxLines; i++) {
      var textPart = (i < infoLines.size()) ? infoLines.get(i) : "";
      if (i < logoLines.size()) {
        var logoPart = logoLines.get(i);
        var paddedLogo = String.format("%-" + logoWidth + "s", logoPart);
        System.out.println(logoColor + paddedLogo + reset + gap + textPart);
      } else {
        System.out.println(emptyLogoPad + gap + textPart);
      }
    }
  }
  //endregion

  //region Logo Records & Art
  private record Logo(String name, String colorCode, List<String> lines, int width) {
    Logo(String name, String colorCode, String rawArt) {
      this(
          name,
          colorCode,
          rawArt.stripTrailing().lines().toList(),
          rawArt.stripTrailing().lines().mapToInt(String::length).max().orElse(0));
    }
  }

  private static final Logo LOGO_DEBIAN = new Logo("Debian", "\u001B[31m\u001B[1m", """
             _,met$$$$$gg.
          ,g$$$$$$$$$$$$$$$P.
        ,g$$P""       ""\"Y$$".
       ,$$P'              `$$$.
      ',$$P       ,ggs.     `$$b
      `d$$'     ,"   .    $$$
       $$P      d     ,    $$P
       $$:      $   -    ,d$$'
       $$;      Y._   _,d'
       Y$$.    `.`"Y$$$$P"'
       `$$b      "-.__
        `Y$$b
         `Y$$.
           `$$b.
             `Y$$b.
               `"Y$b._
                   `\"\"\"\"
      """);

  private static final Logo LOGO_UBUNTU = new Logo("Ubuntu", "\u001B[31m\u001B[1m", """
               _
           ---(_)
       _/  ---  \\
      (_) |   |
        \\  --- _/
           ---(_)
      """);

  private static final Logo LOGO_ARCH = new Logo("Arch", "\u001B[36m\u001B[1m", """
                /\\
               /  \\
              /\\   \\
             /      \\
            /   ,,   \\
           /   |  |  -\\
          /_-''    ''-_\\
      """);

  private static final Logo LOGO_FEDORA = new Logo("Fedora", "\u001B[34m\u001B[1m", """
              ,'''''.
             |   n   |
           ,-'---+---'-.
          /  (       )  \\
         |   /       \\   |
          \\             /
           '-._______.-'
      """);

  private static final Logo LOGO_MACOS = new Logo("macOS", "\u001B[32m\u001B[1m", """
              .:'
          __ :'__
       .'`  `-'  ``.
      :          .-'
      :         :
       :         `-;
        `.__.-.__.'
      """);

  private static final Logo LOGO_WINDOWS = new Logo("Windows", "\u001B[36m\u001B[1m", """
        ################  ################
        ################  ################
        ################  ################
        ################  ################

        ################  ################
        ################  ################
        ################  ################
        ################  ################
      """);

  private static final Logo LOGO_LINUX = new Logo("Linux", "\u001B[33m\u001B[1m", """
             .---.
            /     \\
            \\.@-@./
            /`\\_/`\\
           //  _  \\\\
          | \\     )|_
         /`\\_`>  <_/ \\
         \\__/'---'\\__/
      """);

  private static final Logo LOGO_JAVA = new Logo("Java", "\u001B[31m\u001B[1m", """
               `
              ` `
             ` ` `
           .------.  _
          /        \\(_)
         |          |
          \\        /
           `------'
      """);
}
