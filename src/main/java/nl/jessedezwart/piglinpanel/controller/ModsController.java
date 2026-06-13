package nl.jessedezwart.piglinpanel.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import nl.jessedezwart.piglinpanel.model.ModFile;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.ModService;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class ModsController extends BaseController {

  private final ModService modService;

  public ModsController(ServerRegistryService registryService, ModService modService) {
    super(registryService);
    this.modService = modService;
  }

  @GetMapping("/mods")
  public String mods(
      @RequestParam(required = false) String serverId,
      Model model,
      HttpServletRequest request)
      throws IOException {
    populateLayout(model, serverId, "mods");
    ServerProfile profile = resolveServer(serverId);
    if (profile != null) {
      model.addAttribute("mods", modService.scan(profile, false));
    }
    model.addAttribute("contentFragment", "fragments/mods :: content");
    if (isHtmxRequest(request)) {
      return "fragments/mods :: content";
    }
    return "layout";
  }

  @PostMapping("/mods/check-updates")
  public String checkUpdates(
      @RequestParam(required = false) String serverId, Model model) throws IOException {
    populateLayout(model, serverId, "mods");
    ServerProfile profile = resolveServer(serverId);
    if (profile != null) {
      model.addAttribute("mods", modService.scan(profile, true));
    }
    return "fragments/mods :: content";
  }

  @PostMapping("/mods/update")
  public String updateMods(
      @RequestParam(required = false) String serverId,
      @RequestParam(required = false) List<String> modPaths,
      Model model)
      throws IOException {
    populateLayout(model, serverId, "mods");
    ServerProfile profile = resolveServer(serverId);
    if (profile != null && modPaths != null) {
      List<ModFile> allMods = modService.scanForUpdate(profile);
      for (ModFile mod : allMods) {
        if (modPaths.contains(mod.relativePath()) && mod.update() != null && mod.update().available()) {
          modService.applyUpdate(profile, mod);
        }
      }
      model.addAttribute("mods", modService.scan(profile, false));
    }
    return "fragments/mods :: content";
  }

  @PostMapping("/mods/delete")
  public String deleteMods(
      @RequestParam(required = false) String serverId,
      @RequestParam(required = false) List<String> modPaths,
      Model model)
      throws IOException {
    populateLayout(model, serverId, "mods");
    ServerProfile profile = resolveServer(serverId);
    if (profile != null && modPaths != null) {
      List<ModFile> allMods = modService.scan(profile, false);
      for (ModFile mod : allMods) {
        if (modPaths.contains(mod.relativePath())) {
          modService.delete(profile, mod);
        }
      }
      model.addAttribute("mods", modService.scan(profile, false));
    }
    return "fragments/mods :: content";
  }

  @PostMapping("/mods/upload")
  public String uploadMod(
      @RequestParam(required = false) String serverId,
      @RequestParam String folderId,
      @RequestParam MultipartFile file,
      Model model)
      throws IOException {
    populateLayout(model, serverId, "mods");
    ServerProfile profile = resolveServer(serverId);
    if (profile != null && !file.isEmpty()) {
      ModFolder folder = profile.getModFolders().stream()
          .filter(f -> folderId.equals(f.getId()))
          .findFirst()
          .orElse(profile.getModFolders().isEmpty() ? null : profile.getModFolders().get(0));
      if (folder != null) {
        modService.upload(profile, folder, file.getOriginalFilename(), file.getBytes());
      }
      model.addAttribute("mods", modService.scan(profile, false));
    }
    return "fragments/mods :: content";
  }
}
