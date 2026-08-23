package nl.jessedezwart.piglinpanel.config

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import okio.FileSystem
import okio.Path

/**
 * AES-GCM encryption for the secrets in config.json, keyed by a random 256-bit key held in a
 * separate file ([keyPath]) that is created on first use and locked down to the current user where
 * the filesystem supports it.
 *
 * This is the fallback behind [KeyringSecretCodec], used for two things: reading secrets written
 * before the OS credential store was used (they move into it on the next save), and machines with
 * no credential store at all — a Linux server box with no keyring daemon. It keeps passwords out of
 * the file people open, sync and share, but the key file sits beside them, so it is no protection
 * against someone who already has the user's account.
 *
 * Encrypted values are stored as `enc:v1:<base64(iv || ciphertext)>`. Anything without that prefix
 * is returned untouched, which is what migrates existing plain-text configs: they decrypt to
 * themselves and are re-written encrypted on the next save.
 */
class AesSecretCodec(
    private val fileSystem: FileSystem,
    private val keyPath: Path,
) : SecretCodec {
    private val random = SecureRandom()

    /** Null once key material has proven unusable, which downgrades this codec to plain text. */
    private val key: SecretKeySpec? by lazy { loadOrCreateKey() }

    override fun store(id: String, plaintext: String): String {
        if (plaintext.isBlank() || plaintext.startsWith(PREFIX)) return plaintext
        val secret = key ?: return plaintext
        return try {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, iv))
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(iv + encrypted)
        } catch (e: GeneralSecurityException) {
            plaintext
        }
    }

    override fun read(id: String, stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        val secret = key ?: return ""
        return try {
            val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secret,
                GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
            )
            cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES).toString(Charsets.UTF_8)
        } catch (e: RuntimeException) {
            // Wrong or lost key file: report the secret as unset rather than as garbage, so the
            // field simply reads empty and can be entered again.
            ""
        } catch (e: GeneralSecurityException) {
            ""
        }
    }

    private fun loadOrCreateKey(): SecretKeySpec? = try {
        val existing = if (fileSystem.exists(keyPath)) fileSystem.read(keyPath) { readUtf8() } else null
        val bytes = existing?.trim()?.takeIf { it.isNotEmpty() }?.let { Base64.getDecoder().decode(it) }
        if (bytes != null && bytes.size == KEY_BYTES) {
            SecretKeySpec(bytes, "AES")
        } else {
            SecretKeySpec(generateKeyFile(), "AES")
        }
    } catch (e: RuntimeException) {
        null
    } catch (e: okio.IOException) {
        null
    }

    private fun generateKeyFile(): ByteArray {
        val bytes = ByteArray(KEY_BYTES).also(random::nextBytes)
        keyPath.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(keyPath) { writeUtf8(Base64.getEncoder().encodeToString(bytes)) }
        restrictToOwner()
        return bytes
    }

    /** Best effort `chmod 600`; silently skipped on filesystems without POSIX permissions. */
    private fun restrictToOwner() {
        try {
            Files.setPosixFilePermissions(
                Paths.get(keyPath.toString()),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (e: UnsupportedOperationException) {
            // Windows: the file inherits the user profile's ACL, which is already user-scoped.
        } catch (e: java.io.IOException) {
            // Nothing to do — the key is written, just not locked down.
        }
    }

    private companion object {
        const val PREFIX = "enc:v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
