# Piglin Panel

A desktop panel for managing Minecraft servers — local or remote over SSH.

- **Overview**: online status, host hardware specs, CPU/memory metrics, and one-click actions
- **Mods**: scan mod folders, match against Modrinth, one-click updates, enable/disable/archive versions,
  and check the pack against another Minecraft version before moving to it
- **Files**: browse and edit server files, including NBT ⇄ SNBT editing
- **Logs**: view, filter, and live-tail server logs
- **RCON**: send commands to the running server
- **Console**: the panel's own log stream

Built with Kotlin Multiplatform: the domain logic lives in the `shared` module
(kotlinx.serialization, Ktor client, okio, coroutines — portable to future targets), and the UI is
Compose Multiplatform for Desktop in the `desktop` module. Configuration is stored at
`%APPDATA%\Piglinpanel\config.json` (Windows) or `~/.config/piglinpanel` (Linux/macOS), in the same
format as previous releases. Credentials — SSH passwords and key passphrases, RCON passwords, API
keys — are not in that file: they live in the operating system's own credential store (Windows
Credential Manager, macOS Keychain, Secret Service on Linux) under "Piglin Panel", and config.json
holds only a `keyring:v1:` reference. Values written by earlier releases are read as they were and
move into the store on the next save; machines with no credential store fall back to AES-GCM under a
`secret.key` file in the same folder.

SSH profiles with no key path and no password fall back to the default identities in `~/.ssh`, so a
host that already works with `ssh` needs no credentials stored here at all. Host keys are checked
against `~/.ssh/known_hosts` the way OpenSSH's `StrictHostKeyChecking=accept-new` does: an unknown
host is trusted once and pinned, and a host whose key has since changed is refused.

A profile reached over SSH can carry actions: named shell commands, such as
`docker compose restart`, that run on the host from a button on the Overview tab. Each runs in the
server root (or its own working directory) until it exits, and what it printed is shown. Overview
checks the server's status once when opened and again on Refresh; it does not poll.

## Development

```sh
./gradlew :desktop:run          # run the app
./gradlew :shared:jvmTest       # run the test suite
./gradlew check                 # everything
```

Any JDK 17+ works to launch Gradle; the build provisions its own JDK 21 toolchain automatically.

## Packaging

```sh
./gradlew :desktop:createDistributable               # self-contained app image (no installer)
./gradlew :desktop:packageDistributionForCurrentOS   # installer: NSIS .exe on Windows, .deb on Linux
```

Packaging uses [Potassium](https://github.com/sproctor/potassium), which needs Node.js 18+ on PATH
to build installers. Installers bundle a Java runtime, so end users don't need Java installed.

## Releases and updates

Every push to `main` runs `.github/workflows/release.yml`: it runs the tests, builds the Windows and
Linux installers, and publishes them as GitHub release `v1.0.<run number>` (bump the major.minor in
the workflow file). An installed copy checks the latest release on startup and offers to download
and install a newer one; a copy started with `./gradlew :desktop:run` never does.
