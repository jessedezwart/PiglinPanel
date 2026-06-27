package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import nl.jessedezwart.piglinpanel.client.ModrinthClient;
import nl.jessedezwart.piglinpanel.filesystem.ServerFileSystem;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ModFile;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.ModGroup;
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class ModService {
  private static final Logger log = LoggerFactory.getLogger(ModService.class);
  private static final String DISABLED_SUFFIX = ".disabled";
  private final ServerConnectionService connectionService;
  private final ModrinthClient modrinthClient;

  @Autowired @Lazy ModService self;
  @Autowired(required = false) CacheManager cacheManager;

  /** Reports scan progress so callers (e.g. the SSE endpoint) can show a live indicator. */
  @FunctionalInterface
  public interface ProgressListener {
    void onProgress(String phase, int done, int total, String fileName);
  }

  public ModService(ServerConnectionService connectionService, ModrinthClient modrinthClient) {
    this.connectionService = connectionService;
    this.modrinthClient = modrinthClient;
  }

  public List<ModFile> scan(ServerProfile profile, boolean checkUpdates) throws IOException {
    return scan(profile, checkUpdates, null);
  }

  public List<ModFile> scan(ServerProfile profile, boolean checkUpdates, Consumer<ModFile> onMod)
      throws IOException {
    return scan(profile, checkUpdates, onMod, false, null);
  }

  public List<ModFile> scan(
      ServerProfile profile,
      boolean checkUpdates,
      Consumer<ModFile> onMod,
      boolean forceRefresh,
      ProgressListener progress)
      throws IOException {
    log.info("Scanning mods for {} (checkUpdates={})", profile.getName(), checkUpdates);
    ServerFileSystem fileSystem = connectionService.fileSystem(profile);
    List<Candidate> candidates = new ArrayList<>();
    for (ModFolder folder : profile.getModFolders()) {
      for (FileEntry entry : fileSystem.list(folder.getPath())) {
        if (entry.directory()
            || (!entry.name().endsWith(".jar") && !entry.name().endsWith(".jar.disabled"))) {
          continue;
        }
        candidates.add(new Candidate(folder, entry));
      }
    }
    List<ModFile> mods = new ArrayList<>(candidates.size());
    List<LookupCandidate> lookupCandidates = new ArrayList<>();
    int total = candidates.size();
    int index = 0;
    for (Candidate candidate : candidates) {
      ModFolder folder = candidate.folder();
      FileEntry entry = candidate.entry();
      String sha1 = null;
      if (checkUpdates) {
        try {
          sha1 = self.resolveSha1(profile, fileSystem, entry);
        } catch (IOException e) {
          log.warn("Failed to hash mod {}", entry.path(), e);
        }
      }
      boolean archived = entry.name().endsWith(DISABLED_SUFFIX);
      if (checkUpdates && sha1 != null) {
        lookupCandidates.add(new LookupCandidate(entry, sha1));
      }
      ModFile modFile =
          new ModFile(
              folder.getId(),
              folder.getName(),
              folder.getPath(),
              entry.path(),
              entry.name(),
              entry.size(),
              archived,
              sha1,
              null);
      mods.add(modFile);
      index++;
      if (progress != null) {
        progress.onProgress("hashing", index, total, entry.name());
      }
      if (onMod != null && !checkUpdates) {
        onMod.accept(modFile);
      }
    }
    if (checkUpdates && !lookupCandidates.isEmpty()) {
      if (progress != null) {
        progress.onProgress("lookup", 0, lookupCandidates.size(), null);
      }
      applySuggestedUpdates(mods, lookupCandidates, forceRefresh);
    }
    if (checkUpdates && onMod != null) {
      mods.forEach(onMod);
    }
    log.info("Scan complete for {}: {} mods", profile.getName(), mods.size());
    return mods;
  }

  private record Candidate(ModFolder folder, FileEntry entry) {}

  private record LookupCandidate(FileEntry entry, String sha1) {}

  public void upload(ServerProfile profile, ModFolder folder, String fileName, byte[] bytes)
      throws IOException {
    log.info("Uploading {} to {}:{}", fileName, profile.getName(), folder.getPath());
    ServerFileSystem fileSystem = connectionService.fileSystem(profile);
    String relativePath = joinPath(folder.getPath(), fileName);
    fileSystem.writeBytes(relativePath, bytes);
  }

  public void delete(ServerProfile profile, ModFile modFile) throws IOException {
    log.info("Deleting {} from {}", modFile.relativePath(), profile.getName());
    connectionService.fileSystem(profile).delete(modFile.relativePath());
  }

  /**
   * Activates an archived ({@code .jar.disabled}) version by enabling it, after disabling the
   * currently active version of the same mod (if one is provided).
   */
  public void activate(ServerProfile profile, String archivedPath, String currentActivePath)
      throws IOException {
    log.info(
        "Activating {} (disabling {}) for {}",
        archivedPath,
        currentActivePath,
        profile.getName());
    if (currentActivePath != null && !currentActivePath.isBlank()) {
      disablePath(profile, currentActivePath);
    }
    enablePath(profile, archivedPath);
  }

  void disablePath(ServerProfile profile, String relativePath) throws IOException {
    String disabled = nextArchiveName(relativePath);
    if (!disabled.equals(relativePath)) {
      connectionService.fileSystem(profile).move(relativePath, disabled);
    }
  }

  void enablePath(ServerProfile profile, String relativePath) throws IOException {
    String enabled = enableName(relativePath);
    if (!enabled.equals(relativePath)) {
      connectionService.fileSystem(profile).move(relativePath, enabled);
    }
  }

  public void applyUpdate(ServerProfile profile, ModFile modFile) throws IOException {
    ModUpdate update = modFile.update();
    if (update == null || !update.available()) {
      throw new IOException("No update is available for " + modFile.fileName());
    }
    log.info(
        "Applying update for {} in {} to version {}",
        modFile.fileName(),
        profile.getName(),
        update.latestVersionName());
    ServerFileSystem fileSystem = connectionService.fileSystem(profile);
    String archivedName = nextArchiveName(modFile.relativePath());
    final byte[] bytes = modrinthClient.downloadUpdate(update);
    fileSystem.move(modFile.relativePath(), archivedName);
    String updatedPath = joinPath(modFile.folderPath(), update.fileName());
    fileSystem.writeBytes(updatedPath, bytes);
  }

  static String nextArchiveName(String relativePath) {
    return relativePath.endsWith(DISABLED_SUFFIX) ? relativePath : relativePath + DISABLED_SUFFIX;
  }

  static String enableName(String relativePath) {
    return relativePath.endsWith(DISABLED_SUFFIX)
        ? relativePath.substring(0, relativePath.length() - DISABLED_SUFFIX.length())
        : relativePath;
  }

  /**
   * Collapses scanned files into one {@link ModGroup} per mod (keyed by Modrinth project when
   * known, otherwise by file path), surfacing the active version with its disabled versions sorted
   * newest first.
   */
  public List<ModGroup> group(List<ModFile> mods) {
    Map<String, List<ModFile>> byKey = new LinkedHashMap<>();
    for (ModFile mod : mods) {
      byKey.computeIfAbsent(groupKey(mod), key -> new ArrayList<>()).add(mod);
    }
    List<ModGroup> groups = new ArrayList<>(byKey.size());
    for (Map.Entry<String, List<ModFile>> entry : byKey.entrySet()) {
      List<ModFile> files = entry.getValue();
      ModFile active = files.stream().filter(file -> !file.archived()).findFirst().orElse(null);
      ModFile representative = active != null ? active : files.get(0);
      List<ModFile> archived =
          files.stream()
              .filter(file -> file != active)
              .sorted(Comparator.comparing(ModService::publishedAt).reversed())
              .toList();
      ModUpdate update = active != null ? active.update() : null;
      groups.add(
          new ModGroup(
              entry.getKey(),
              displayName(representative),
              iconUrl(files),
              representative.folderName(),
              active,
              archived,
              update));
    }
    return groups;
  }

  private static String groupKey(ModFile mod) {
    if (mod.update() != null && mod.update().projectId() != null) {
      return "project:" + mod.update().projectId();
    }
    return "file:" + mod.relativePath();
  }

  private static String displayName(ModFile mod) {
    if (mod.update() != null
        && mod.update().projectName() != null
        && !mod.update().projectName().isBlank()) {
      return mod.update().projectName();
    }
    return mod.fileName();
  }

  private static String iconUrl(List<ModFile> files) {
    return files.stream()
        .map(ModFile::update)
        .filter(update -> update != null && update.iconUrl() != null)
        .map(ModUpdate::iconUrl)
        .findFirst()
        .orElse(null);
  }

  private static Instant publishedAt(ModFile mod) {
    if (mod.update() != null && mod.update().lastUpdatedAt() != null) {
      return mod.update().lastUpdatedAt();
    }
    return Instant.EPOCH;
  }

  private void applySuggestedUpdates(
      List<ModFile> mods, List<LookupCandidate> lookupCandidates, boolean forceRefresh)
      throws IOException {
    List<String> allHashes =
        lookupCandidates.stream().map(LookupCandidate::sha1).distinct().toList();

    Cache cache = updatesCache();
    Map<String, ModUpdate> resolved = new LinkedHashMap<>();
    List<String> missing = new ArrayList<>();
    for (String hash : allHashes) {
      ModUpdate cached =
          (!forceRefresh && cache != null) ? cache.get(hash, ModUpdate.class) : null;
      if (cached != null) {
        resolved.put(hash, cached);
      } else {
        missing.add(hash);
      }
    }

    if (!missing.isEmpty()) {
      Map<String, Optional<ModUpdate>> fetched;
      try {
        fetched = modrinthClient.findSuggestedUpdates(missing);
      } catch (IOException e) {
        log.warn("Modrinth bulk lookup failed", e);
        fetched = Map.of();
      }
      for (String hash : missing) {
        ModUpdate update = fetched.getOrDefault(hash, Optional.empty()).orElse(null);
        if (update != null) {
          resolved.put(hash, update);
          if (cache != null) {
            cache.put(hash, update);
          }
        }
      }
    }

    Map<String, LookupCandidate> candidatesByPath = new LinkedHashMap<>();
    for (LookupCandidate candidate : lookupCandidates) {
      candidatesByPath.put(candidate.entry().path(), candidate);
    }
    for (int i = 0; i < mods.size(); i++) {
      ModFile mod = mods.get(i);
      LookupCandidate lookupCandidate = candidatesByPath.get(mod.relativePath());
      if (lookupCandidate == null) {
        continue;
      }
      ModUpdate modUpdate = resolved.getOrDefault(lookupCandidate.sha1(), mod.update());
      mods.set(
          i,
          new ModFile(
              mod.folderId(),
              mod.folderName(),
              mod.folderPath(),
              mod.relativePath(),
              mod.fileName(),
              mod.size(),
              mod.archived(),
              mod.sha1(),
              modUpdate));
    }
  }

  private Cache updatesCache() {
    return cacheManager == null ? null : cacheManager.getCache("mod-updates");
  }

  @Cacheable(
      value = "mod-hashes",
      key =
          "#profile.id + ':' + #entry.path() + ':' + #entry.size()"
              + " + ':' + #entry.modifiedAt().toEpochMilli()")
  public String resolveSha1(ServerProfile profile, ServerFileSystem fileSystem, FileEntry entry)
      throws IOException {
    log.info("Hashing mod {}", entry.path());
    byte[] bytes = fileSystem.readBytes(entry.path());
    String sha1 = digest("SHA-1", bytes);
    log.info("Finished hashing mod {}", entry.path());
    return sha1;
  }

  private static String joinPath(String folderPath, String fileName) {
    if (folderPath == null || folderPath.isBlank()) {
      return fileName;
    }
    return folderPath.endsWith("/") ? folderPath + fileName : folderPath + "/" + fileName;
  }

  private static String digest(String algorithm, byte[] bytes) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance(algorithm);
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("Hash algorithm is unavailable: " + algorithm, e);
    }
  }
}
