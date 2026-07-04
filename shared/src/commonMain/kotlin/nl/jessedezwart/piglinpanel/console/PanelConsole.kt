package nl.jessedezwart.piglinpanel.console

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * In-memory sink for Piglin Panel's own log output. The logging appender in the desktop module
 * feeds formatted log lines here; UI collectors receive the recent history (via the replay buffer)
 * plus any new lines in real time.
 */
object PanelConsole {
    private const val MAX_HISTORY = 500

    private val flow = MutableSharedFlow<String>(
        replay = MAX_HISTORY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val lines: SharedFlow<String> = flow

    /** Records a formatted log line; never blocks or throws (logging must not break). */
    fun publish(line: String?) {
        if (line == null) {
            return
        }
        flow.tryEmit(line)
    }

    /** Returns a snapshot of the buffered history. */
    fun history(): List<String> = flow.replayCache
}
