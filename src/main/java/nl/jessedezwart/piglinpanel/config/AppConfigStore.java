package nl.jessedezwart.piglinpanel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import nl.jessedezwart.piglinpanel.model.AppConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AppConfigStore {
  private final ObjectMapper objectMapper;
  private final Path configPath;

  @Autowired
  public AppConfigStore(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    this.configPath = resolveConfigPath();
  }

  AppConfigStore(ObjectMapper objectMapper, Path configPath) {
    this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    this.configPath = configPath;
  }

  public AppConfig load() {
    if (!Files.exists(configPath)) {
      return new AppConfig();
    }
    try {
      return objectMapper.readValue(configPath.toFile(), AppConfig.class);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read app config from " + configPath, e);
    }
  }

  public void save(AppConfig config) {
    try {
      Files.createDirectories(configPath.getParent());
      objectMapper.writeValue(configPath.toFile(), config);
    } catch (IOException e) {
      throw new IllegalStateException("Could not save app config to " + configPath, e);
    }
  }

  public Path configPath() {
    return configPath;
  }

  private static Path resolveConfigPath() {
    String appData = System.getenv("APPDATA");
    if (appData != null && !appData.isBlank()) {
      return Path.of(appData, "Piglinpanel", "config.json");
    }
    return Path.of(System.getProperty("user.home"), ".config", "piglinpanel", "config.json");
  }
}
