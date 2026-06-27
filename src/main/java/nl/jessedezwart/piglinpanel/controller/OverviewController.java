package nl.jessedezwart.piglinpanel.controller;

import jakarta.servlet.http.HttpServletRequest;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import nl.jessedezwart.piglinpanel.service.ServerStatusService;
import nl.jessedezwart.piglinpanel.service.SystemInfoService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class OverviewController extends BaseController {
  private final ServerStatusService serverStatusService;
  private final SystemInfoService systemInfoService;

  public OverviewController(
      ServerRegistryService registryService,
      ServerStatusService serverStatusService,
      SystemInfoService systemInfoService) {
    super(registryService);
    this.serverStatusService = serverStatusService;
    this.systemInfoService = systemInfoService;
  }

  @GetMapping("/overview")
  public String overview(
      @RequestParam(required = false) String serverId, Model model, HttpServletRequest request) {
    ServerProfile profile = resolveServer(serverId);
    populateLayout(model, serverId, "overview");
    if (profile != null) {
      model.addAttribute("serverStatus", serverStatusService.statusFor(profile));
    }
    model.addAttribute("contentFragment", "fragments/overview :: content");
    if (isHtmxRequest(request)) {
      return "fragments/overview :: content";
    }
    return "layout";
  }

  @GetMapping("/overview/metrics")
  public String metrics(@RequestParam(required = false) String serverId, Model model) {
    ServerProfile profile = resolveServer(serverId);
    model.addAttribute("serverId", profile != null ? profile.getId() : serverId);
    if (profile != null) {
      model.addAttribute("metrics", systemInfoService.metrics(profile));
    }
    return "fragments/overview :: metrics";
  }

  @GetMapping("/overview/specs")
  public String specs(@RequestParam(required = false) String serverId, Model model) {
    ServerProfile profile = resolveServer(serverId);
    model.addAttribute("serverId", profile != null ? profile.getId() : serverId);
    if (profile != null) {
      model.addAttribute("specs", systemInfoService.specs(profile));
    }
    return "fragments/overview :: specs";
  }
}
