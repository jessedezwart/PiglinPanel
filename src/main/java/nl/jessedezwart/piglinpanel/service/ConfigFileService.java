package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.stereotype.Service;

@Service
public class ConfigFileService {
  private final FileService fileService;

  public ConfigFileService(FileService fileService) {
    this.fileService = fileService;
  }

  public String readServerProperties(ServerProfile profile) throws IOException {
    return fileService.readText(profile, profile.getServerPropertiesPath());
  }

  public void saveServerProperties(ServerProfile profile, String text) throws IOException {
    fileService.saveText(profile, profile.getServerPropertiesPath(), text);
  }
}
