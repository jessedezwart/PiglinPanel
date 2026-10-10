package nl.jessedezwart.piglinpanel.desktop.ui.settings

import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ModFolder
import nl.jessedezwart.piglinpanel.model.ServerAction
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.model.SshConfig

/**
 * The profile editor's fields as typed. Kept apart from [ServerProfile] because what is typed is not
 * yet what is saved: paths are trimmed, blank rows dropped and the port parsed by [applyTo].
 */
internal data class ProfileDraft(
    val accessType: AccessType,
    val rootPath: String,
    val logsPath: String,
    val modFolders: List<ModFolder>,
    val sshHost: String,
    val sshPort: String,
    val sshUser: String,
    val sshPassword: String,
    val sshKeyPath: String,
    val sshKeyPassphrase: String,
    val auth: SshAuth,
    val actions: List<ServerAction>,
) {
    /** Whether enough is filled in to reach the server at all. */
    val complete: Boolean
        get() = rootPath.isNotBlank() && (accessType == AccessType.LOCAL || sshHost.isNotBlank())

    /** [profile] with this draft's fields in place of its own. */
    fun applyTo(profile: ServerProfile): ServerProfile = profile.copy(
        accessType = accessType,
        rootPath = rootPath.trim(),
        logsPath = logsPath.trim(),
        modFolders = modFolders.filter { it.path.isNotBlank() }.map { it.withId() },
        ssh = SshConfig(
            host = sshHost.trim(),
            port = sshPort.toIntOrNull() ?: 22,
            username = sshUser.trim(),
            // Only the credentials belonging to the chosen method are kept, so the saved profile
            // says unambiguously how it authenticates — and so switching to ~/.ssh really does
            // stop sending a password that was typed earlier.
            password = if (auth == SshAuth.Password) sshPassword else "",
            privateKeyPath = if (auth == SshAuth.KeyFile) sshKeyPath.trim() else "",
            privateKeyPassphrase = if (auth == SshAuth.KeyFile) sshKeyPassphrase else "",
        ),
        actions = actions
            .filter { it.command.isNotBlank() }
            .map { it.copy(name = it.name.trim(), command = it.command.trim(), workingDirectory = it.workingDirectory.trim()) },
    )

    companion object {
        fun of(profile: ServerProfile) = ProfileDraft(
            accessType = profile.accessType,
            rootPath = profile.rootPath,
            logsPath = profile.logsPath,
            modFolders = profile.modFolders,
            sshHost = profile.ssh.host,
            sshPort = profile.ssh.port.toString(),
            sshUser = profile.ssh.username,
            sshPassword = profile.ssh.password,
            sshKeyPath = profile.ssh.privateKeyPath,
            sshKeyPassphrase = profile.ssh.privateKeyPassphrase,
            auth = SshAuth.of(profile.ssh),
            actions = profile.actions,
        )
    }
}

/** How a profile authenticates over SSH. Derived from which credentials the profile carries. */
internal enum class SshAuth(val label: String) {
    DefaultKeys("Keys from ~/.ssh"),
    KeyFile("Key file"),
    Password("Password"),
    ;

    companion object {
        fun of(config: SshConfig): SshAuth = when {
            config.privateKeyPath.isNotBlank() -> KeyFile
            config.password.isNotBlank() -> Password
            else -> DefaultKeys
        }
    }
}

/** Mod folder ids are what the rest of the app keys on; keep them in step with the name. */
private fun ModFolder.withId(): ModFolder = copy(id = id.ifBlank { name.ifBlank { path } })
