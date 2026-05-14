package nl.jessedezwart.piglinpanel.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ServerProfile;

public class LocalServerFileSystem implements ServerFileSystem {
  private final Path root;

  public LocalServerFileSystem(ServerProfile profile) {
    this.root = Path.of(profile.getRootPath()).toAbsolutePath().normalize();
  }

  @Override
  public List<FileEntry> list(String relativePath) throws IOException {
    Path path = resolve(relativePath);
    if (!Files.exists(path)) {
      return List.of();
    }
    try (var stream = Files.list(path)) {
      return stream
          .sorted(
              Comparator.<Path, Boolean>comparing(Files::isRegularFile)
                  .thenComparing(item -> item.getFileName().toString()))
          .map(this::entry)
          .toList();
    }
  }

  @Override
  public byte[] readBytes(String relativePath) throws IOException {
    return Files.readAllBytes(resolve(relativePath));
  }

  @Override
  public void writeBytes(String relativePath, byte[] bytes) throws IOException {
    Path path = resolve(relativePath);
    Files.createDirectories(path.getParent());
    Files.write(path, bytes);
  }

  @Override
  public void delete(String relativePath) throws IOException {
    Files.deleteIfExists(resolve(relativePath));
  }

  @Override
  public void move(String sourceRelativePath, String targetRelativePath) throws IOException {
    Path target = resolve(targetRelativePath);
    Files.createDirectories(target.getParent());
    Files.move(resolve(sourceRelativePath), target, StandardCopyOption.REPLACE_EXISTING);
  }

  private FileEntry entry(Path path) {
    try {
      Path relative = root.relativize(path.toAbsolutePath().normalize());
      return new FileEntry(
          relative.toString().replace('\\', '/'),
          path.getFileName().toString(),
          Files.isDirectory(path),
          Files.isDirectory(path) ? 0 : Files.size(path),
          Files.getLastModifiedTime(path).toInstant());
    } catch (IOException e) {
      return new FileEntry(
          path.getFileName().toString(),
          path.getFileName().toString(),
          Files.isDirectory(path),
          0,
          Instant.EPOCH);
    }
  }

  private Path resolve(String relativePath) throws IOException {
    String clean = relativePath == null ? "" : relativePath;
    Path resolved = root.resolve(clean).toAbsolutePath().normalize();
    if (!resolved.startsWith(root)) {
      throw new IOException("Path escapes server root: " + relativePath);
    }
    return resolved;
  }
}
