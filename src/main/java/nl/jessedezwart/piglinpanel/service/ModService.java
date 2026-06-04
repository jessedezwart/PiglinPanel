package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import nl.jessedezwart.piglinpanel.client.ModrinthClient;
import nl.jessedezwart.piglinpanel.config.ModHashCacheStore;
import nl.jessedezwart.piglinpanel.filesystem.ServerFileSystem;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ModFile;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ModService {
  private static final Logger log = LoggerFactory.getLogger(ModService.class);
  private final ServerConnectionService connectionService;
  private final ModrinthClient modrinthClient;
  private final ModHashCacheStore modHashCacheStore;

  public ModService(
      ServerConnectionService connectionService,
      ModrinthClient modrinthClient,
      ModHashCacheStore modHashCacheStore) {
    this.connectionService = connectionService;
    this.modrinthClient = modrinthClient;
    this.modHashCacheStore = modHashCacheStore;
  }

  public List<ModFile> scan(ServerProfile profile, boolean checkUpdates) throws IOException {
    return scan(profile, checkUpdates, null);
  }

  public List<ModFile> scan(
      ServerProfile profile, boolean checkUpdates, Consumer<ModFile> onMod)
      throws IOException {
    log.info("Scanning mods for {} (checkUpdates={})", profile.getName(), checkUpdates);
    ServerFileSystem fileSystem = connectionService.fileSystem(profile);
    List<Candidate> candidates = new ArrayList<>();
    for (ModFolder folder : profile.getModFolders()) {
      for (FileEntry entry : fileSystem.list(folder.getPath())) {
        if (entry.directory()
            || (!entry.name().endsWith(".jar") && !entry.name().endsWith(".jar.old"))) {
          continue;
        }
        candidates.add(new Candidate(folder, entry));
      }
    }
    List<ModFile> mods = new ArrayList<>(candidates.size());
    for (Candidate candidate : candidates) {
      ModFolder folder = candidate.folder();
      FileEntry entry = candidate.entry();
      String sha1 = null;
      if (checkUpdates) {
        try {
          sha1 = resolveSha1(profile, fileSystem, entry);
        } catch (IOException e) {
          log.warn("Failed to hash mod {}", entry.path(), e);
        }
      }
      boolean archived = entry.name().endsWith(".old");
      ModUpdate update = null;
      if (checkUpdates && !archived && sha1 != null) {
        try {
          Optional<ModUpdate> suggested = modrinthClient.findSuggestedUpdate(sha1);
          update = suggested.orElse(null);
        } catch (IOException e) {
          log.warn("Modrinth lookup failed for {}", entry.path(), e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while checking Modrinth", e);
        }
      }
      ModFile modFile = new ModFile(
          folder.getId(),
          folder.getPath(),
          entry.path(),
          entry.name(),
          entry.size(),
          archived,
          sha1,
          update);
      mods.add(modFile);
      if (onMod != null) {
        onMod.accept(modFile);
      }
    }
    log.info("Scan complete for {}: {} mods", profile.getName(), mods.size());
    return mods;
  }

  private record Candidate(ModFolder folder, FileEntry entry) {
  }

  public void upload(ServerProfile profile, ModFolder folder, String fileName, byte[] bytes)
      throws IOException {
    log.info("Uploading {} to {}:{}", fileName, profile.getName(), folder.getPath());
    ServerFileSystem fileSystem = connectionService.fileSystem(profile);
    String relativePath = joinPath(folder.getPath(), fileName);
    fileSystem.writeBytes(relativePath, bytes);
    cacheWrittenFile(profile, fileSystem, relativePath, digest("SHA-1", bytes));
  }

  public void delete(ServerProfile profile, ModFile modFile) throws IOException {
    log.info("Deleting {} from {}", modFile.relativePath(), profile.getName());
    connectionService.fileSystem(profile).delete(modFile.relativePath());
    modHashCacheStore.remove(profile, modFile.relativePath());
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
    try {
      byte[] bytes = modrinthClient.download(update.downloadUrl());
      String archivedName = nextArchiveName(modFile.relativePath());
      fileSystem.move(modFile.relativePath(), archivedName);
      modHashCacheStore.move(profile, modFile.relativePath(), archivedName);
      if (modFile.sha1() != null && !modFile.sha1().isBlank()) {
        cacheWrittenFile(profile, fileSystem, archivedName, modFile.sha1());
      }
      String updatedPath = joinPath(modFile.folderPath(), update.fileName());
      fileSystem.writeBytes(updatedPath, bytes);
      cacheWrittenFile(profile, fileSystem, updatedPath, digest("SHA-1", bytes));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while downloading mod update", e);
    }
  }

  static String nextArchiveName(String relativePath) {
    return relativePath.endsWith(".old") ? relativePath : relativePath + ".old";
  }

  private String resolveSha1(ServerProfile profile, ServerFileSystem fileSystem, FileEntry entry)
      throws IOException {
    Optional<String> cached = modHashCacheStore.lookup(profile, entry);
    if (cached.isPresent()) {
      log.info("Using cached hash for mod {}", entry.path());
      return cached.get();
    }
    log.info("Hashing mod {}", entry.path());
    byte[] bytes = fileSystem.readBytes(entry.path());
    String sha1 = digest("SHA-1", bytes);
    modHashCacheStore.put(profile, entry, sha1);
    log.info("Finished hashing mod {}", entry.path());
    return sha1;
  }

  private void cacheWrittenFile(
      ServerProfile profile, ServerFileSystem fileSystem, String relativePath, String sha1)
      throws IOException {
    findEntry(fileSystem, relativePath)
        .ifPresent(entry -> modHashCacheStore.put(profile, entry, sha1));
  }

  private Optional<FileEntry> findEntry(ServerFileSystem fileSystem, String relativePath)
      throws IOException {
    String folderPath = parentPath(relativePath);
    return fileSystem.list(folderPath).stream()
        .filter(entry -> relativePath.equals(entry.path()))
        .findFirst();
  }

  private static String joinPath(String folderPath, String fileName) {
    if (folderPath == null || folderPath.isBlank()) {
      return fileName;
    }
    return folderPath.endsWith("/") ? folderPath + fileName : folderPath + "/" + fileName;
  }

  private static String parentPath(String relativePath) {
    int slash = relativePath.lastIndexOf('/');
    if (slash < 0) {
      return "";
    }
    return relativePath.substring(0, slash);
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
