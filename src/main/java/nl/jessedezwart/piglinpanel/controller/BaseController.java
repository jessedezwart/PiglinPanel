package nl.jessedezwart.piglinpanel.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import org.springframework.ui.Model;

abstract class BaseController {

  protected final ServerRegistryService registryService;

  protected BaseController(ServerRegistryService registryService) {
    this.registryService = registryService;
  }

  protected ServerProfile resolveServer(String serverId) {
    return registryService.resolve(serverId);
  }

  protected void populateLayout(Model model, String serverId, String currentView) {
    ServerProfile profile = resolveServer(serverId);
    List<ServerProfile> servers = registryService.servers();
    model.addAttribute("profile", profile);
    model.addAttribute("servers", servers);
    model.addAttribute("serverId", profile != null ? profile.getId() : serverId);
    model.addAttribute("currentView", currentView);
  }

  protected boolean isHtmxRequest(HttpServletRequest request) {
    return request.getHeader("HX-Request") != null;
  }
}
