package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import nl.jessedezwart.piglinpanel.model.AccessType;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.stereotype.Service;

@Service
public class ServerStatusService {
  private static final String LOCAL_HOST = "127.0.0.1";
  private static final int TIMEOUT_MILLIS = 1500;

  public ServerStatus statusFor(ServerProfile profile) {
    String host = resolveHost(profile);
    return new ServerStatus(host, isOnline(host));
  }

  String resolveHost(ServerProfile profile) {
    if (profile.getAccessType() == AccessType.SSH && !profile.getSsh().getHost().isBlank()) {
      return profile.getSsh().getHost().trim();
    }
    if (profile.getRcon().isEnabled() && !profile.getRcon().getHost().isBlank()) {
      return profile.getRcon().getHost().trim();
    }
    return LOCAL_HOST;
  }

  boolean isOnline(String host) {
    if (host == null || host.isBlank()) {
      return false;
    }
    return pingHost(host) || isReachable(host);
  }

  boolean pingHost(String host) {
    Process process = null;
    try {
      process =
          new ProcessBuilder(pingCommand(host))
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(TIMEOUT_MILLIS + 500L, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (process != null) {
        process.destroyForcibly();
      }
      return false;
    }
  }

  boolean isReachable(String host) {
    try {
      return InetAddress.getByName(host).isReachable(TIMEOUT_MILLIS);
    } catch (IOException e) {
      return false;
    }
  }

  private static List<String> pingCommand(String host) {
    if (isWindows()) {
      return List.of("ping", "-n", "1", "-w", String.valueOf(TIMEOUT_MILLIS), host);
    }
    return List.of("ping", "-c", "1", "-W", "2", host);
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  public record ServerStatus(String host, boolean online) {}
}
