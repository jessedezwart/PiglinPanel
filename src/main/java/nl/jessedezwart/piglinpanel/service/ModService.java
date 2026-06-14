package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class ModService {
  private static final Logger log = LoggerFactory.getLogger(ModService.class);
  private final ServerConnectionService connectionService;
  private final ModrinthClient modrinthClient;

  @Autowired @Lazy ModService self;

  public ModService(ServerConnectionService connectionService, ModrinthClient modrinthClient) {
    this.connectionService = connectionService;
    this.modrinthClient = modrinthClient;
  }

  public List<ModFile> scan(ServerProfile profile, boolean checkUpdates) throws IOException {
    return scan(profile, checkUpdates, null);
  }

  public List<ModFile> scan(ServerProfile profile, boolean checkUpdates, Consumer<ModFile> onMod)
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
      boolean archived = entry.name().endsWith(".disabled");
      if (checkUpdates && !archived && sha1 != null) {
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
      if (onMod != null && !checkUpdates) {
        onMod.accept(modFile);
      }
    }
    if (checkUpdates) {
      if (!lookupCandidates.isEmpty()) {
        applySuggestedUpdates(mods, lookupCandidates);
      }
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
    return relativePath.endsWith(".disabled") ? relativePath : relativePath + ".disabled";
  }

  private void applySuggestedUpdates(List<ModFile> mods, List<LookupCandidate> lookupCandidates)
      throws IOException {
    List<String> hashes = lookupCandidates.stream().map(LookupCandidate::sha1).distinct().toList();
    Map<String, Optional<ModUpdate>> suggestedUpdates;
    try {
      suggestedUpdates = modrinthClient.findSuggestedUpdates(hashes);
    } catch (IOException e) {
      log.warn("Modrinth bulk lookup failed", e);
      return;
    }

    Map<String, LookupCandidate> candidatesByPath = new LinkedHashMap<>();
    for (LookupCandidate candidate : lookupCandidates) {
      candidatesByPath.put(candidate.entry().path(), candidate);
    }
    for (int index = 0; index < mods.size(); index++) {
      ModFile mod = mods.get(index);
      LookupCandidate lookupCandidate = candidatesByPath.get(mod.relativePath());
      if (lookupCandidate == null) {
        continue;
      }
      ModUpdate modUpdate =
          suggestedUpdates
              .getOrDefault(lookupCandidate.sha1(), Optional.empty())
              .orElse(mod.update());
      mods.set(
          index,
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
