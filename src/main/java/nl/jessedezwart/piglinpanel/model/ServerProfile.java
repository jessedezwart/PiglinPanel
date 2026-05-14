package nl.jessedezwart.piglinpanel.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ServerProfile {
  private String id = UUID.randomUUID().toString();
  private String name = "Minecraft Server";
  private AccessType accessType = AccessType.LOCAL;
  private String rootPath = "";
  private String logsPath = "logs";
  private String serverPropertiesPath = "server.properties";
  private List<String> configFolders = new ArrayList<>(List.of("config"));
  private List<ModFolder> modFolders =
      new ArrayList<>(List.of(new ModFolder("server-mods", "Server mods", "mods")));
  private SshConfig ssh = new SshConfig();
  private RconConfig rcon = new RconConfig();

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

  public AccessType getAccessType() {
    return accessType;
  }

  public void setAccessType(AccessType accessType) {
    this.accessType = accessType;
  }

  public String getRootPath() {
    return rootPath;
  }

  public void setRootPath(String rootPath) {
    this.rootPath = rootPath;
  }

  public String getLogsPath() {
    return logsPath;
  }

  public void setLogsPath(String logsPath) {
    this.logsPath = logsPath;
  }

  public String getServerPropertiesPath() {
    return serverPropertiesPath;
  }

  public void setServerPropertiesPath(String serverPropertiesPath) {
    this.serverPropertiesPath = serverPropertiesPath;
  }

  public List<String> getConfigFolders() {
    return configFolders;
  }

  public void setConfigFolders(List<String> configFolders) {
    this.configFolders = configFolders == null ? new ArrayList<>() : configFolders;
  }

  public List<ModFolder> getModFolders() {
    return modFolders;
  }

  public void setModFolders(List<ModFolder> modFolders) {
    this.modFolders = modFolders == null ? new ArrayList<>() : modFolders;
  }

  public SshConfig getSsh() {
    return ssh;
  }

  public void setSsh(SshConfig ssh) {
    this.ssh = ssh == null ? new SshConfig() : ssh;
  }

  public RconConfig getRcon() {
    return rcon;
  }

  public void setRcon(RconConfig rcon) {
    this.rcon = rcon == null ? new RconConfig() : rcon;
  }

  @Override
  public String toString() {
    return name;
  }
}
