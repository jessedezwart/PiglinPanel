package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import nl.jessedezwart.piglinpanel.client.RconClient;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.stereotype.Service;

@Service
public class RconService {
  public String execute(ServerProfile profile, String command) throws IOException {
    if (!profile.getRcon().isEnabled()) {
      throw new IOException("RCON is not enabled for this server");
    }
    try (RconClient client = new RconClient(profile.getRcon())) {
      return client.command(command);
    }
  }
}
