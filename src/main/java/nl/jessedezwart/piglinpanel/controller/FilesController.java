package nl.jessedezwart.piglinpanel.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.FileService;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class FilesController extends BaseController {

  private final FileService fileService;

  public FilesController(ServerRegistryService registryService, FileService fileService) {
    super(registryService);
    this.fileService = fileService;
  }

  @GetMapping("/files")
  public String files(
      @RequestParam(required = false) String serverId,
      @RequestParam(required = false, defaultValue = "") String path,
      Model model,
      HttpServletRequest request)
      throws IOException {
    populateLayout(model, serverId, "files");
    ServerProfile profile = resolveServer(serverId);
    if (profile != null) {
      model.addAttribute("entries", fileService.list(profile, path));
    }
    model.addAttribute("currentPath", path);
    model.addAttribute("contentFragment", "fragments/files :: content");
    if (isHtmxRequest(request)) {
      return "fragments/files :: content";
    }
    return "layout";
  }

  @GetMapping("/files/list")
  public String listChildren(
      @RequestParam(required = false) String serverId,
      @RequestParam(required = false, defaultValue = "") String path,
      Model model)
      throws IOException {
    ServerProfile profile = resolveServer(serverId);
    List<FileEntry> entries = List.of();
    if (profile != null) {
      entries = fileService.list(profile, path);
    }
    model.addAttribute("entries", entries);
    model.addAttribute("serverId", serverId);
    model.addAttribute("currentPath", path);
    return "fragments/files :: file-children";
  }

  @GetMapping("/files/open")
  public String openFile(
      @RequestParam(required = false) String serverId,
      @RequestParam String path,
      Model model)
      throws IOException {
    ServerProfile profile = resolveServer(serverId);
    model.addAttribute("serverId", serverId);
    model.addAttribute("openPath", path);
    if (profile != null) {
      if (isLikelyText(path)) {
        String content = fileService.readText(profile, path);
        model.addAttribute("fileContent", content);
        model.addAttribute("isBinary", false);
      } else {
        model.addAttribute("isBinary", true);
      }
    }
    return "fragments/files :: editor";
  }

  @PostMapping("/files/save")
  public String saveFile(
      @RequestParam(required = false) String serverId,
      @RequestParam String path,
      @RequestParam String content,
      Model model)
      throws IOException {
    ServerProfile profile = resolveServer(serverId);
    if (profile != null) {
      fileService.saveText(profile, path, content);
      model.addAttribute("fileContent", content);
    }
    model.addAttribute("serverId", serverId);
    model.addAttribute("openPath", path);
    model.addAttribute("isBinary", false);
    model.addAttribute("saved", true);
    return "fragments/files :: editor";
  }

  @GetMapping("/files/download")
  public ResponseEntity<byte[]> downloadFile(
      @RequestParam(required = false) String serverId,
      @RequestParam String path)
      throws IOException {
    ServerProfile profile = resolveServer(serverId);
    if (profile == null) {
      return ResponseEntity.notFound().build();
    }
    byte[] bytes = fileService.readBytes(profile, path);
    String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(bytes.length)
        .body(bytes);
  }

  private static boolean isLikelyText(String path) {
    if (path == null) {
      return false;
    }
    String lower = path.toLowerCase();
    return lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".json")
        || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".toml")
        || lower.endsWith(".properties") || lower.endsWith(".conf") || lower.endsWith(".cfg")
        || lower.endsWith(".xml") || lower.endsWith(".md") || lower.endsWith(".sh")
        || lower.endsWith(".bat") || lower.endsWith(".cmd") || lower.endsWith(".ini")
        || lower.endsWith(".env") || lower.endsWith(".java") || lower.endsWith(".py");
  }
}
