package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import nl.jessedezwart.piglinpanel.client.ModrinthClient;
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

  public ModService(ServerConnectionService connectionService, ModrinthClient modrinthClient) {
    this.connectionService = connectionService;
    this.modrinthClient = modrinthClient;
  }

  public List<ModFile> scan(ServerProfile profile, boolean checkUpdates) throws IOException {
    return scan(profile, checkUpdates, (done, total) -> {
    });
  }

  public List<ModFile> scan(
      ServerProfile profile, boolean checkUpdates, BiConsumer<Integer, Integer> progress)
      throws IOException {
    log.info("Scanning mods for {} (checkUpdates={})", profile.getName(), checkUpdates);
    List<Candidate> candidates = new ArrayList<>();
    for (ModFolder folder : profile.getModFolders()) {
      for (FileEntry entry : connectionService.fileSystem(profile).list(folder.getPath())) {
        if (entry.directory()
            || (!entry.name().endsWith(".jar") && !entry.name().endsWith(".jar.old"))) {
          continue;
        }
        candidates.add(new Candidate(folder, entry));
      }
    }
    int total = candidates.size();
    int done = 0;
    progress.accept(done, total);
    List<ModFile> mods = new ArrayList<>(total);
    for (Candidate candidate : candidates) {
      ModFolder folder = candidate.folder();
      FileEntry entry = candidate.entry();
      String sha1 = null;
      String sha512 = null;
      if (checkUpdates) {
        try {
          log.info("Hashing mod {}", entry.path());
          byte[] bytes = connectionService.fileSystem(profile).readBytes(entry.path());
          sha1 = digest("SHA-1", bytes);
          sha512 = digest("SHA-512", bytes);
          log.info("Finished hashing mod {}", entry.path());
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
      mods.add(
          new ModFile(
              folder.getId(),
              folder.getPath(),
              entry.path(),
              entry.name(),
              entry.size(),
              archived,
              sha1,
              sha512,
              update));
      done += 1;
      progress.accept(done, total);
    }
    log.info("Scan complete for {}: {} mods", profile.getName(), mods.size());
    return mods;
  }

  private record Candidate(ModFolder folder, FileEntry entry) {
  }

  public void upload(ServerProfile profile, ModFolder folder, String fileName, byte[] bytes)
      throws IOException {
    log.info("Uploading {} to {}:{}", fileName, profile.getName(), folder.getPath());
    connectionService.fileSystem(profile).writeBytes(folder.getPath() + "/" + fileName, bytes);
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
    try {
      byte[] bytes = modrinthClient.download(update.downloadUrl());
      String archivedName = nextArchiveName(modFile.relativePath());
      connectionService.fileSystem(profile).move(modFile.relativePath(), archivedName);
      connectionService
          .fileSystem(profile)
          .writeBytes(modFile.folderPath() + "/" + update.fileName(), bytes);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while downloading mod update", e);
    }
  }

  static String nextArchiveName(String relativePath) {
    return relativePath.endsWith(".old") ? relativePath : relativePath + ".old";
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
