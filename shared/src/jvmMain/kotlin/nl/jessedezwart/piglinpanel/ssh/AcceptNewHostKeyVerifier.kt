package nl.jessedezwart.piglinpanel.ssh

import java.io.File
import java.io.IOException
import java.security.PublicKey
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts

/**
 * Host key checking against `~/.ssh/known_hosts`, following OpenSSH's `StrictHostKeyChecking=accept-new`:
 *
 * - a host already in `known_hosts` must present the key recorded there;
 * - a host that is not yet known is trusted once and written to the file, so it is pinned from then on;
 * - a host whose key has *changed* is refused, because that is what a man-in-the-middle looks like.
 *
 * A desktop panel has nowhere to ask "are you sure?" at connect time — connections happen while the
 * user is looking at a screen full of server data — so first contact is trusted silently, the way
 * `ssh` behaves for most people. What matters is that the key is pinned afterwards. This replaces
 * the previous `StrictHostKeyChecking=no`, which accepted a changed key without a word.
 */
class AcceptNewHostKeyVerifier(
    knownHosts: File = File(SshIdentities.sshDirectory(), "known_hosts"),
) : OpenSSHKnownHosts(knownHosts.also { it.parentFile?.mkdirs() }) {

    override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
        val entry = OpenSSHKnownHosts.HostEntry(null, hostname, KeyType.fromKey(key), key)
        entries().add(entry)
        try {
            write(entry)
        } catch (e: IOException) {
            // Trust it for this session even when known_hosts cannot be written (a read-only home
            // directory, say); refusing would leave the user unable to connect at all.
        }
        return true
    }

    override fun hostKeyChangedAction(hostname: String, key: PublicKey): Boolean {
        throw SSHException(
            "The host key for $hostname has changed — it is now ${KeyType.fromKey(key)} " +
                "${SecurityUtils.getFingerprint(key)}. Someone may be intercepting the connection. " +
                "If the server was genuinely rebuilt, remove its line from ${file.absolutePath}.",
        )
    }
}
