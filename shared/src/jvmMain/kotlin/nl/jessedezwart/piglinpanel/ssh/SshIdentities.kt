package nl.jessedezwart.piglinpanel.ssh

import java.io.File

/**
 * The user's `~/.ssh` directory: the keys OpenSSH itself would try when you run `ssh host` without
 * naming an identity. A profile that has neither a key path nor a password falls back to these, so
 * the common case — an SSH setup that already works from the terminal — needs no credentials in
 * Piglin Panel at all.
 */
object SshIdentities {
    /** Key file names OpenSSH tries by default, in its own order of preference. */
    private val DEFAULT_KEY_NAMES = listOf(
        "id_ed25519",
        "id_ecdsa",
        "id_ecdsa_sk",
        "id_ed25519_sk",
        "id_rsa",
        "id_dsa",
    )

    /** `~/.ssh`, whether or not it exists. */
    fun sshDirectory(): File = File(System.getProperty("user.home"), ".ssh")

    /** Existing default private keys in `~/.ssh`, most preferred first; empty when there are none. */
    fun defaultKeys(): List<File> {
        val dir = sshDirectory()
        if (!dir.isDirectory) return emptyList()
        return DEFAULT_KEY_NAMES.map { File(dir, it) }.filter { it.isFile && it.canRead() }
    }

    /** Expands a leading `~` so key paths can be written the way they appear in ssh_config. */
    fun expandHome(path: String): String {
        val trimmed = path.trim()
        if (trimmed != "~" && !trimmed.startsWith("~/") && !trimmed.startsWith("~\\")) return trimmed
        return File(System.getProperty("user.home"), trimmed.drop(1).trimStart('/', '\\')).path
    }
}
