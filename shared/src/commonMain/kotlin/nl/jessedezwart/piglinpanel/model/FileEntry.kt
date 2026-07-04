package nl.jessedezwart.piglinpanel.model

import kotlin.time.Instant

data class FileEntry(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modifiedAt: Instant?,
)
