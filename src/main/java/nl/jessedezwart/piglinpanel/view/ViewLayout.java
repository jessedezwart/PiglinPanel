package nl.jessedezwart.piglinpanel.view;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import jfx.incubator.scene.control.richtext.CodeArea;

final class ViewLayout {
  private ViewLayout() {}

  static CodeArea editor(boolean editable) {
    CodeArea area = new CodeArea();
    area.setWrapText(true);
    area.setLineNumbersEnabled(true);
    area.setEditable(editable);
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
