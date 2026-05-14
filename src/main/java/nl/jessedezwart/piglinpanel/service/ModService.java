package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import nl.jessedezwart.piglinpanel.client.ModrinthClient;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ModFile;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.stereotype.Service;

@Service
public class ModService {
  private final ServerConnectionService connectionService;
  private final ModrinthClient modrinthClient;

  public ModService(ServerConnectionService connectionService, ModrinthClient modrinthClient) {
    this.connectionService = connectionService;
    this.modrinthClient = modrinthClient;
  }

  public List<ModFile> scan(ServerProfile profile, boolean checkUpdates) throws IOException {
    List<ModFile> mods = new ArrayList<>();
    for (ModFolder folder : profile.getModFolders()) {
      for (FileEntry entry : connectionService.fileSystem(profile).list(folder.getPath())) {
        if (entry.directory()
            || (!entry.name().endsWith(".jar") && !entry.name().endsWith(".jar.old"))) {
          continue;
        }
        byte[] bytes = connectionService.fileSystem(profile).readBytes(entry.path());
        String sha1 = digest("SHA-1", bytes);
        String sha512 = digest("SHA-512", bytes);
        boolean archived = entry.name().endsWith(".old");
        ModUpdate update = null;
        if (checkUpdates && !archived) {
          try {
            Optional<ModUpdate> suggested = modrinthClient.findSuggestedUpdate(sha1);
            update = suggested.filter(ModUpdate::available).orElse(null);
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
      }
    }
    return mods;
  }

  public void upload(ServerProfile profile, ModFolder folder, String fileName, byte[] bytes)
      throws IOException {
    connectionService.fileSystem(profile).writeBytes(folder.getPath() + "/" + fileName, bytes);
  }

  public void delete(ServerProfile profile, ModFile modFile) throws IOException {
    connectionService.fileSystem(profile).delete(modFile.relativePath());
  }

  public void applyUpdate(ServerProfile profile, ModFile modFile) throws IOException {
    ModUpdate update = modFile.update();
    if (update == null || !update.available()) {
      throw new IOException("No update is available for " + modFile.fileName());
    }
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
