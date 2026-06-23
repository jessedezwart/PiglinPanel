package nl.jessedezwart.piglinpanel.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;
import net.querz.nbt.io.NBTInputStream;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.io.SNBTUtil;
import net.querz.nbt.tag.Tag;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class FileService {
  private final ServerConnectionService connectionService;

  public FileService(ServerConnectionService connectionService) {
    this.connectionService = connectionService;
  }

  @Cacheable(value = "file-listings", key = "#profile.id + ':' + #relativePath")
  public List<FileEntry> list(ServerProfile profile, String relativePath) throws IOException {
    return connectionService.fileSystem(profile).list(relativePath);
  }

  public String readText(ServerProfile profile, String relativePath) throws IOException {
    byte[] bytes = connectionService.fileSystem(profile).readBytes(relativePath);
    if (relativePath != null && relativePath.toLowerCase().endsWith(".gz")) {
      bytes = decompressGzip(bytes);
    }
    if (relativePath != null && isNbtExtension(relativePath)) {
      return readNbt(bytes);
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  public byte[] readBytes(ServerProfile profile, String relativePath) throws IOException {
    return connectionService.fileSystem(profile).readBytes(relativePath);
  }

  public static boolean isNbtExtension(String path) {
    if (path == null) {
      return false;
    }
    String lower = path.toLowerCase();
    return lower.endsWith(".nbt") || lower.endsWith(".dat") || lower.endsWith(".dat_old") || lower.endsWith(".mca");
  }

  private String readNbt(byte[] bytes) throws IOException {
    byte[] raw;
    try {
      raw = decompressGzip(bytes);
    } catch (IOException e) {
      raw = bytes;
    }
    try (NBTInputStream stream = new NBTInputStream(new ByteArrayInputStream(raw))) {
      NamedTag namedTag = stream.readTag(Tag.DEFAULT_MAX_DEPTH);
      return SNBTUtil.toSNBT(namedTag.getTag());
    }
  }

  private static byte[] decompressGzip(byte[] bytes) throws IOException {
    try (var input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
      return input.readAllBytes();
    }
  }

  public void saveText(ServerProfile profile, String relativePath, String text) throws IOException {
    connectionService
        .fileSystem(profile)
        .writeBytes(relativePath, text.getBytes(StandardCharsets.UTF_8));
  }
}
