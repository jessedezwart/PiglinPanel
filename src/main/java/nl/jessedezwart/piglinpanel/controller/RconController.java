package nl.jessedezwart.piglinpanel.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.LogService;
import nl.jessedezwart.piglinpanel.service.RconService;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class RconController extends BaseController {

  private final RconService rconService;
  private final LogService logService;

  public RconController(
      ServerRegistryService registryService, RconService rconService, LogService logService) {
    super(registryService);
    this.rconService = rconService;
    this.logService = logService;
  }

  @GetMapping("/rcon")
  public String rcon(
      @RequestParam(required = false) String serverId, Model model, HttpServletRequest request)
      throws IOException {
    populateLayout(model, serverId, "rcon");
    ServerProfile profile = resolveServer(serverId);
    if (profile != null) {
      model.addAttribute("logContent", logService.latestLog(profile));
    }
    model.addAttribute("contentFragment", "fragments/rcon :: content");
    if (isHtmxRequest(request)) {
      return "fragments/rcon :: content";
    }
    return "layout";
  }

  @PostMapping("/rcon/command")
  public String executeCommand(
      @RequestParam(required = false) String serverId, @RequestParam String command, Model model) {
    ServerProfile profile = resolveServer(serverId);
    model.addAttribute("serverId", serverId);
    if (profile == null) {
      model.addAttribute("errorMessage", "No server selected");
      return "fragments/rcon :: command-error";
    }
    try {
      rconService.execute(profile, command);
      return "fragments/rcon :: command-ok";
    } catch (IOException e) {
      model.addAttribute("errorMessage", e.getMessage());
      return "fragments/rcon :: command-error";
    }
  }

  @GetMapping("/rcon/log")
  public String rconLog(@RequestParam(required = false) String serverId, Model model)
      throws IOException {
    ServerProfile profile = resolveServer(serverId);
    String content = "";
    if (profile != null) {
      content = logService.latestLog(profile);
    }
    model.addAttribute("logContent", content);
    model.addAttribute("serverId", serverId);
    return "fragments/rcon :: log-content";
  }
}
