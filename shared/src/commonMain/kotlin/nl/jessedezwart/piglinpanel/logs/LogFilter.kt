package nl.jessedezwart.piglinpanel.logs

object LogFilter {
    /** Keeps only the lines of [content] containing [filter], case-insensitively. */
    fun apply(content: String, filter: String?): String {
        if (filter.isNullOrBlank()) {
            return content
        }
        val lowerFilter = filter.lowercase()
        return buildString {
            for (line in content.split("\n")) {
                if (line.lowercase().contains(lowerFilter)) {
                    append(line).append('\n')
                }
            }
        }
    }
}
