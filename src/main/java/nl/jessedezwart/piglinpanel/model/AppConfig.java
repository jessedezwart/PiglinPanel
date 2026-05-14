package nl.jessedezwart.piglinpanel.model;

import java.util.ArrayList;
import java.util.List;

public class AppConfig {
  private List<ServerProfile> servers = new ArrayList<>();
  private String selectedServerId;

  public List<ServerProfile> getServers() {
    return servers;
  }

  public void setServers(List<ServerProfile> servers) {
    this.servers = servers == null ? new ArrayList<>() : servers;
  }

  public String getSelectedServerId() {
    return selectedServerId;
  }

  public void setSelectedServerId(String selectedServerId) {
    this.selectedServerId = selectedServerId;
  }
}
