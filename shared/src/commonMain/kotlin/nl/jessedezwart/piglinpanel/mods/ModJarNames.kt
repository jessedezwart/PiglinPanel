package nl.jessedezwart.piglinpanel.mods

/**
 * How mod jars are named on disk. A jar is disabled by appending `.disabled`, which every loader
 * skips, so enabling and disabling are renames.
 */
internal object ModJarNames {
    const val DISABLED_SUFFIX = ".disabled"
    const val JAR_SUFFIX = ".jar"

    /** Whether a directory entry named [name] is a mod jar, enabled or disabled. */
    fun isModJar(name: String): Boolean = name.endsWith(JAR_SUFFIX) || name.endsWith(JAR_SUFFIX + DISABLED_SUFFIX)

    fun nextArchiveName(relativePath: String): String =
        if (relativePath.endsWith(DISABLED_SUFFIX)) relativePath else relativePath + DISABLED_SUFFIX

    fun enableName(relativePath: String): String =
        if (relativePath.endsWith(DISABLED_SUFFIX)) {
            relativePath.dropLast(DISABLED_SUFFIX.length)
        } else {
            relativePath
        }

    fun joinPath(folderPath: String, fileName: String): String = when {
        folderPath.isBlank() -> fileName
        folderPath.endsWith("/") -> folderPath + fileName
        else -> "$folderPath/$fileName"
    }

    fun parentPath(relativePath: String): String = relativePath.substringBeforeLast('/', "")

    /**
     * [relativePath] when nothing occupies it, otherwise the same name with a `~1`, `~2`, ...
     * tag inserted before the `.jar`.
     *
     * Two versions of a mod can carry the same file name -- an update that keeps its name
     * leaves exactly that behind -- and a move replaces its target, so archiving one on top of
     * the other silently deletes a version the user asked to keep. The tag goes before the
     * extension so the result is still a `.jar` (or `.jar.disabled`) that a scan picks up.
     */
    fun uniqueName(relativePath: String, taken: Set<String>): String {
        if (relativePath !in taken) return relativePath
        val disabled = relativePath.endsWith(DISABLED_SUFFIX)
        val stem = enableName(relativePath).removeSuffix(JAR_SUFFIX)
        var counter = 1
        while (true) {
            val candidate = "$stem~$counter$JAR_SUFFIX" + if (disabled) DISABLED_SUFFIX else ""
            if (candidate !in taken) return candidate
            counter++
        }
    }
}
