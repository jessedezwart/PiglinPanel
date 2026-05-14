package nl.jessedezwart.piglinpanel.model;

public class ModFolder {
  private String id;
  private String name;
  private String path;

  public ModFolder() {}

  public ModFolder(String id, String name, String path) {
    this.id = id;
    this.name = name;
    this.path = path;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  @Override
  public String toString() {
    return name == null || name.isBlank() ? path : name;
  }
}
