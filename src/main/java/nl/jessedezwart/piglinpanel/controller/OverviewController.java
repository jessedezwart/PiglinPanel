package nl.jessedezwart.piglinpanel.controller;

import jakarta.servlet.http.HttpServletRequest;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class OverviewController extends BaseController {

  public OverviewController(ServerRegistryService registryService) {
    super(registryService);
  }

  @GetMapping("/overview")
  public String overview(
      @RequestParam(required = false) String serverId, Model model, HttpServletRequest request) {
    populateLayout(model, serverId, "overview");
    model.addAttribute("contentFragment", "fragments/overview :: content");
    if (isHtmxRequest(request)) {
      return "fragments/overview :: content";
    }
    return "layout";
  }
}
