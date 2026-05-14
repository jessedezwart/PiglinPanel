package nl.jessedezwart.piglinpanel.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import nl.jessedezwart.piglinpanel.model.AccessType;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RconConfigResolverTests {
  @TempDir Path tempDir;

  @Test
  void infersEnabledRconFromServerProperties() throws Exception {
    Files.writeString(
        tempDir.resolve("server.properties"),
        """
        enable-rcon=true
        rcon.port=25576
        rcon.password=secret
        """);
    ServerProfile profile = new ServerProfile();
    profile.setRootPath(tempDir.toString());

    boolean refreshed = new RconConfigResolver(new ServerConnectionService()).refresh(profile);

    assertThat(refreshed).isTrue();
    assertThat(profile.getRcon().isEnabled()).isTrue();
    assertThat(profile.getRcon().getHost()).isEqualTo("127.0.0.1");
    assertThat(profile.getRcon().getPort()).isEqualTo(25576);
    assertThat(profile.getRcon().getPassword()).isEqualTo("secret");
  }

  @Test
  void usesSshHostAsRconHostForRemoteServers() {
    ServerProfile profile = new ServerProfile();
    profile.setAccessType(AccessType.SSH);
    profile.getSsh().setHost("minecraft.example.test");
    Properties properties = new Properties();
    properties.setProperty("enable-rcon", "true");

    new RconConfigResolver(new ServerConnectionService()).apply(profile, properties);

    assertThat(profile.getRcon().isEnabled()).isTrue();
    assertThat(profile.getRcon().getHost()).isEqualTo("minecraft.example.test");
  }
}
