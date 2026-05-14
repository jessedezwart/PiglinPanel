package nl.jessedezwart.piglinpanel.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.junit.jupiter.api.Test;

class RconServiceTests {
  @Test
  void rejectsCommandsWhenRconIsDisabled() {
    ServerProfile profile = new ServerProfile();
    profile.getRcon().setEnabled(false);

    assertThatThrownBy(() -> new RconService().execute(profile, "list"))
        .hasMessageContaining("not enabled");
  }
}
