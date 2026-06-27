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
    ServerStatusService service = new TestServerStatusService(true, false);

    ServerStatusService.ServerStatus status = service.statusFor(profile);

    assertThat(status.host()).isEqualTo("mc.example.test");
    assertThat(status.online()).isTrue();
  }

  @Test
  void usesRconHostForLocalServersWhenAvailable() {
    ServerProfile profile = new ServerProfile();
    profile.getRcon().setEnabled(true);
    profile.getRcon().setHost("lan-server.test");
    ServerStatusService service = new TestServerStatusService(false, false);

    ServerStatusService.ServerStatus status = service.statusFor(profile);

    assertThat(status.host()).isEqualTo("lan-server.test");
  }

  @Test
  void fallsBackToInetReachabilityWhenPingCommandFails() {
    ServerProfile profile = new ServerProfile();
    ServerStatusService service = new TestServerStatusService(false, true);

    ServerStatusService.ServerStatus status = service.statusFor(profile);

    assertThat(status.online()).isTrue();
  }

  @Test
  void marksServerOfflineWhenPingAndReachabilityFail() {
    ServerProfile profile = new ServerProfile();
    ServerStatusService service = new TestServerStatusService(false, false);

    ServerStatusService.ServerStatus status = service.statusFor(profile);

    assertThat(status.host()).isEqualTo("127.0.0.1");
    assertThat(status.online()).isFalse();
  }

  private static final class TestServerStatusService extends ServerStatusService {
    private final boolean pingResult;
    private final boolean reachabilityResult;

    private TestServerStatusService(boolean pingResult, boolean reachabilityResult) {
      this.pingResult = pingResult;
      this.reachabilityResult = reachabilityResult;
    }

    @Override
    boolean pingHost(String host) {
      return pingResult;
    }

    @Override
    boolean isReachable(String host) {
      return reachabilityResult;
    }
  }
}
