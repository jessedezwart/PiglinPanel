package nl.jessedezwart.piglinpanel.service;

import java.util.List;
import java.util.Optional;
import nl.jessedezwart.piglinpanel.config.AppConfigStore;
import nl.jessedezwart.piglinpanel.model.AppConfig;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.stereotype.Service;

@Service
public class ServerRegistryService {
  private final AppConfigStore store;
  private final RconConfigResolver rconConfigResolver;
  private AppConfig config;

  public ServerRegistryService(AppConfigStore store, RconConfigResolver rconConfigResolver) {
    this.store = store;
    this.rconConfigResolver = rconConfigResolver;
    this.config = store.load();
    if (refreshRcon(selected().orElse(null))) {
      store.save(config);
    }
  }

  public List<ServerProfile> servers() {
    return config.getServers();
  }

  public Optional<ServerProfile> selected() {
    String selectedId = config.getSelectedServerId();
    if (selectedId != null) {
      return config.getServers().stream()
          .filter(server -> selectedId.equals(server.getId()))
          .findFirst();
    }
    return config.getServers().stream().findFirst();
  }

  public void select(ServerProfile profile) {
    refreshRcon(profile);
    config.setSelectedServerId(profile == null ? null : profile.getId());
    store.save(config);
  }

  public void save(ServerProfile profile) {
    if (profile.getId() == null || profile.getId().isBlank()) {
      throw new IllegalArgumentException("Server id is required");
    }
    refreshRcon(profile);
    for (int i = 0; i < config.getServers().size(); i++) {
      if (profile.getId().equals(config.getServers().get(i).getId())) {
        config.getServers().set(i, profile);
        store.save(config);
        return;
      }
    }
    config.getServers().add(profile);
    if (config.getSelectedServerId() == null) {
      config.setSelectedServerId(profile.getId());
    }
    store.save(config);
  }

  private boolean refreshRcon(ServerProfile profile) {
    if (profile != null) {
      return rconConfigResolver.refresh(profile);
    }
    return false;
  }
}
