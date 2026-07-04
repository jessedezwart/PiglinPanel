package nl.jessedezwart.piglinpanel.logs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import nl.jessedezwart.piglinpanel.fs.FileService
import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException

class LogService(private val fileService: FileService) {
    suspend fun latestLog(profile: ServerProfile): String =
        fileService.readText(profile, "${profile.logsPath}/latest.log")

    suspend fun logFiles(profile: ServerProfile): List<FileEntry> =
        fileService.list(profile, profile.logsPath)

    /**
     * Live-tails `latest.log`: emits the current content once, then any newly appended text, by
     * polling every [interval]. Replaces the old WebSocket log stream.
     */
    fun tail(profile: ServerProfile, interval: Duration = 2.seconds): Flow<String> = flow {
        var lastContent = ""
        while (true) {
            val content = try {
                latestLog(profile)
            } catch (e: IOException) {
                null
            }
            if (content != null && content != lastContent) {
                val appended = if (content.length > lastContent.length &&
                    content.startsWith(lastContent)
                ) {
                    content.substring(lastContent.length)
                } else {
                    content // rotated or rewritten: emit everything again
                }
                lastContent = content
                emit(appended)
            }
            delay(interval)
        }
    }.flowOn(Dispatchers.Default)
}
