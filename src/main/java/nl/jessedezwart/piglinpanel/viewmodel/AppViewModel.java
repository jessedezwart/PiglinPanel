package nl.jessedezwart.piglinpanel.viewmodel;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import org.springframework.stereotype.Component;

@Component
public class AppViewModel {
  private final ServerRegistryService registryService;
  private final ObservableList<ServerProfile> servers = FXCollections.observableArrayList();
  private final ObjectProperty<ServerProfile> selectedServer = new SimpleObjectProperty<>();

  public AppViewModel(ServerRegistryService registryService) {
    this.registryService = registryService;
    reload();
    selectedServer.addListener(
        (ignored, oldValue, newValue) -> {
          if (newValue != null) {
            registryService.select(newValue);
          }
        });
  }

  public ObservableList<ServerProfile> servers() {
    return servers;
  }

  public ObjectProperty<ServerProfile> selectedServerProperty() {
    return selectedServer;
  }

  public ServerProfile selectedServer() {
    return selectedServer.get();
  }

  public void save(ServerProfile profile) {
    registryService.save(profile);
    reload();
    selectedServer.set(
        servers.stream()
            .filter(server -> server.getId().equals(profile.getId()))
            .findFirst()
            .orElse(null));
  }

  public void reload() {
    servers.setAll(registryService.servers());
    selectedServer.set(
        registryService.selected().orElse(servers.isEmpty() ? null : servers.getFirst()));
  }
}
