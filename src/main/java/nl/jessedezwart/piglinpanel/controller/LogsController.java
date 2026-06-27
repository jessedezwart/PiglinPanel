package nl.jessedezwart.piglinpanel.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.FileService;
import nl.jessedezwart.piglinpanel.service.LogService;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class LogsController extends BaseController {

  private final LogService logService;
  private final FileService fileService;

  public LogsController(
      ServerRegistryService registryService, LogService logService, FileService fileService) {
    super(registryService);
    this.logService = logService;
    this.fileService = fileService;
  }

  @GetMapping("/logs")
  public String logs(
      @RequestParam(required = false) String serverId,
      @RequestParam(required = false, defaultValue = "") String filter,
      Model model,
      HttpServletRequest request)
      throws IOException {
    populateLayout(model, serverId, "logs");
    ServerProfile profile = resolveServer(serverId);
    if (profile != null) {
      model.addAttribute("logFiles", logService.logFiles(profile));
      String content = logService.latestLog(profile);
      model.addAttribute("logContent", LogService.applyFilter(content, filter));
      model.addAttribute("currentLogPath", "latest.log");
      model.addAttribute("logPath", "latest.log");
      model.addAttribute("isLatest", true);
    } else {
      model.addAttribute("isLatest", false);
    }
    model.addAttribute("filter", filter);
    model.addAttribute("contentFragment", "fragments/logs :: content");
    if (isHtmxRequest(request)) {
      return "fragments/logs :: content";
    }
    return "layout";
  }

  @GetMapping("/logs/content")
  public String logContent(
      @RequestParam(required = false) String serverId,
      @RequestParam(required = false, defaultValue = "") String filter,
      Model model)
      throws IOException {
    ServerProfile profile = resolveServer(serverId);
    String content = "";
    if (profile != null) {
      content = logService.latestLog(profile);
    }
    model.addAttribute("logContent", LogService.applyFilter(content, filter));
    model.addAttribute("serverId", serverId);
    model.addAttribute("filter", filter);
    model.addAttribute("isLatest", true);
    model.addAttribute("logPath", "latest.log");
    return "fragments/logs :: log-content";
  }

  @GetMapping("/logs/file")
  public String logFile(
      @RequestParam(required = false) String serverId,
      @RequestParam String path,
      @RequestParam(required = false, defaultValue = "") String filter,
      Model model)
      throws IOException {
    ServerProfile profile = resolveServer(serverId);
    String content = "";
    if (profile != null) {
      content = fileService.readText(profile, profile.getLogsPath() + "/" + path);
    }
    model.addAttribute("logContent", LogService.applyFilter(content, filter));
    model.addAttribute("serverId", serverId);
    model.addAttribute("filter", filter);
    model.addAttribute("isLatest", false);
    model.addAttribute("logPath", path);
    return "fragments/logs :: log-content";
  }

}
