package nl.jessedezwart.piglinpanel.fs

import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.ssh.SshConnectionPool

/**
 * Local filesystem for LOCAL profiles, SSH-backed for SSH profiles.
 *
 * A new instance per call is cheap because the connection does not live here: SSH profiles share
 * [ssh], so two callers that each ask for a filesystem still talk over the same connection.
 */
class DefaultServerFileSystemProvider(
    private val ssh: SshConnectionPool,
) : ServerFileSystemProvider {
    override fun fileSystem(profile: ServerProfile): ServerFileSystem =
        when (profile.accessType) {
            AccessType.SSH -> SshServerFileSystem(profile, ssh)
            AccessType.LOCAL -> LocalServerFileSystem(profile)
        }
}
