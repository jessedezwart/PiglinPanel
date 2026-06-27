package nl.jessedezwart.piglinpanel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import nl.jessedezwart.piglinpanel.client.SshClient;
import nl.jessedezwart.piglinpanel.model.AccessType;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Gathers hardware specifications and live performance metrics for the machine hosting a server.
 *
 * <p>Per the project constraint, only OS-native sources are used: {@code /proc} on Linux (read
 * directly for local servers, via SSH for remote ones) and CIM/WMI through PowerShell on Windows
 * (local servers only). No third-party system-info libraries are involved.
 */
@Service
public class SystemInfoService {
  private static final Logger log = LoggerFactory.getLogger(SystemInfoService.class);
  private static final int COMMAND_TIMEOUT_SECONDS = 12;

  private final ObjectMapper objectMapper = new ObjectMapper();

  public Metrics metrics(ServerProfile profile) {
    try {
      if (profile.getAccessType() == AccessType.SSH) {
        return linuxMetrics(runSsh(profile, LINUX_METRICS_COMMAND));
      }
      if (isWindows()) {
        return windowsMetrics(runLocalPowerShell(WINDOWS_METRICS_SCRIPT));
      }
      return linuxMetrics(runLocalShell(LINUX_METRICS_COMMAND));
    } catch (Exception e) {
      log.warn("Could not collect metrics for {}", profile.getName(), e);
      return Metrics.unavailable(shortMessage(e));
    }
  }

  public Specs specs(ServerProfile profile) {
    try {
      if (profile.getAccessType() == AccessType.SSH) {
        return linuxSpecs(runSsh(profile, LINUX_SPECS_COMMAND), "Linux (remote)");
      }
      if (isWindows()) {
        return windowsSpecs(runLocalPowerShell(WINDOWS_SPECS_SCRIPT));
      }
      return linuxSpecs(runLocalShell(LINUX_SPECS_COMMAND), osName());
    } catch (Exception e) {
      log.warn("Could not collect specs for {}", profile.getName(), e);
      return Specs.unavailable(shortMessage(e));
    }
  }

  // --- Linux (/proc) -------------------------------------------------------

  private static final String LINUX_METRICS_COMMAND =
      "echo '@@cpu1'; grep '^cpu ' /proc/stat; sleep 0.3;"
          + " echo '@@cpu2'; grep '^cpu ' /proc/stat;"
          + " echo '@@mem'; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo";

  private static final String LINUX_SPECS_COMMAND =
      "echo '@@cpu'; grep -m1 '^model name' /proc/cpuinfo | cut -d: -f2-;"
          + " echo '@@cores'; grep -c '^processor' /proc/cpuinfo;"
          + " echo '@@mem'; grep '^MemTotal:' /proc/meminfo;"
          + " echo '@@board';"
          + " cat /sys/devices/virtual/dmi/id/board_vendor"
          + " /sys/devices/virtual/dmi/id/board_name 2>/dev/null;"
          + " echo '@@gpu'; (lspci 2>/dev/null | grep -iE 'vga|3d|display' | cut -d: -f3-) || true";

  private Metrics linuxMetrics(String output) {
    Map<String, String> sections = parseSections(output);
    long[] cpu1 = parseCpuLine(sections.get("cpu1"));
    long[] cpu2 = parseCpuLine(sections.get("cpu2"));
    int cpuPercent = cpuPercent(cpu1, cpu2);

    long totalKb = 0;
    long availableKb = 0;
    for (String line : sections.getOrDefault("mem", "").split("\\R")) {
      if (line.startsWith("MemTotal:")) {
        totalKb = firstLong(line);
      } else if (line.startsWith("MemAvailable:")) {
        availableKb = firstLong(line);
      }
    }
    long totalMb = totalKb / 1024;
    long usedMb = (totalKb - availableKb) / 1024;
    return new Metrics(true, cpuPercent, usedMb, totalMb, null);
  }

  private Specs linuxSpecs(String output, String os) {
    Map<String, String> sections = parseSections(output);
    String cpu = trimToUnknown(sections.get("cpu"));
    String cores = trimToUnknown(sections.get("cores"));
    if (!"Unknown".equals(cpu) && !"Unknown".equals(cores)) {
      cpu = cpu + " (" + cores + " threads)";
    }
    long totalKb = firstLong(sections.getOrDefault("mem", ""));
    String memory = totalKb > 0 ? formatGb(totalKb / 1024) : "Unknown";

    String board = sections.getOrDefault("board", "").strip().replaceAll("\\R+", " ");
    if (board.isBlank()) {
      board = "Unknown";
    }
    String gpu = sections.getOrDefault("gpu", "").strip().replaceAll("\\R+", ", ");
    if (gpu.isBlank()) {
      gpu = "Unknown";
    }
    return new Specs(true, os, cpu, board, memory, gpu, null);
  }

  private static long[] parseCpuLine(String line) {
    if (line == null) {
      return null;
    }
    String[] parts = line.trim().split("\\s+");
    long[] values = new long[parts.length - 1];
    for (int i = 1; i < parts.length; i++) {
      try {
        values[i - 1] = Long.parseLong(parts[i]);
      } catch (NumberFormatException e) {
        values[i - 1] = 0;
      }
    }
    return values;
  }

  private static int cpuPercent(long[] first, long[] second) {
    if (first == null || second == null || first.length < 5 || second.length < 5) {
      return -1;
    }
    long idle1 = first[3] + first[4];
    long idle2 = second[3] + second[4];
    long total1 = sum(first);
    long total2 = sum(second);
    long totalDelta = total2 - total1;
    long idleDelta = idle2 - idle1;
    if (totalDelta <= 0) {
      return -1;
    }
    long busy = totalDelta - idleDelta;
    return (int) Math.max(0, Math.min(100, Math.round(100.0 * busy / totalDelta)));
  }

  private static long sum(long[] values) {
    long total = 0;
    for (long value : values) {
      total += value;
    }
    return total;
  }

  // --- Windows (CIM via PowerShell) ----------------------------------------

  private static final String WINDOWS_METRICS_SCRIPT =
      "$os = Get-CimInstance Win32_OperatingSystem;"
          + " $cpu = (Get-CimInstance Win32_Processor |"
          + " Measure-Object -Property LoadPercentage -Average).Average;"
          + " [pscustomobject]@{ cpuPercent = $cpu; memTotalKb = $os.TotalVisibleMemorySize;"
          + " memFreeKb = $os.FreePhysicalMemory } | ConvertTo-Json -Compress";

  private static final String WINDOWS_SPECS_SCRIPT =
      "$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1;"
          + " $board = Get-CimInstance Win32_BaseBoard | Select-Object -First 1;"
          + " $mem = (Get-CimInstance Win32_PhysicalMemory |"
          + " Measure-Object -Property Capacity -Sum).Sum;"
          + " $gpu = (Get-CimInstance Win32_VideoController |"
          + " Select-Object -ExpandProperty Name) -join ', ';"
          + " [pscustomobject]@{ cpu = $cpu.Name;"
          + " cores = ($cpu.NumberOfCores.ToString() + ' cores / '"
          + " + $cpu.NumberOfLogicalProcessors.ToString() + ' threads');"
          + " motherboard = ($board.Manufacturer + ' ' + $board.Product);"
          + " memoryBytes = $mem; gpu = $gpu } | ConvertTo-Json -Compress";

  private Metrics windowsMetrics(String json) throws IOException {
    JsonNode node = objectMapper.readTree(json);
    int cpuPercent = node.path("cpuPercent").isNumber() ? node.get("cpuPercent").asInt() : -1;
    long totalKb = node.path("memTotalKb").asLong(0);
    long freeKb = node.path("memFreeKb").asLong(0);
    long totalMb = totalKb / 1024;
    long usedMb = (totalKb - freeKb) / 1024;
    return new Metrics(true, cpuPercent, usedMb, totalMb, null);
  }

  private Specs windowsSpecs(String json) throws IOException {
    JsonNode node = objectMapper.readTree(json);
    String cpu = blankToUnknown(node.path("cpu").asText(""));
    String cores = node.path("cores").asText("");
    if (!"Unknown".equals(cpu) && !cores.isBlank()) {
      cpu = cpu + " (" + cores + ")";
    }
    String board = blankToUnknown(node.path("motherboard").asText("").trim());
    long memoryBytes = node.path("memoryBytes").asLong(0);
    String memory = memoryBytes > 0 ? formatGb(memoryBytes / (1024 * 1024)) : "Unknown";
    String gpu = blankToUnknown(node.path("gpu").asText(""));
    return new Specs(true, "Windows", cpu, board, memory, gpu, null);
  }

  // --- Command execution ---------------------------------------------------

  private String runSsh(ServerProfile profile, String command) throws IOException {
    try (SshClient client = new SshClient(profile)) {
      SshClient.CommandResult result = client.exec(command);
      result.throwIfFailed();
      return result.stdout();
    }
  }

  private String runLocalShell(String command) throws IOException, InterruptedException {
    return runProcess(new String[] {"sh", "-c", command});
  }

  private String runLocalPowerShell(String script) throws IOException, InterruptedException {
    return runProcess(
        new String[] {"powershell", "-NoProfile", "-NonInteractive", "-Command", script});
  }

  private String runProcess(String[] command) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(false)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start();
    byte[] stdout = process.getInputStream().readAllBytes();
    if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("System command timed out");
    }
    return new String(stdout, StandardCharsets.UTF_8);
  }

  // --- Helpers -------------------------------------------------------------

  private static Map<String, String> parseSections(String output) {
    Map<String, String> sections = new HashMap<>();
    if (output == null) {
      return sections;
    }
    String current = null;
    StringBuilder buffer = new StringBuilder();
    for (String line : output.split("\\R")) {
      if (line.startsWith("@@")) {
        if (current != null) {
          sections.put(current, buffer.toString().strip());
        }
        current = line.substring(2).trim();
        buffer.setLength(0);
      } else if (current != null) {
        buffer.append(line).append('\n');
      }
    }
    if (current != null) {
      sections.put(current, buffer.toString().strip());
    }
    return sections;
  }

  private static long firstLong(String text) {
    if (text == null) {
      return 0;
    }
    for (String token : text.trim().split("\\s+")) {
      try {
        return Long.parseLong(token.replaceAll("[^0-9]", ""));
      } catch (NumberFormatException ignored) {
        // Skip non-numeric tokens (e.g. the "MemTotal:" label).
      }
    }
    return 0;
  }

  private static String formatGb(long megabytes) {
    return String.format("%.1f GB", megabytes / 1024.0);
  }

  private static String trimToUnknown(String value) {
    return blankToUnknown(value == null ? "" : value.strip());
  }

  private static String blankToUnknown(String value) {
    return value == null || value.isBlank() ? "Unknown" : value.strip();
  }

  private static boolean isWindows() {
    return osName().toLowerCase().contains("win");
  }

  private static String osName() {
    return System.getProperty("os.name", "Unknown");
  }

  private static String shortMessage(Exception e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  public record Metrics(
      boolean available, int cpuPercent, long memUsedMb, long memTotalMb, String error) {
    static Metrics unavailable(String error) {
      return new Metrics(false, -1, 0, 0, error);
    }

    public int memPercent() {
      return memTotalMb > 0 ? (int) Math.min(100, memUsedMb * 100 / memTotalMb) : 0;
    }

    public boolean cpuKnown() {
      return cpuPercent >= 0;
    }
  }

  public record Specs(
      boolean available,
      String os,
      String cpu,
      String motherboard,
      String memory,
      String gpu,
      String error) {
    static Specs unavailable(String error) {
      return new Specs(false, null, null, null, null, null, error);
    }
  }
}
