package nl.jessedezwart.piglinpanel.control

import nl.jessedezwart.piglinpanel.model.ServerProfile

/**
 * Builds the shell script that starts a server over SSH.
 *
 * The hard part is detachment. A command run through `ssh` is a child of the SSH channel: when the
 * channel closes the process is hung up, and while it lives its output keeps the channel open, so
 * the panel would sit waiting for a server that only stops in a year. [detached] therefore reopens
 * every standard stream onto files, puts the process in its own session, and returns immediately.
 *
 * Commands that already handle this — `screen -dmS`, `tmux new -d`, `systemctl start`,
 * `docker start` — are run [verbatim] instead, because their exit status is worth having and
 * wrapping them would only bury it.
 */
object ServerStartCommand {
    /** Startup output lands here, in the working directory, replaced on every start. */
    const val LOG_FILE = "piglinpanel-start.log"

    /** Printed by [detached] when the server is still alive shortly after launching. */
    const val RUNNING_MARKER = "@@running"

    private const val PID_FILE = ".piglinpanel-start.pid"

    /** How long to let the server prove it did not die on the spot, in seconds. */
    private const val SETTLE_SECONDS = 2

    private const val TAIL_LINES = 20

    /**
     * The script for a command that does not detach itself: launch it in its own session with all
     * three standard streams off the SSH channel, then check it is still running.
     *
     * The launched shell records its own pid, rather than the caller using `$!`, because `setsid`
     * may fork — leaving `$!` pointing at a process that has already exited, which would read as an
     * instant crash. When the server is gone, the tail of its output is the error message.
     */
    fun detached(workingDirectory: String, command: String): String {
        val inner = "echo \$\$ > ${quote(PID_FILE)}; exec $command"
        return buildString {
            appendLine("cd ${quotePath(workingDirectory)} || exit 1")
            appendLine("rm -f ${quote(PID_FILE)}")
            appendLine(
                "{ if command -v setsid >/dev/null 2>&1; then setsid nohup sh -c ${quote(inner)};" +
                    " else nohup sh -c ${quote(inner)}; fi; }" +
                    " > ${quote(LOG_FILE)} 2>&1 < /dev/null &",
            )
            appendLine("sleep $SETTLE_SECONDS")
            appendLine("pid=\$(cat ${quote(PID_FILE)} 2>/dev/null)")
            appendLine(
                "if [ -n \"\$pid\" ] && kill -0 \"\$pid\" 2>/dev/null;" +
                    " then echo '$RUNNING_MARKER';" +
                    " else tail -n $TAIL_LINES ${quote(LOG_FILE)} 2>/dev/null; fi",
            )
        }
    }

    /**
     * The script for a command that detaches itself. Stdin is still closed — these tools do not
     * read it, and leaving it attached invites a prompt nobody can answer — but stdout and stderr
     * come back so the panel can report what the tool said.
     */
    fun verbatim(workingDirectory: String, command: String): String =
        "cd ${quotePath(workingDirectory)} || exit 1\n$command < /dev/null\n"

    /**
     * Where the command runs: the configured directory when absolute or `~`-relative, otherwise
     * that directory inside the server root, and the server root itself when nothing is set.
     */
    fun workingDirectory(profile: ServerProfile): String {
        val root = profile.rootPath.trim().trimEnd('/').ifBlank { "." }
        val configured = profile.start.workingDirectory.trim().replace('\\', '/').trimEnd('/')
        return when {
            configured.isBlank() -> root
            configured.startsWith("/") || configured.startsWith("~") -> configured
            else -> "$root/$configured"
        }
    }

    /** Wraps [value] for the remote shell; the only character to fear inside single quotes is `'`. */
    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /**
     * Quotes a path, but hands `~` back to the shell as `$HOME` — quoting a leading tilde stops it
     * expanding, which would turn `~/minecraft` into a literal directory nobody has.
     */
    private fun quotePath(path: String): String = when {
        path == "~" -> "\"\$HOME\""
        path.startsWith("~/") -> "\"\$HOME\"/" + quote(path.removePrefix("~/"))
        else -> quote(path)
    }
}
