package nl.jessedezwart.piglinpanel.filesystem;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import nl.jessedezwart.piglinpanel.client.SshClient;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ServerProfile;

public class SshServerFileSystem implements ServerFileSystem {
  private final ServerProfile profile;
  private final String root;

  public SshServerFileSystem(ServerProfile profile) {
    this.profile = profile;
    this.root = normalizeRemote(profile.getRootPath());
  }

  @Override
  public List<FileEntry> list(String relativePath) throws IOException {
    String remote = scoped(relativePath);
    String command =
        "find " + quote(remote) + " -maxdepth 1 -mindepth 1 -printf '%P\\t%y\\t%s\\t%T@\\n' | sort";
    try (SshClient client = new SshClient(profile)) {
      var result = client.exec(command);
      result.throwIfFailed();
      List<FileEntry> entries = new ArrayList<>();
      for (String line : result.stdout().split("\\R")) {
        if (line.isBlank()) {
          continue;
        }
        String[] parts = line.split("\\t", 4);
        if (parts.length < 4) {
          continue;
        }
        String childPath = join(relativePath, parts[0]);
        boolean directory = "d".equals(parts[1]);
        long size = parseLong(parts[2]);
        Instant modified = Instant.ofEpochMilli((long) (Double.parseDouble(parts[3]) * 1000));
        entries.add(new FileEntry(childPath, parts[0], directory, directory ? 0 : size, modified));
      }
      return entries;
    }
  }

  @Override
  public byte[] readBytes(String relativePath) throws IOException {
    try (SshClient client = new SshClient(profile)) {
      var result = client.execRaw("cat " + quote(scoped(relativePath)));
      result.throwIfFailed();
      return result.stdout().toByteArray();
    }
  }

  @Override
  public void writeBytes(String relativePath, byte[] bytes) throws IOException {
    String target = scoped(relativePath);
    String tmp = target + ".piglinpanel-tmp";
    String command =
        "mkdir -p "
            + quote(parent(target))
            + " && cat > "
            + quote(tmp)
            + " && mv "
            + quote(tmp)
            + " "
            + quote(target);
    try (SshClient client = new SshClient(profile)) {
      client.writeToCommand(command, bytes);
    }
  }

  @Override
  public void delete(String relativePath) throws IOException {
    try (SshClient client = new SshClient(profile)) {
      var result = client.exec("rm -f -- " + quote(scoped(relativePath)));
      result.throwIfFailed();
    }
  }

  @Override
  public void move(String sourceRelativePath, String targetRelativePath) throws IOException {
    String target = scoped(targetRelativePath);
    String command =
        "mkdir -p "
            + quote(parent(target))
            + " && mv -f -- "
            + quote(scoped(sourceRelativePath))
            + " "
            + quote(target);
    try (SshClient client = new SshClient(profile)) {
      var result = client.exec(command);
      result.throwIfFailed();
    }
  }

  private String scoped(String relativePath) throws IOException {
    String clean = relativePath == null ? "" : relativePath.replace('\\', '/');
    if (clean.startsWith("/") || clean.contains("..")) {
      throw new IOException("Path escapes server root: " + relativePath);
    }
    return clean.isBlank() ? root : root + "/" + clean;
  }

  private static String normalizeRemote(String path) {
    String clean = path == null || path.isBlank() ? "." : path.replace('\\', '/');
    while (clean.endsWith("/") && clean.length() > 1) {
      clean = clean.substring(0, clean.length() - 1);
    }
    return clean;
  }

  private static String parent(String path) {
    int slash = path.lastIndexOf('/');
    return slash <= 0 ? "." : path.substring(0, slash);
  }

  private static String join(String first, String second) {
    if (first == null || first.isBlank()) {
      return second;
    }
    return first.endsWith("/") ? first + second : first + "/" + second;
  }

  private static long parseLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }
}
