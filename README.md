# Piglin Panel

A desktop panel for managing Minecraft servers — local or remote over SSH.

- **Overview**: online status, host hardware specs, live CPU/memory metrics
- **Mods**: scan mod folders, match against Modrinth, one-click updates, enable/disable/archive versions
- **Files**: browse and edit server files, including NBT ⇄ SNBT editing
- **Logs**: view, filter, and live-tail server logs
- **RCON**: send commands to the running server
- **Console**: the panel's own log stream

Built with Kotlin Multiplatform: the domain logic lives in the `shared` module
(kotlinx.serialization, Ktor client, okio, coroutines — portable to future targets), and the UI is
Compose Multiplatform for Desktop in the `desktop` module. Configuration is stored at
`%APPDATA%\Piglinpanel\config.json` (Windows) or `~/.config/piglinpanel` (Linux/macOS), in the same
format as previous releases.

## Development

```sh
./gradlew :desktop:run          # run the app
./gradlew :shared:jvmTest       # run the test suite
./gradlew check                 # everything
```

Any JDK 17+ works to launch Gradle; the build provisions its own JDK 21 toolchain automatically.

## Packaging

```sh
./gradlew :desktop:createDistributable   # self-contained app image (no installer)
./gradlew :desktop:packageMsi            # Windows installer (requires WiX Toolset 3.x)
./gradlew :desktop:packageDeb            # Debian package (on Linux)
```

MSI/EXE packaging needs the WiX Toolset on PATH: `winget install WiXToolset.WiXToolset`.
Installers bundle a Java runtime, so end users don't need Java installed.
