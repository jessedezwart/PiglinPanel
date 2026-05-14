package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import nl.jessedezwart.piglinpanel.client.RconClient;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RconService {
  private static final Logger log = LoggerFactory.getLogger(RconService.class);

  public String execute(ServerProfile profile, String command) throws IOException {
    if (!profile.getRcon().isEnabled()) {
      throw new IOException("RCON is not enabled for this server");
    }
    try (RconClient client = new RconClient(profile.getRcon())) {
      log.info("Executing RCON command for {}", profile.getName());
      String response = client.command(command);
      log.info("RCON command completed for {}", profile.getName());
      return response;
    } catch (IOException e) {
      log.warn("RCON command failed for {}", profile.getName(), e);
      throw e;
    }
  }
}
