package nl.jessedezwart.piglinpanel.service;

import nl.jessedezwart.piglinpanel.filesystem.LocalServerFileSystem;
import nl.jessedezwart.piglinpanel.filesystem.ServerFileSystem;
import nl.jessedezwart.piglinpanel.filesystem.SshServerFileSystem;
import nl.jessedezwart.piglinpanel.model.AccessType;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.stereotype.Service;

@Service
public class ServerConnectionService {
  public ServerFileSystem fileSystem(ServerProfile profile) {
    if (profile.getAccessType() == AccessType.SSH) {
      return new SshServerFileSystem(profile);
    }
    return new LocalServerFileSystem(profile);
  }
}
