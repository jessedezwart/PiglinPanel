package nl.jessedezwart.piglinpanel.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Properties;
import nl.jessedezwart.piglinpanel.model.AccessType;
import nl.jessedezwart.piglinpanel.model.RconConfig;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.stereotype.Service;

@Service
public class RconConfigResolver {
  private static final int DEFAULT_PORT = 25575;
  private static final String LOCAL_HOST = "127.0.0.1";

  private final ServerConnectionService connectionService;

  public RconConfigResolver(ServerConnectionService connectionService) {
    this.connectionService = connectionService;
  }

  public boolean refresh(ServerProfile profile) {
    try {
      Properties properties = readServerProperties(profile);
      apply(profile, properties);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  void apply(ServerProfile profile, Properties properties) {
    RconConfig rcon = profile.getRcon();
    boolean enabled = Boolean.parseBoolean(properties.getProperty("enable-rcon", "false"));

    rcon.setEnabled(enabled);
    rcon.setHost(hostFor(profile));
    rcon.setPort(parsePort(properties.getProperty("rcon.port")));
    rcon.setPassword(enabled ? properties.getProperty("rcon.password", "") : "");
  }

  private Properties readServerProperties(ServerProfile profile) throws IOException {
    byte[] bytes =
        connectionService.fileSystem(profile).readBytes(profile.getServerPropertiesPath());
    Properties properties = new Properties();
    properties.load(new ByteArrayInputStream(bytes));
    return properties;
  }

  private static String hostFor(ServerProfile profile) {
    if (profile.getAccessType() == AccessType.SSH && !profile.getSsh().getHost().isBlank()) {
      return profile.getSsh().getHost();
    }
    return LOCAL_HOST;
  }

  private static int parsePort(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT_PORT;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return DEFAULT_PORT;
    }
  }
}
