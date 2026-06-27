package nl.jessedezwart.piglinpanel.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import nl.jessedezwart.piglinpanel.filesystem.ServerFileSystem;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ModHashService {
  private static final Logger log = LoggerFactory.getLogger(ModHashService.class);

  @Cacheable(
      value = "mod-hashes",
      key =
          "#profile.id + ':' + #entry.path() + ':' + #entry.size()"
              + " + ':' + #entry.modifiedAt().toEpochMilli()")
  public String resolveSha1(ServerProfile profile, ServerFileSystem fileSystem, FileEntry entry)
      throws IOException {
    log.info("Hashing mod {}", entry.path());
    byte[] bytes = fileSystem.readBytes(entry.path());
    String sha1 = digest(bytes);
    log.info("Finished hashing mod {}", entry.path());
    return sha1;
  }

  private static String digest(byte[] bytes) throws IOException {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-1 is unavailable", e);
    }
  }
}
