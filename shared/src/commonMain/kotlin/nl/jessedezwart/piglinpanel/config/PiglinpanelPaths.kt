package nl.jessedezwart.piglinpanel.config

import okio.Path

/**
 * Platform-specific configuration directory: `%APPDATA%\Piglinpanel` on Windows,
 * `~/.config/piglinpanel` elsewhere.
 */
expect fun piglinpanelConfigDir(): Path
