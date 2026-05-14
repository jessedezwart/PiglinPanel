package nl.jessedezwart.piglinpanel.view;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.RconService;
import org.springframework.stereotype.Component;

@Component
class RconView {
  private final RconService rconService;

  RconView(RconService rconService) {
    this.rconService = rconService;
  }

  Node create(ServerProfile profile) {
    TextArea output = ViewLayout.editor(false);
    TextField command = new TextField();
    command.setPromptText("Command");
    Button send = new Button("Send");
    send.setOnAction(
        event -> {
          String value = command.getText();
          UiTasks.run(
              () -> rconService.execute(profile, value),
              response -> {
                output.setText(output.getText() + "> " + value + "\n" + response + "\n");
                command.clear();
              });
        });
    VBox box = ViewLayout.contentBox(new HBox(8, command, send), output);
    HBox.setHgrow(command, Priority.ALWAYS);
    VBox.setVgrow(output, Priority.ALWAYS);
    return box;
  }
}
