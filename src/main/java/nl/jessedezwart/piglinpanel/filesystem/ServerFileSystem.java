package nl.jessedezwart.piglinpanel.filesystem;

import java.io.IOException;
import java.util.List;
import nl.jessedezwart.piglinpanel.model.FileEntry;

public interface ServerFileSystem {
  List<FileEntry> list(String relativePath) throws IOException;

  byte[] readBytes(String relativePath) throws IOException;

  void writeBytes(String relativePath, byte[] bytes) throws IOException;

  void delete(String relativePath) throws IOException;

  void move(String sourceRelativePath, String targetRelativePath) throws IOException;
}
