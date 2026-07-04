package nl.jessedezwart.piglinpanel.fs

import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ServerProfile

/** Local filesystem for LOCAL profiles, SSH-backed for SSH profiles. */
object DefaultServerFileSystemProvider : ServerFileSystemProvider {
    override fun fileSystem(profile: ServerProfile): ServerFileSystem =
        when (profile.accessType) {
            AccessType.SSH -> SshServerFileSystem(profile)
            AccessType.LOCAL -> LocalServerFileSystem(profile)
        }
}
