package nl.jessedezwart.piglinpanel.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nl.jessedezwart.piglinpanel.model.AccessType;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.RconConfig;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.model.SshConfig;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class MainController extends BaseController {

  public MainController(ServerRegistryService registryService) {
    super(registryService);
  }

  @GetMapping("/")
  public String index(@RequestParam(required = false) String serverId) {
    ServerProfile profile = resolveServer(serverId);
    String id = profile != null ? profile.getId() : "";
    return "redirect:/overview" + (id.isBlank() ? "" : "?serverId=" + id);
  }

  @GetMapping("/servers/select")
  public String selectServer(@RequestParam String serverId) {
    ServerProfile profile = resolveServer(serverId);
    if (profile != null) {
      registryService.select(profile);
    }
    return "redirect:/overview?serverId=" + serverId;
  }

  @GetMapping("/servers/new-form")
  public String newServerForm(Model model) {
    ServerProfile profile = new ServerProfile();
    profile.setId(UUID.randomUUID().toString());
    model.addAttribute("editProfile", profile);
    model.addAttribute("modFoldersText", formatModFolders(profile.getModFolders()));
    model.addAttribute("isNew", true);
    return "fragments/server-form :: form";
  }

  @GetMapping("/servers/edit-form")
  public String editServerForm(@RequestParam(required = false) String serverId, Model model) {
    ServerProfile profile = resolveServer(serverId);
    if (profile == null) {
      profile = new ServerProfile();
      profile.setId(UUID.randomUUID().toString());
    }
    model.addAttribute("editProfile", profile);
    model.addAttribute("modFoldersText", formatModFolders(profile.getModFolders()));
    model.addAttribute("isNew", false);
    return "fragments/server-form :: form";
  }

  @PostMapping("/servers")
  public ResponseEntity<Void> saveServer(
      @RequestParam String id,
      @RequestParam String name,
      @RequestParam AccessType accessType,
      @RequestParam(defaultValue = "") String rootPath,
      @RequestParam(defaultValue = "logs") String logsPath,
      @RequestParam(defaultValue = "") String modFoldersText,
      @RequestParam(defaultValue = "") String sshHost,
      @RequestParam(defaultValue = "22") int sshPort,
      @RequestParam(defaultValue = "") String sshUsername,
      @RequestParam(defaultValue = "") String sshPassword,
      @RequestParam(defaultValue = "") String sshPrivateKeyPath,
      @RequestParam(defaultValue = "") String sshPrivateKeyPassphrase,
      @RequestParam(defaultValue = "false") boolean rconEnabled,
      @RequestParam(defaultValue = "127.0.0.1") String rconHost,
      @RequestParam(defaultValue = "25575") int rconPort,
      @RequestParam(defaultValue = "") String rconPassword) {

    ServerProfile profile = new ServerProfile();
    profile.setId(id);
    profile.setName(name);
    profile.setAccessType(accessType);
    profile.setRootPath(rootPath);
    profile.setLogsPath(logsPath);
    profile.setModFolders(parseModFolders(modFoldersText));

    SshConfig ssh = new SshConfig();
    ssh.setHost(sshHost);
    ssh.setPort(sshPort);
    ssh.setUsername(sshUsername);
    ssh.setPassword(sshPassword);
    ssh.setPrivateKeyPath(sshPrivateKeyPath);
    ssh.setPrivateKeyPassphrase(sshPrivateKeyPassphrase);
    profile.setSsh(ssh);

    RconConfig rcon = new RconConfig();
    rcon.setEnabled(rconEnabled);
    rcon.setHost(rconHost);
    rcon.setPort(rconPort);
    rcon.setPassword(rconPassword);
    profile.setRcon(rcon);

    registryService.save(profile);

    return ResponseEntity.ok()
        .header("HX-Redirect", "/overview?serverId=" + profile.getId())
        .build();
  }

  @PostMapping("/servers/{id}/delete")
  public ResponseEntity<Void> deleteServer(@PathVariable String id) {
    registryService.delete(id);
    return ResponseEntity.ok()
        .header("HX-Redirect", "/")
        .build();
  }

  static String formatModFolders(List<ModFolder> folders) {
    if (folders == null) return "";
    StringBuilder sb = new StringBuilder();
    for (ModFolder f : folders) {
      if (!sb.isEmpty()) sb.append('\n');
      sb.append(f.getPath());
    }
    return sb.toString();
  }

  static List<ModFolder> parseModFolders(String text) {
    List<ModFolder> folders = new ArrayList<>();
    if (text == null || text.isBlank()) return folders;
    for (String line : text.split("\\r?\\n")) {
      line = line.trim();
      if (line.isEmpty()) continue;
      String folderId = line.toLowerCase().replaceAll("[^a-z0-9]+", "-");
      folders.add(new ModFolder(folderId, line, line));
    }
    return folders;
  }
}
