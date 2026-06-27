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

  public static String applyFilter(String content, String filter) {
    if (filter == null || filter.isBlank()) {
      return content;
    }
    String lowerFilter = filter.toLowerCase();
    StringBuilder sb = new StringBuilder();
    for (String line : content.split("\n")) {
      if (line.toLowerCase().contains(lowerFilter)) {
        sb.append(line).append('\n');
      }
    }
    return sb.toString();
  }
}
