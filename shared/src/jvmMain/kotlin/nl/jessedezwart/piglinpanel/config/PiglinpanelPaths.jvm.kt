package nl.jessedezwart.piglinpanel.config

import okio.Path
import okio.Path.Companion.toPath

actual fun piglinpanelConfigDir(): Path {
    val appData = System.getenv("APPDATA")
    if (!appData.isNullOrBlank()) {
        return appData.toPath() / "Piglinpanel"
    }
    return System.getProperty("user.home").toPath() / ".config" / "piglinpanel"
}
