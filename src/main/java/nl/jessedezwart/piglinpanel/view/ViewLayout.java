package nl.jessedezwart.piglinpanel.view;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class ViewLayout {
  private ViewLayout() {}

  static TextArea editor(boolean editable) {
    TextArea area = new TextArea();
    area.setWrapText(true);
    area.setEditable(editable);
    area.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace;");
    return area;
  }

  static VBox contentBox(Node... children) {
    VBox box = new VBox(10, children);
    box.setPadding(new Insets(12));
    return box;
  }

  static Label title(String text) {
    Label label = new Label(text);
    label.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
    return label;
  }

  static Node spacer() {
    HBox spacer = new HBox();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    return spacer;
  }
}
