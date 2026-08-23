Kotlin Multiplatform project: portable logic in `shared` (commonMain; JVM-only code in jvmMain), Compose Desktop UI in `desktop`.

- Build/run: `./gradlew :desktop:run` (JAVA_HOME must point at an installed JDK 17+; the build itself uses a toolchain-provisioned JDK 21).
- Tests: `./gradlew :shared:jvmTest`
- Package: `./gradlew :desktop:createDistributable` (app image) or `:desktop:packageMsi` (requires WiX Toolset).
- Prefer constructor injection wired in `desktop/.../di/AppGraph.kt`; no DI framework.
- New domain logic goes in `shared/commonMain` unless it needs JVM-only APIs (ProcessBuilder, sshj, AWT, Querz NBT).
