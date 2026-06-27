package nl.jessedezwart.piglinpanel.controller;

import jakarta.servlet.http.HttpServletRequest;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import nl.jessedezwart.piglinpanel.service.ServerStatusService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class OverviewController extends BaseController {
  private final ServerStatusService serverStatusService;

  public OverviewController(
      ServerRegistryService registryService, ServerStatusService serverStatusService) {
    super(registryService);
    this.serverStatusService = serverStatusService;
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
}
