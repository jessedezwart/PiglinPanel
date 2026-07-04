import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
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
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation(libs.ktor.client.cio)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "nl.jessedezwart.piglinpanel.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "Piglin Panel"
            packageVersion = "1.0.0"
            vendor = "Jesse de Zwart"

            windows {
                menu = true
                upgradeUuid = "6f5f3a52-9c1e-4b8a-b7a4-2f45f6f9d3a1"
            }
        }
    }
}
