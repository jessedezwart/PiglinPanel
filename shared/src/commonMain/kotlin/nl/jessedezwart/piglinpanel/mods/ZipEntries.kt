package nl.jessedezwart.piglinpanel.mods

/**
 * Reads a single entry out of a zip archive held in memory, or null when the archive does not
 * contain it (or is not a readable zip at all). Mod jars are read in full for hashing anyway, so
 * pulling their manifest out of the same bytes costs no extra I/O.
 */
expect fun readZipEntry(bytes: ByteArray, entryName: String): ByteArray?

/**
 * Every entry whose name satisfies [matches], read out of the same in-memory archive. Used for the
 * jars a mod bundles inside itself; an unreadable archive yields an empty list, never an error.
 */
expect fun readZipEntries(bytes: ByteArray, matches: (String) -> Boolean): List<ByteArray>
