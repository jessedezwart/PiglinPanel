package nl.jessedezwart.piglinpanel.config

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException

/** The operating system refused to hand over or accept a credential. */
class CredentialStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A place the operating system keeps secrets for the logged-in user, addressed by id.
 *
 * Small enough to fake in tests; [KeyringCredentialStore] is the real one.
 */
interface CredentialStore {
    /** The stored value, or null when nothing is stored under [id]. */
    fun get(id: String): String?

    fun set(id: String, value: String)

    fun remove(id: String)
}

/**
 * The user's OS credential store, via java-keyring: Windows Credential Manager, macOS Keychain, or
 * the Freedesktop Secret Service / KWallet on Linux.
 *
 * Entries are filed under [service] (what the user sees in Credential Manager or Keychain Access)
 * with the secret's id as the account name.
 */
class KeyringCredentialStore private constructor(
    private val keyring: Keyring,
    private val service: String,
) : CredentialStore {
    companion object {
        /** Opens the platform's store, or returns null when this machine has none to offer. */
        fun openOrNull(service: String): KeyringCredentialStore? = try {
            KeyringCredentialStore(Keyring.create(), service)
        } catch (e: BackendNotSupportedException) {
            null
        } catch (e: NoClassDefFoundError) {
            // The platform backend's native bindings are not on the classpath.
            null
        }
    }

    override fun get(id: String): String? = try {
        keyring.getPassword(service, id)
    } catch (e: PasswordAccessException) {
        // The backends report "no such entry" the same way as a real failure, so a missing
        // credential and a broken store are indistinguishable here; both mean "nothing to use".
        null
    }

    override fun set(id: String, value: String) {
        try {
            keyring.setPassword(service, id, value)
        } catch (e: PasswordAccessException) {
            throw CredentialStoreException("Could not save the credential for $id", e)
        }
    }

    override fun remove(id: String) {
        try {
            keyring.deletePassword(service, id)
        } catch (e: PasswordAccessException) {
            // Nothing stored under that id, which is the state we wanted anyway.
        }
    }
}
