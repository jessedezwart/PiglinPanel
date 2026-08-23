package nl.jessedezwart.piglinpanel.config

/**
 * Keeps the secrets in config.json — SSH passwords and key passphrases, RCON passwords, API keys —
 * out of a file that gets copied around, backed up or pasted into a bug report.
 *
 * Each secret has a stable [id] derived from where it sits in the config (see [AppConfigStore]), so
 * an implementation can either transform the value in place, or park it somewhere else entirely and
 * leave a reference behind. [store] returns whatever should be written to config.json, and [read]
 * turns that back into the value the app uses.
 *
 * Implementations must round-trip blank values unchanged, and [read] must return anything it does
 * not recognise as-is, so config files written by earlier versions (or edited by hand) keep working.
 */
interface SecretCodec {
    fun store(id: String, plaintext: String): String

    fun read(id: String, stored: String): String

    /** Drops the secret held under [id], if the implementation holds one; called when a profile goes away. */
    fun forget(id: String) {}

    /** Used when nothing better is available; secrets are stored exactly as entered. */
    object Plaintext : SecretCodec {
        override fun store(id: String, plaintext: String): String = plaintext

        override fun read(id: String, stored: String): String = stored
    }
}
