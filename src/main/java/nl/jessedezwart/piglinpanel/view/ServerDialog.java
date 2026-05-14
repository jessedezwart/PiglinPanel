package nl.jessedezwart.piglinpanel.view;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import nl.jessedezwart.piglinpanel.model.AccessType;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.ServerProfile;

public class ServerDialog extends Dialog<ServerProfile> {
  public ServerDialog(ServerProfile existing) {
    ServerProfile source = existing == null ? new ServerProfile() : existing;
    setTitle(existing == null ? "Add server" : "Edit server");
    getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    getDialogPane().setContent(content(source));
    setResultConverter(button -> button == ButtonType.OK ? read(source) : null);
  }

  private final TextField name = new TextField();
  private final ComboBox<AccessType> accessType = new ComboBox<>();
  private final TextField rootPath = new TextField();
  private final TextField logsPath = new TextField();
  private final TextArea modFolders = new TextArea();
  private final TextField sshHost = new TextField();
  private final TextField sshPort = new TextField();
  private final TextField sshUsername = new TextField();
  private final PasswordField sshPassword = new PasswordField();
  private final TextField sshPrivateKey = new TextField();
  private final PasswordField sshPassphrase = new PasswordField();

  private Node content(ServerProfile profile) {
    name.setText(profile.getName());
    accessType.getItems().setAll(AccessType.values());
    accessType.setValue(profile.getAccessType());
    rootPath.setText(profile.getRootPath());
    logsPath.setText(profile.getLogsPath());
    modFolders.setText(formatModFolders(profile.getModFolders()));
    sshHost.setText(profile.getSsh().getHost());
    sshPort.setText(Integer.toString(profile.getSsh().getPort()));
    sshUsername.setText(profile.getSsh().getUsername());
    sshPassword.setText(profile.getSsh().getPassword());
    sshPrivateKey.setText(profile.getSsh().getPrivateKeyPath());
    sshPassphrase.setText(profile.getSsh().getPrivateKeyPassphrase());

    GridPane grid = new GridPane();
    grid.setHgap(10);
    grid.setVgap(8);
    grid.setPadding(new Insets(14));
    int row = 0;
    row = add(grid, row, "Name", name);
    row = add(grid, row, "Access", accessType);
    row = add(grid, row, "Root path", rootPath);
    row = add(grid, row, "Logs path", logsPath);
    modFolders.setPrefRowCount(4);
    row = add(grid, row, "Mod folders", modFolders);
    row = label(grid, row, "Mod folder format: Name=relative/path, one per line");
    row = label(grid, row, "SSH");
    row = add(grid, row, "Host", sshHost);
    row = add(grid, row, "Port", sshPort);
    row = add(grid, row, "Username", sshUsername);
    row = add(grid, row, "Password", sshPassword);
    row = add(grid, row, "Private key", sshPrivateKey);
    row = add(grid, row, "Key passphrase", sshPassphrase);
    return grid;
  }

  private ServerProfile read(ServerProfile profile) {
    profile.setId(profile.getId() == null ? UUID.randomUUID().toString() : profile.getId());
    profile.setName(name.getText());
    profile.setAccessType(accessType.getValue());
    profile.setRootPath(rootPath.getText());
    profile.setLogsPath(logsPath.getText());
    profile.setModFolders(parseModFolders(modFolders.getText()));
    profile.getSsh().setHost(sshHost.getText());
    profile.getSsh().setPort(parseInt(sshPort.getText(), 22));
    profile.getSsh().setUsername(sshUsername.getText());
    profile.getSsh().setPassword(sshPassword.getText());
    profile.getSsh().setPrivateKeyPath(sshPrivateKey.getText());
    profile.getSsh().setPrivateKeyPassphrase(sshPassphrase.getText());
    return profile;
  }

  private static int add(GridPane grid, int row, String label, Node node) {
    grid.add(new Label(label), 0, row);
    grid.add(node, 1, row);
    return row + 1;
  }

  private static int label(GridPane grid, int row, String label) {
    Label node = new Label(label);
    node.setStyle("-fx-font-weight: bold;");
    grid.add(node, 0, row, 2, 1);
    return row + 1;
  }

  private static String formatModFolders(List<ModFolder> folders) {
    return folders.stream()
        .map(folder -> folder.getName() + "=" + folder.getPath())
        .reduce((a, b) -> a + "\n" + b)
        .orElse("Server mods=mods");
  }

  private static List<ModFolder> parseModFolders(String text) {
    List<ModFolder> folders = new ArrayList<>();
    text.lines()
        .filter(line -> !line.isBlank())
        .forEach(
            line -> {
              String[] parts = line.split("=", 2);
              String name = parts.length == 2 ? parts[0].trim() : parts[0].trim();
              String path = parts.length == 2 ? parts[1].trim() : parts[0].trim();
              folders.add(new ModFolder(UUID.randomUUID().toString(), name, path));
            });
    return folders.isEmpty()
        ? List.of(new ModFolder("server-mods", "Server mods", "mods"))
        : folders;
  }

  private static int parseInt(String value, int fallback) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
