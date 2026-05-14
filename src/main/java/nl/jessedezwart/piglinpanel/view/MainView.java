package nl.jessedezwart.piglinpanel.view;

import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.viewmodel.AppViewModel;
import org.springframework.stereotype.Component;

@Component
public class MainView {
  private final AppViewModel state;
  private final OverviewView overviewView;
  private final ModsView modsView;
  private final LogsView logsView;
  private final FilesView filesView;
  private final RconView rconView;
  private BorderPane root;
  private ComboBox<ServerProfile> serverSelector;
  private String currentView = "Overview";

  public MainView(
      AppViewModel state,
      OverviewView overviewView,
      ModsView modsView,
      LogsView logsView,
      FilesView filesView,
      RconView rconView) {
    this.state = state;
    this.overviewView = overviewView;
    this.modsView = modsView;
    this.logsView = logsView;
    this.filesView = filesView;
    this.rconView = rconView;
  }

  public Parent create() {
    root = new BorderPane();
    serverSelector = new ComboBox<>();
    root.setTop(topBar());
    root.setLeft(sidebar());
    state.selectedServerProperty().addListener((ignored, oldValue, newValue) -> show(currentView));
    show("Overview");
    return root;
  }

  private Node topBar() {
    serverSelector.setItems(state.servers());
    serverSelector.valueProperty().bindBidirectional(state.selectedServerProperty());
    Button edit = new Button("Edit");
    edit.setOnAction(
        event -> {
          ServerProfile selected = state.selectedServer();
          if (selected != null) {
            new ServerDialog(selected).showAndWait().ifPresent(state::save);
          }
        });
    Button add = new Button("+");
    add.setOnAction(event -> new ServerDialog(null).showAndWait().ifPresent(state::save));
    HBox bar = new HBox(8, new Label("Piglinpanel"), spacer(), serverSelector, edit, add);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.setPadding(new Insets(10));
    bar.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #dddddd;");
    return bar;
  }

  private Node sidebar() {
    VBox box = new VBox(6);
    box.setPadding(new Insets(10));
    box.setPrefWidth(150);
    for (String item : List.of("Overview", "Mods", "Logs", "Files", "RCON")) {
      Button button = new Button(item);
      button.setMaxWidth(Double.MAX_VALUE);
      button.setOnAction(event -> show(item));
      if (item.equals("RCON")) {
        button
            .disableProperty()
            .bind(
                state
                    .selectedServerProperty()
                    .map(server -> server == null || !server.getRcon().isEnabled()));
      }
      box.getChildren().add(button);
    }
    return box;
  }

  private void show(String name) {
    currentView = name;
    ServerProfile selected = state.selectedServer();
    if (selected == null) {
      root.setCenter(emptyState());
      return;
    }
    root.setCenter(
        switch (name) {
          case "Mods" -> modsView.create(profileContext(selected));
          case "Logs" -> logsView.create(selected, () -> "Logs".equals(currentView));
          case "Files" -> filesView.create(selected);
          case "RCON" -> rconView.create(selected);
          default -> overviewView.create(selected);
        });
  }

  private ModsView.Context profileContext(ServerProfile selected) {
    return new ModsView.Context(selected, root);
  }

  private Node emptyState() {
    Button add = new Button("Add server");
    add.setOnAction(event -> new ServerDialog(null).showAndWait().ifPresent(state::save));
    VBox box = new VBox(12, new Label("No servers configured"), add);
    box.setAlignment(Pos.CENTER);
    return box;
  }

  private Node spacer() {
    return ViewLayout.spacer();
  }
}
