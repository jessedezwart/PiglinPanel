import com.seanproctor.potassium.dsl.LinuxTargetFormat
import com.seanproctor.potassium.dsl.WindowsTargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.potassium)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.code.editor)
    implementation(libs.filekit.dialogs.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.coil3)
    implementation(libs.ktor.client.cio)
    implementation(libs.potassium.updater)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// The release workflow sets RELEASE_VERSION to the tag it is about to publish (v1.0.42).
val releaseVersion = providers.environmentVariable("RELEASE_VERSION")
    .map { it.removePrefix("v") }
    .filter { it.isNotBlank() }
    .getOrElse("1.0.0")

potassium {
    mainClass = "nl.jessedezwart.piglinpanel.desktop.MainKt"

    appName = "Piglin Panel"
    // No space: the name ends up in release asset file names, where GitHub rewrites spaces and
    // the update manifest would then point at a file that is not there.
    packageName = "PiglinPanel"
    packageVersion = releaseVersion
    description = "A desktop panel for managing Minecraft servers"
    vendor = "Jesse de Zwart"
    homepage = "https://github.com/jessedezwart/piglinpanel"

    // What `:desktop:suggestRuntimeModules` reports beyond the plugin's defaults.
    modules(
        "java.instrument",
        "java.management",
        "java.security.jgss",
        "java.sql",
        "jdk.security.auth",
        "jdk.unsupported",
    )

    windows {
        targetFormats(WindowsTargetFormat.Nsis)
        menu = true
        iconFile.set(project.file("src/main/resources/icon.ico"))
        upgradeUuid = "6f5f3a52-9c1e-4b8a-b7a4-2f45f6f9d3a1"
    }

    linux {
        targetFormats(LinuxTargetFormat.Deb)
        packageName = "piglinpanel"
        iconFile.set(project.file("src/main/resources/icon.png"))
        debMaintainer = "Jesse de Zwart <jessedezwart@users.noreply.github.com>"
    }

    // Uploading is the release workflow's job; this only makes the build write the update
    // manifests (latest.yml, latest-linux.yml) the in-app updater reads from the release.
    publish {
        github {
            enabled = true
            owner = "jessedezwart"
            repo = "piglinpanel"
        }
    }
}
