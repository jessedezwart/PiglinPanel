package nl.jessedezwart.piglinpanel.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;
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
    byte[] bytes = connectionService.fileSystem(profile).readBytes(relativePath);
    if (relativePath != null && relativePath.toLowerCase().endsWith(".gz")) {
      bytes = decompressGzip(bytes);
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  public byte[] readBytes(ServerProfile profile, String relativePath) throws IOException {
    return connectionService.fileSystem(profile).readBytes(relativePath);
  }

  private static byte[] decompressGzip(byte[] bytes) throws IOException {
    try (var input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
      return input.readAllBytes();
    }
  }

  public void saveText(ServerProfile profile, String relativePath, String text) throws IOException {
    connectionService
        .fileSystem(profile)
        .writeBytes(relativePath, text.getBytes(StandardCharsets.UTF_8));
  }
}
