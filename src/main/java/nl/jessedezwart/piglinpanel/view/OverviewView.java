package nl.jessedezwart.piglinpanel.view;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.stereotype.Component;

@Component
class OverviewView {
  Node create(ServerProfile profile) {
    VBox box = ViewLayout.contentBox();
    box.getChildren()
        .addAll(
            ViewLayout.title("Overview"),
            new Label("Name: " + profile.getName()),
            new Label("Access: " + profile.getAccessType()),
            new Label("Root: " + profile.getRootPath()),
            new Label("Logs: " + profile.getLogsPath()),
            new Label(
                "Mod folders: "
                    + profile.getModFolders().stream()
                        .map(ModFolder::toString)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("")),
            new Label("RCON: " + (profile.getRcon().isEnabled() ? "enabled" : "disabled")));
    return box;
  }
}
