package nl.jessedezwart.piglinpanel.config;

import java.nio.file.Path;

public final class PiglinpanelPaths {
  private PiglinpanelPaths() {}

  public static Path configDir() {
    String appData = System.getenv("APPDATA");
    if (appData != null && !appData.isBlank()) {
      return Path.of(appData, "Piglinpanel");
    }
    return Path.of(System.getProperty("user.home"), ".config", "piglinpanel");
  }

  public static Path cacheDir() {
    return configDir().resolve("cache");
  }
}
