package nl.jessedezwart.piglinpanel.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import nl.jessedezwart.piglinpanel.model.ModFile;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.ModGroup;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.ModService;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Controller
public class ModsController extends BaseController {

  private static final Logger log = LoggerFactory.getLogger(ModsController.class);

  private final ModService modService;
  private final SpringTemplateEngine templateEngine;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public ModsController(
      ServerRegistryService registryService,
      ModService modService,
      SpringTemplateEngine templateEngine) {
    super(registryService);
    this.modService = modService;
    this.templateEngine = templateEngine;
  }

  @GetMapping("/mods")
  public String mods(
      @RequestParam(required = false) String serverId,
      @RequestParam(required = false, defaultValue = "false") boolean force,
      Model model,
      HttpServletRequest request)
      throws IOException {
    populateLayout(model, serverId, "mods");
    ServerProfile profile = resolveServer(serverId);
    populateMods(model, profile, false);
    model.addAttribute("updatesLoaded", false);
    model.addAttribute("force", force);
    model.addAttribute("contentFragment", "fragments/mods :: content");
    if (isHtmxRequest(request)) {
      return "fragments/mods :: content";
    }
    return "layout";
  }

  /**
   * Streams update-check progress over SSE so the page can show a live indicator, then sends the
   * fully rendered (and now cache-warm) mods table as a terminal {@code done} event.
   */
  @GetMapping("/mods/check-updates/stream")
  public SseEmitter streamCheckUpdates(
      @RequestParam(required = false) String serverId,
      @RequestParam(required = false, defaultValue = "false") boolean force) {
    SseEmitter emitter = new SseEmitter(0L);
    ServerProfile profile = resolveServer(serverId);
    String resolvedServerId = profile != null ? profile.getId() : serverId;
    executor.execute(
        () -> {
          try {
            List<ModFile> mods =
                profile == null
                    ? List.of()
                    : modService.scan(
                        profile,
                        true,
                        null,
                        force,
                        (phase, done, total, fileName) ->
                            sendProgress(emitter, phase, done, total, fileName));
            List<ModGroup> groups = modService.group(mods);
            String html = renderContent(resolvedServerId, profile, mods, groups);
            emitter.send(SseEmitter.event().name("done").data(html));
            emitter.complete();
          } catch (Exception e) {
            // Complete (not completeWithError) so we never trigger an error-page dispatch on the
            // already-committed SSE response; the client keeps the basic list and shows a hint.
            log.warn("Update-check stream failed", e);
            emitter.complete();
          }
        });
    return emitter;
  }

  private static void sendProgress(
      SseEmitter emitter, String phase, int done, int total, String fileName) {
    try {
      emitter.send(
          SseEmitter.event()
              .name("progress")
              .data(
                  Map.of(
                      "phase", phase,
                      "done", done,
                      "total", total,
                      "fileName", fileName == null ? "" : fileName)));
    } catch (IOException e) {
      // Client disconnected; the executor task will finish and the emitter is already broken.
      log.debug("Failed to send progress event", e);
    }
  }

  private String renderContent(
      String serverId, ServerProfile profile, List<ModFile> mods, List<ModGroup> groups) {
    Context context = new Context();
    context.setVariable("profile", profile);
    context.setVariable("serverId", serverId);
    context.setVariable("mods", mods);
    context.setVariable("groups", groups);
    context.setVariable("updatesLoaded", true);
    context.setVariable("force", false);
    return templateEngine.process("fragments/mods", Set.of("content"), context);
  }

  @PostMapping("/mods/activate")
  public String activate(
      @RequestParam(required = false) String serverId,
      @RequestParam String modPath,
      @RequestParam(required = false) String activePath,
      Model model)
      throws IOException {
    populateLayout(model, serverId, "mods");
    ServerProfile profile = resolveServer(serverId);
    if (profile != null) {
      modService.activate(profile, modPath, activePath);
      populateMods(model, profile, true);
    }
    model.addAttribute("updatesLoaded", true);
    model.addAttribute("force", false);
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
      List<ModFile> allMods = modService.scan(profile, true);
      for (ModFile mod : allMods) {
        if (modPaths.contains(mod.relativePath())
            && mod.update() != null
            && mod.update().available()) {
          modService.applyUpdate(profile, mod);
        }
      }
      populateMods(model, profile, true);
    }
    model.addAttribute("updatesLoaded", true);
    model.addAttribute("force", false);
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
      populateMods(model, profile, false);
    }
    model.addAttribute("updatesLoaded", false);
    model.addAttribute("force", false);
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
      ModFolder folder =
          profile.getModFolders().stream()
              .filter(f -> folderId.equals(f.getId()))
              .findFirst()
              .orElse(profile.getModFolders().isEmpty() ? null : profile.getModFolders().get(0));
      if (folder != null) {
        modService.upload(profile, folder, file.getOriginalFilename(), file.getBytes());
      }
      populateMods(model, profile, false);
    }
    model.addAttribute("updatesLoaded", false);
    model.addAttribute("force", false);
    return "fragments/mods :: content";
  }

  private void populateMods(Model model, ServerProfile profile, boolean checkUpdates)
      throws IOException {
    if (profile == null) {
      return;
    }
    List<ModFile> mods = modService.scan(profile, checkUpdates);
    model.addAttribute("mods", mods);
    model.addAttribute("groups", modService.group(mods));
  }
}
