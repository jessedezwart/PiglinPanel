package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import nl.jessedezwart.piglinpanel.model.AccessType;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.stereotype.Service;

/**
 * Determines whether a Minecraft server is actually online by opening a TCP connection to the
 * server port. A plain ICMP ping only tells us the host machine answers, which is why the status
 * dot used to stay green even when the Minecraft server itself was down.
 */
@Service
public class ServerStatusService {
  private static final String LOCAL_HOST = "127.0.0.1";
  private static final int DEFAULT_MINECRAFT_PORT = 25565;
  private static final int TIMEOUT_MILLIS = 1500;

  public ServerStatus statusFor(ServerProfile profile) {
    String host = resolveHost(profile);
    int port = resolvePort(profile);
    return new ServerStatus(host, port, isOnline(host, port));
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

  /**
   * Prefers the RCON port when enabled (it is only open while the server is running), otherwise the
   * default Minecraft game port.
   */
  int resolvePort(ServerProfile profile) {
    if (profile.getRcon().isEnabled() && profile.getRcon().getPort() > 0) {
      return profile.getRcon().getPort();
    }
    return DEFAULT_MINECRAFT_PORT;
  }

  boolean isOnline(String host, int port) {
    if (host == null || host.isBlank() || port <= 0) {
      return false;
    }
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public record ServerStatus(String host, int port, boolean online) {}
}
