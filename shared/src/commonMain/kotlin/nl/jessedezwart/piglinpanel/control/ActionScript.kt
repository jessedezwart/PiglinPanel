package nl.jessedezwart.piglinpanel.control

import nl.jessedezwart.piglinpanel.model.ServerProfile

/** Builds the shell script that runs a [nl.jessedezwart.piglinpanel.model.ServerAction] over SSH. */
object ActionScript {
    /**
     * Changes into [workingDirectory] and runs [command] there. Stdin is closed — nothing can answer
     * a prompt over this channel, and an open stdin would leave such a command waiting forever —
     * while stdout and stderr come back so the panel can show what the command said.
     */
    fun build(workingDirectory: String, command: String): String =
        "cd ${quotePath(workingDirectory)} || exit 1\n$command < /dev/null\n"

    /**
     * Where a command runs: [directory] when absolute or `~`-relative, otherwise that directory
     * inside the server root, and the server root itself when nothing is set.
     */
    fun workingDirectory(profile: ServerProfile, directory: String): String {
        val root = profile.rootPath.trim().trimEnd('/').ifBlank { "." }
        val configured = directory.trim().replace('\\', '/').trimEnd('/')
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
