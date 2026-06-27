package nl.jessedezwart.piglinpanel.service;

import static org.assertj.core.api.Assertions.assertThat;

import nl.jessedezwart.piglinpanel.model.AccessType;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.junit.jupiter.api.Test;

class ServerStatusServiceTests {
  @Test
  void prefersSshHostForRemoteServers() {
    ServerProfile profile = new ServerProfile();
    profile.setAccessType(AccessType.SSH);
    profile.getSsh().setHost("mc.example.test");
    ServerStatusService service = new TestServerStatusService(true);

    ServerStatusService.ServerStatus status = service.statusFor(profile);

    assertThat(status.host()).isEqualTo("mc.example.test");
    assertThat(status.online()).isTrue();
  }

  @Test
  void usesRconHostAndPortForLocalServersWhenAvailable() {
    ServerProfile profile = new ServerProfile();
    profile.getRcon().setEnabled(true);
    profile.getRcon().setHost("lan-server.test");
    profile.getRcon().setPort(25575);
    ServerStatusService service = new TestServerStatusService(true);

    ServerStatusService.ServerStatus status = service.statusFor(profile);

    assertThat(status.host()).isEqualTo("lan-server.test");
    assertThat(status.port()).isEqualTo(25575);
  }

  @Test
  void fallsBackToDefaultMinecraftPortWhenRconDisabled() {
    ServerProfile profile = new ServerProfile();
    ServerStatusService service = new TestServerStatusService(false);

    ServerStatusService.ServerStatus status = service.statusFor(profile);

    assertThat(status.host()).isEqualTo("127.0.0.1");
    assertThat(status.port()).isEqualTo(25565);
  }

  @Test
  void marksServerOfflineWhenPortIsClosed() {
    ServerProfile profile = new ServerProfile();
    ServerStatusService service = new TestServerStatusService(false);

    ServerStatusService.ServerStatus status = service.statusFor(profile);

    assertThat(status.online()).isFalse();
  }

  private static final class TestServerStatusService extends ServerStatusService {
    private final boolean portOpen;

    private TestServerStatusService(boolean portOpen) {
      this.portOpen = portOpen;
    }

    @Override
    boolean isOnline(String host, int port) {
      return portOpen;
    }
  }
}
