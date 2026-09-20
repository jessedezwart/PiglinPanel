package nl.jessedezwart.piglinpanel.sysinfo

import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.ssh.SshConnectionPool
import okio.IOException

data class Metrics(
    val available: Boolean,
    val cpuPercent: Int,
    val memUsedMb: Long,
    val memTotalMb: Long,
    val error: String?,
) {
    companion object {
        fun unavailable(error: String?): Metrics = Metrics(false, -1, 0, 0, error)
    }

    fun memPercent(): Int = if (memTotalMb > 0) minOf(100, memUsedMb * 100 / memTotalMb).toInt() else 0

    fun cpuKnown(): Boolean = cpuPercent >= 0
}

data class Specs(
    val available: Boolean,
    val os: String?,
    val cpu: String?,
    val motherboard: String?,
    val memory: String?,
    val gpu: String?,
    val error: String?,
) {
    companion object {
        fun unavailable(error: String?): Specs = Specs(false, null, null, null, null, null, error)
    }
}

/**
 * Gathers hardware specifications and live performance metrics for the machine hosting a server.
 *
 * Per the project constraint, only OS-native sources are used: `/proc` on Linux (read directly for
 * local servers, via SSH for remote ones) and CIM/WMI through PowerShell on Windows (local servers
 * only). No third-party system-info libraries are involved.
 */
class SystemInfoService(private val ssh: SshConnectionPool) {
    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 12L

        private const val LINUX_METRICS_COMMAND =
            "echo '@@cpu1'; grep '^cpu ' /proc/stat; sleep 0.3;" +
                " echo '@@cpu2'; grep '^cpu ' /proc/stat;" +
                " echo '@@mem'; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo"

        private const val LINUX_SPECS_COMMAND =
            "echo '@@cpu'; grep -m1 '^model name' /proc/cpuinfo | cut -d: -f2-;" +
                " echo '@@cores'; grep -c '^processor' /proc/cpuinfo;" +
                " echo '@@mem'; grep '^MemTotal:' /proc/meminfo;" +
                " echo '@@board';" +
                " cat /sys/devices/virtual/dmi/id/board_vendor" +
                " /sys/devices/virtual/dmi/id/board_name 2>/dev/null;" +
                " echo '@@gpu'; (lspci 2>/dev/null | grep -iE 'vga|3d|display' | cut -d: -f3-) || true"

        private const val WINDOWS_METRICS_SCRIPT =
            "\$os = Get-CimInstance Win32_OperatingSystem;" +
                " \$cpu = (Get-CimInstance Win32_Processor |" +
                " Measure-Object -Property LoadPercentage -Average).Average;" +
                " [pscustomobject]@{ cpuPercent = \$cpu; memTotalKb = \$os.TotalVisibleMemorySize;" +
                " memFreeKb = \$os.FreePhysicalMemory } | ConvertTo-Json -Compress"

        private const val WINDOWS_SPECS_SCRIPT =
            "\$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1;" +
                " \$board = Get-CimInstance Win32_BaseBoard | Select-Object -First 1;" +
                " \$mem = (Get-CimInstance Win32_PhysicalMemory |" +
                " Measure-Object -Property Capacity -Sum).Sum;" +
                " \$gpu = (Get-CimInstance Win32_VideoController |" +
                " Select-Object -ExpandProperty Name) -join ', ';" +
                " [pscustomobject]@{ cpu = \$cpu.Name;" +
                " cores = (\$cpu.NumberOfCores.ToString() + ' cores / '" +
                " + \$cpu.NumberOfLogicalProcessors.ToString() + ' threads');" +
                " motherboard = (\$board.Manufacturer + ' ' + \$board.Product);" +
                " memoryBytes = \$mem; gpu = \$gpu } | ConvertTo-Json -Compress"

        private fun isWindows(): Boolean = osName().lowercase().contains("win")

        private fun osName(): String = System.getProperty("os.name", "Unknown")

        private fun shortMessage(e: Exception): String =
            e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "Error")

        private fun formatGb(megabytes: Long): String {
            val tenths = (megabytes / 1024.0 * 10).roundToLong()
            return "${tenths / 10}.${tenths % 10} GB"
        }

        private fun blankToUnknown(value: String?): String =
            if (value.isNullOrBlank()) "Unknown" else value.trim()
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun metrics(profile: ServerProfile): Metrics = try {
        when {
            profile.accessType == AccessType.SSH -> linuxMetrics(runSsh(profile, LINUX_METRICS_COMMAND))
            isWindows() -> windowsMetrics(runLocalPowerShell(WINDOWS_METRICS_SCRIPT))
            else -> linuxMetrics(runLocalShell(LINUX_METRICS_COMMAND))
        }
    } catch (e: Exception) {
        Metrics.unavailable(shortMessage(e))
    }

    suspend fun specs(profile: ServerProfile): Specs = try {
        when {
            profile.accessType == AccessType.SSH -> linuxSpecs(runSsh(profile, LINUX_SPECS_COMMAND), "Linux (remote)")
            isWindows() -> windowsSpecs(runLocalPowerShell(WINDOWS_SPECS_SCRIPT))
            else -> linuxSpecs(runLocalShell(LINUX_SPECS_COMMAND), osName())
        }
    } catch (e: Exception) {
        Specs.unavailable(shortMessage(e))
    }

    // --- Linux (/proc) -------------------------------------------------------

    private fun linuxMetrics(output: String): Metrics {
        val sections = parseSections(output)
        val cpuPercent = cpuPercent(parseCpuLine(sections["cpu1"]), parseCpuLine(sections["cpu2"]))

        var totalKb = 0L
        var availableKb = 0L
        for (line in (sections["mem"] ?: "").split(Regex("\\R"))) {
            when {
                line.startsWith("MemTotal:") -> totalKb = firstLong(line)
                line.startsWith("MemAvailable:") -> availableKb = firstLong(line)
            }
        }
        return Metrics(true, cpuPercent, (totalKb - availableKb) / 1024, totalKb / 1024, null)
    }

    private fun linuxSpecs(output: String, os: String): Specs {
        val sections = parseSections(output)
        var cpu = blankToUnknown(sections["cpu"])
        val cores = blankToUnknown(sections["cores"])
        if (cpu != "Unknown" && cores != "Unknown") {
            cpu = "$cpu ($cores threads)"
        }
        val totalKb = firstLong(sections["mem"] ?: "")
        val memory = if (totalKb > 0) formatGb(totalKb / 1024) else "Unknown"

        val board = (sections["board"] ?: "").trim().replace(Regex("\\R+"), " ").ifBlank { "Unknown" }
        val gpu = (sections["gpu"] ?: "").trim().replace(Regex("\\R+"), ", ").ifBlank { "Unknown" }
        return Specs(true, os, cpu, board, memory, gpu, null)
    }

    private fun parseCpuLine(line: String?): LongArray? {
        if (line == null) return null
        val parts = line.trim().split(Regex("\\s+"))
        return LongArray(parts.size - 1) { i -> parts[i + 1].toLongOrNull() ?: 0 }
    }

    private fun cpuPercent(first: LongArray?, second: LongArray?): Int {
        if (first == null || second == null || first.size < 5 || second.size < 5) {
            return -1
        }
        val idleDelta = (second[3] + second[4]) - (first[3] + first[4])
        val totalDelta = second.sum() - first.sum()
        if (totalDelta <= 0) {
            return -1
        }
        val busy = totalDelta - idleDelta
        return (100.0 * busy / totalDelta).roundToLong().coerceIn(0, 100).toInt()
    }

    // --- Windows (CIM via PowerShell) ----------------------------------------

    private fun windowsMetrics(jsonText: String): Metrics {
        val node = json.parseToJsonElement(jsonText).jsonObject
        val cpuPercent = node["cpuPercent"]?.jsonPrimitiveOrNull()?.doubleOrNull?.toInt() ?: -1
        val totalKb = node["memTotalKb"]?.jsonPrimitiveOrNull()?.longOrNull ?: 0
        val freeKb = node["memFreeKb"]?.jsonPrimitiveOrNull()?.longOrNull ?: 0
        return Metrics(true, cpuPercent, (totalKb - freeKb) / 1024, totalKb / 1024, null)
    }

    private fun windowsSpecs(jsonText: String): Specs {
        val node = json.parseToJsonElement(jsonText).jsonObject
        var cpu = blankToUnknown(node.stringOrNull("cpu"))
        val cores = node.stringOrNull("cores") ?: ""
        if (cpu != "Unknown" && cores.isNotBlank()) {
            cpu = "$cpu ($cores)"
        }
        val board = blankToUnknown(node.stringOrNull("motherboard")?.trim())
        val memoryBytes = node["memoryBytes"]?.jsonPrimitiveOrNull()?.longOrNull ?: 0
        val memory = if (memoryBytes > 0) formatGb(memoryBytes / (1024 * 1024)) else "Unknown"
        val gpu = blankToUnknown(node.stringOrNull("gpu"))
        return Specs(true, "Windows", cpu, board, memory, gpu, null)
    }

    private fun JsonElement.jsonPrimitiveOrNull() =
        try {
            jsonPrimitive
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun kotlinx.serialization.json.JsonObject.stringOrNull(key: String): String? {
        val element = this[key] ?: return null
        if (element is kotlinx.serialization.json.JsonNull) return null
        return element.jsonPrimitiveOrNull()?.content
    }

    // --- Command execution ---------------------------------------------------

    private suspend fun runSsh(profile: ServerProfile, command: String): String =
        ssh.withSession(profile) { client ->
            val result = client.exec(command)
            result.throwIfFailed()
            result.stdoutText()
        }

    private suspend fun runLocalShell(command: String): String =
        runProcess(listOf("sh", "-c", command))

    private suspend fun runLocalPowerShell(script: String): String =
        runProcess(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script))

    private suspend fun runProcess(command: List<String>): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()
        val stdout = process.inputStream.readAllBytes()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("System command timed out")
        }
        stdout.decodeToString()
    }

    // --- Helpers -------------------------------------------------------------

    private fun parseSections(output: String?): Map<String, String> {
        val sections = HashMap<String, String>()
        if (output == null) return sections
        var current: String? = null
        val buffer = StringBuilder()
        for (line in output.split(Regex("\\R"))) {
            if (line.startsWith("@@")) {
                current?.let { sections[it] = buffer.toString().trim() }
                current = line.substring(2).trim()
                buffer.setLength(0)
            } else if (current != null) {
                buffer.append(line).append('\n')
            }
        }
        current?.let { sections[it] = buffer.toString().trim() }
        return sections
    }

    private fun firstLong(text: String): Long {
        for (token in text.trim().split(Regex("\\s+"))) {
            token.replace(Regex("[^0-9]"), "").toLongOrNull()?.let { return it }
        }
        return 0
    }
}
