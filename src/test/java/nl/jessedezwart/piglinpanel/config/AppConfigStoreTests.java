package nl.jessedezwart.piglinpanel.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import nl.jessedezwart.piglinpanel.model.AppConfig;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigStoreTests {
  @TempDir Path tempDir;

  @Test
  void savesAndLoadsServerProfiles() {
    AppConfigStore store = new AppConfigStore(new ObjectMapper(), tempDir.resolve("config.json"));
    AppConfig config = new AppConfig();
    ServerProfile profile = new ServerProfile();
    profile.setName("Test server");
    profile.setRootPath("C:/server");
    config.getServers().add(profile);
    config.setSelectedServerId(profile.getId());

    store.save(config);
    AppConfig loaded = store.load();

    assertThat(loaded.getServers()).hasSize(1);
    assertThat(loaded.getServers().getFirst().getName()).isEqualTo("Test server");
    assertThat(loaded.getSelectedServerId()).isEqualTo(profile.getId());
  }
}
