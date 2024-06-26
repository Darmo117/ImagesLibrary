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
import java.util.*;

// TODO allow tag removal/insertion from the list directly
public class ImagePreviewPane extends VBox implements ClickableListCellFactory.ClickListener<ImagePreviewPane.TagEntry> {
  private final Set<TagClickListener> tagClickListeners = new HashSet<>();
  private final List<EditTagsListener> editTagsListeners = new ArrayList<>();

  private final Button openInExplorerButton = new Button();
  private final Label fileNameLabel = new Label();
  private final Label fileMetadataLabel = new Label();
  private final ImageView imageView = new ImageView();
  private final Button editTagsButton = new Button();
  private final ListView<TagEntry> tagsList = new ListView<>();

  private final Config config;
  @Nullable
  private Picture picture;

  public ImagePreviewPane(final Config config) {
    super(5);
    this.config = config;
    this.setMinWidth(300);
    this.setPadding(new Insets(2, 0, 0, 0));

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
    this.imageView.fitHeightProperty().bind(imageViewBox.heightProperty().subtract(5));
    VBox.setVgrow(imageViewBox, Priority.ALWAYS);

    final HBox tagsLabelBox = new HBox(new Label(language.translate("image_preview.section.tags.title")));
    tagsLabelBox.getStyleClass().add("section-title");
    tagsLabelBox.setAlignment(Pos.CENTER);

    this.editTagsButton.setOnAction(e -> this.editTagsListeners.forEach(listener -> listener.onEditTags(this.picture)));
    this.editTagsButton.setTooltip(new Tooltip(language.translate("image_preview.section.tags.edit_tags_button")));
    this.editTagsButton.setGraphic(theme.getIcon(Icon.EDIT_TAGS, Icon.Size.SMALL));
    this.editTagsButton.setDisable(true);

    this.tagsList.setPrefHeight(150);
    this.tagsList.setCellFactory(ignored -> ClickableListCellFactory.forListener(this));

    HBox.setHgrow(tagsLabelBox, Priority.ALWAYS);
    this.getChildren().addAll(controlsBox,
        fileNameBox,
        metadataBox,
        imageViewBox,
        new HBox(tagsLabelBox, this.editTagsButton),
        this.tagsList
    );
    this.setImage(null, null);
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
   * @param tags    The tags for the image.
   */
  @Contract("!null, null -> fail")
  public void setImage(@Nullable Picture picture, @Nullable Set<Tag> tags) {
    if (picture != null)
      Objects.requireNonNull(tags);
    this.picture = picture;

    boolean noPicture = true;
    this.imageView.setImage(null);
    this.fileNameLabel.setText(null);
    this.fileMetadataLabel.setText(null);
    this.fileMetadataLabel.setTooltip(null);
    this.editTagsButton.setDisable(picture == null);
    this.tagsList.getItems().clear();

    if (picture != null) {
      final Language language = this.config.language();
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
      final var tagsEntries = tags.stream()
          .sorted(Comparator.comparing(Tag::label))
          .map(TagEntry::new)
          .toList();
      this.tagsList.getItems().addAll(tagsEntries);
    }
    this.openInExplorerButton.setDisable(noPicture);
  }

  public void addTagClickListener(TagClickListener listener) {
    this.tagClickListeners.add(Objects.requireNonNull(listener));
  }

  public void addEditTagsListener(EditTagsListener listener) {
    this.editTagsListeners.add(Objects.requireNonNull(listener));
  }

  private void displayMetadata(Path path, Image image) {
    long size = -1;
    try {
      size = Files.size(path);
    } catch (IOException e) {
      App.logger().error("Unable to get size of file {}", path, e);
    }

    final Language language = this.config.language();
    final var formattedSize = size >= 0 ? FileUtils.formatBytesSize(size, language) : new Pair<>("?", "");
    this.fileMetadataLabel.setText(language.translate(
        "image_preview.file_metadata.label",
        new FormatArg("width", (int) image.getWidth()),
        new FormatArg("height", (int) image.getHeight()),
        new FormatArg("abbr_bytes", formattedSize.getKey()),
        new FormatArg("unit", formattedSize.getValue()),
        new FormatArg("full_bytes", language.formatNumber(size)))
    );
  }

  private void onOpenFile() {
    if (this.picture != null)
      FileUtils.openInFileExplorer(this.picture.path().toString());
  }

  @Override
  public void onItemClick(TagEntry item) {
  }

  @Override
  public void onItemDoubleClick(TagEntry item) {
    this.tagClickListeners.forEach(listener -> listener.onTagClick(item.tag()));
  }

  static class TagEntry extends HBox {
    private final Tag tag;

    private TagEntry(Tag tag) {
      this.tag = Objects.requireNonNull(tag);
      final Label label = new Label(tag.label());
      tag.type().ifPresent(tagType -> {
        label.setText(tagType.symbol() + label.getText());
        label.setStyle("-fx-text-fill: %s;".formatted(StringUtils.colorToCss(tagType.color())));
      });
      this.getChildren().add(label);
    }

    public Tag tag() {
      return this.tag;
    }
  }

  public interface EditTagsListener {
    void onEditTags(Picture picture);
  }
}
