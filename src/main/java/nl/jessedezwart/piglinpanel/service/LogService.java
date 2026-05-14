package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import java.util.List;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.stereotype.Service;

@Service
public class LogService {
  private final FileService fileService;

  public LogService(FileService fileService) {
    this.fileService = fileService;
  }

  public String latestLog(ServerProfile profile) throws IOException {
    return fileService.readText(profile, profile.getLogsPath() + "/latest.log");
  }

  public List<FileEntry> logFiles(ServerProfile profile) throws IOException {
    return fileService.list(profile, profile.getLogsPath());
  }
}
