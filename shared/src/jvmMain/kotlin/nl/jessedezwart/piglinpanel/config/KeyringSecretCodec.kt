package nl.jessedezwart.piglinpanel.config

/**
 * Keeps credentials in the operating system's own store — Windows Credential Manager, macOS
 * Keychain, Secret Service on Linux — and leaves only a reference (`keyring:v1:<id>`) in
 * config.json. The file then contains no secret material at all, and the secrets are protected by
 * the user's login rather than by a key file sitting next to them.
 *
 * [fallback] covers the two cases the store cannot: values written before this existed (they are
 * read through the fallback and move into the keyring on the next save), and machines where writing
 * to the store fails at the moment of saving.
 */
class KeyringSecretCodec(
    private val credentials: CredentialStore,
    private val fallback: SecretCodec = SecretCodec.Plaintext,
) : SecretCodec {
    companion object {
        private const val PREFIX = "keyring:v1:"

        /**
         * A codec backed by this machine's credential store, falling back to [fallback] entirely
         * when there is none — a Linux box with no keyring daemon, typically.
         */
        fun openOrFallback(service: String, fallback: SecretCodec): SecretCodec =
            KeyringCredentialStore.openOrNull(service)
                ?.let { KeyringSecretCodec(it, fallback) }
                ?: fallback
    }

    override fun store(id: String, plaintext: String): String {
        if (plaintext.isBlank()) {
            credentials.remove(id)
            return ""
        }
        return try {
            credentials.set(id, plaintext)
            PREFIX + id
        } catch (e: CredentialStoreException) {
            fallback.store(id, plaintext)
        }
    }

    override fun read(id: String, stored: String): String {
        if (!stored.startsWith(PREFIX)) return fallback.read(id, stored)
        // The id travels in the reference so a config file stays self-describing, and so a stored
        // value keeps resolving even if the way ids are derived ever changes.
        return credentials.get(stored.removePrefix(PREFIX)) ?: ""
    }

    override fun forget(id: String) {
        credentials.remove(id)
        fallback.forget(id)
    }
}
