package net.darmo_creations.imageslibrary.ui;

import javafx.beans.binding.*;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.util.*;
import net.darmo_creations.imageslibrary.*;
import net.darmo_creations.imageslibrary.config.*;
import net.darmo_creations.imageslibrary.data.*;
import net.darmo_creations.imageslibrary.themes.*;
import net.darmo_creations.imageslibrary.utils.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.nio.file.*;

public class ImagePreviewPane extends VBox {
  private final Button openInExplorerButton = new Button();
  private final Label fileNameLabel = new Label();
  private final Label fileMetadataLabel = new Label();
  private final ImageView imageView = new ImageView();
  @Nullable
  private Picture picture;

  public ImagePreviewPane() {
    super(5);
    this.setMinWidth(300);
    this.setPadding(new Insets(2, 0, 0, 0));

    final Config config = App.config();
    final Language language = config.language();
    final Theme theme = config.theme();

    this.openInExplorerButton.setText(language.translate("image_preview.open_in_explorer_button.label"));
    this.openInExplorerButton.setGraphic(theme.getIcon(Icon.OPEN_FILE_IN_EXPLORER, Icon.Size.SMALL));
    this.openInExplorerButton.setOnAction(e -> this.onOpenFile());
    final HBox controlsBox = new HBox(5, this.openInExplorerButton);
    controlsBox.setAlignment(Pos.CENTER);

    final HBox fileNameBox = new HBox(this.fileNameLabel);
    fileNameBox.setAlignment(Pos.CENTER);

    final HBox metadataBox = new HBox(this.fileMetadataLabel);
    metadataBox.setAlignment(Pos.CENTER);

    final HBox imageViewBox = new HBox(this.imageView);
    imageViewBox.setAlignment(Pos.TOP_CENTER);
    this.imageView.setPreserveRatio(true);
    this.imageView.fitHeightProperty().bind(imageViewBox.heightProperty().subtract(50));
    VBox.setVgrow(imageViewBox, Priority.ALWAYS);

    this.getChildren().addAll(controlsBox, fileNameBox, metadataBox, imageViewBox);
    this.setImage(null);
  }

  /**
   * Set the splitpane this component is in.
   *
   * @param splitPane The splitpane ancestor.
   * @param left      Whether this component is to the left (true) or right (false) of the splitpane’s divider.
   */
  @SuppressWarnings("SameParameterValue")
  public void setSplitPane(SplitPane splitPane, boolean left) {
    // Cf. https://stackoverflow.com/a/47226681/3779986
    DoubleExpression posProp = splitPane.getDividers().get(0).positionProperty();
    if (!left) // Compute 1 - pos
      posProp = posProp.negate().add(1);
    this.imageView.fitWidthProperty().bind(posProp.multiply(splitPane.widthProperty()).subtract(10));
  }

  /**
   * Set the image to show.
   *
   * @param picture The image to show.
   */
  public void setImage(@Nullable Picture picture) {
    this.picture = picture;
    boolean noPicture = true;
    this.imageView.setImage(null);
    this.fileNameLabel.setText(null);
    this.fileMetadataLabel.setText(null);
    this.fileMetadataLabel.setTooltip(null);
    if (picture != null) {
      final Language language = App.config().language();
      final Path path = picture.path();
      this.fileNameLabel.setText(path.getFileName().toString());
      this.fileMetadataLabel.setText(language.translate("image_preview.loading"));
      if (!Files.exists(path)) {
        this.fileMetadataLabel.setText(language.translate("image_preview.missing_file"));
      } else {
        final Image image = new Image("file://" + path, true);
        image.progressProperty().addListener((observable, oldValue, newValue) -> {
          if (newValue.doubleValue() >= 1) // Image is done loading, lookup its metadata
            this.displayMetadata(path, image);
        });
        this.imageView.setImage(image);
        noPicture = false;
      }
    }
    this.openInExplorerButton.setDisable(noPicture);
  }

  private void displayMetadata(Path path, Image image) {
    long size = -1;
    try {
      size = Files.size(path);
    } catch (IOException e) {
      App.LOGGER.error("Unable to get size of file {}", path, e);
    }

    final Pair<String, String> inBytes, inBibytes;
    if (size >= 0) {
      inBytes = FileUtils.formatBytesSize(size, false);
      inBibytes = FileUtils.formatBytesSize(size, true);
    } else {
      inBytes = new Pair<>("?", "");
      inBibytes = new Pair<>("?", "");
    }

    final Language language = App.config().language();
    this.fileMetadataLabel.setText(language.translate(
        "image_preview.file_metadata.label",
        new FormatArg("width", (int) image.getWidth()),
        new FormatArg("height", (int) image.getHeight()),
        new FormatArg("bytes", inBytes.getKey()),
        new FormatArg("unit", inBytes.getValue()),
        new FormatArg("bibytes", inBibytes.getKey()),
        new FormatArg("biunit", inBibytes.getValue())
    ));
    this.fileMetadataLabel.setTooltip(new Tooltip(language.translate(
        "image_preview.file_metadata.tooltip",
        new FormatArg("bytes", language.formatNumber(size))
    )));
  }

  private void onOpenFile() {
    if (this.picture != null)
      FileUtils.openInFileExplorer(this.picture.path().toString());
  }
}
