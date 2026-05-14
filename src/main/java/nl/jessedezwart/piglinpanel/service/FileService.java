package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.stereotype.Service;

@Service
public class FileService {
  private final ServerConnectionService connectionService;

  public FileService(ServerConnectionService connectionService) {
    this.connectionService = connectionService;
  }

  public List<FileEntry> list(ServerProfile profile, String relativePath) throws IOException {
    return connectionService.fileSystem(profile).list(relativePath);
  }

  public String readText(ServerProfile profile, String relativePath) throws IOException {
    return new String(
        connectionService.fileSystem(profile).readBytes(relativePath), StandardCharsets.UTF_8);
  }

  public void saveText(ServerProfile profile, String relativePath, String text) throws IOException {
    connectionService
        .fileSystem(profile)
        .writeBytes(relativePath, text.getBytes(StandardCharsets.UTF_8));
  }
}
