package net.darmo_creations.imageslibrary.ui;

import javafx.beans.binding.*;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import net.darmo_creations.imageslibrary.config.*;
import net.darmo_creations.imageslibrary.data.*;
import net.darmo_creations.imageslibrary.themes.*;
import net.darmo_creations.imageslibrary.utils.*;
import org.jetbrains.annotations.*;

import java.nio.file.*;
import java.util.*;

// TODO allow tag removal/insertion from the list directly
public class ImagePreviewPane extends SplitPane implements ClickableListCellFactory.ClickListener<ImagePreviewPane.TagEntry> {
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

  public ImagePreviewPane(final @NotNull Config config) {
    this.config = config;
    this.setMinWidth(300);

    final Language language = config.language();
    final Theme theme = config.theme();

    this.openInExplorerButton.setText(language.translate("image_preview.open_in_explorer_button.label"));
    this.openInExplorerButton.setGraphic(theme.getIcon(Icon.OPEN_FILE_IN_EXPLORER, Icon.Size.SMALL));
    this.openInExplorerButton.setOnAction(e -> this.onOpenFile());
    final HBox controlsBox = new HBox(5, this.openInExplorerButton);
    controlsBox.setAlignment(Pos.CENTER);

    final HBox fileNameBox = new HBox(this.fileNameLabel);
    fileNameBox.setAlignment(Pos.CENTER);
    fileNameBox.setPadding(new Insets(0, 5, 0, 5));

    final HBox metadataBox = new HBox(this.fileMetadataLabel);
    metadataBox.setAlignment(Pos.CENTER);
    metadataBox.setPadding(new Insets(0, 5, 0, 5));

    final HBox imageViewBox = new HBox(this.imageView);
    imageViewBox.setAlignment(Pos.CENTER);
    this.imageView.setPreserveRatio(true);
    this.imageView.fitHeightProperty().bind(imageViewBox.heightProperty().subtract(10));
    imageViewBox.setMinHeight(200);

    final HBox tagsLabelBox = new HBox(new Label(language.translate("image_preview.section.tags.title")));
    tagsLabelBox.getStyleClass().add("section-title");
    tagsLabelBox.setAlignment(Pos.CENTER);

    this.editTagsButton.setOnAction(e -> this.editTagsListeners.forEach(
        listener -> listener.onEditTags(Objects.requireNonNull(this.picture))));
    this.editTagsButton.setTooltip(new Tooltip(language.translate("image_preview.section.tags.edit_tags_button")));
    this.editTagsButton.setGraphic(theme.getIcon(Icon.EDIT_TAGS, Icon.Size.SMALL));
    this.editTagsButton.setDisable(true);

    HBox.setHgrow(tagsLabelBox, Priority.ALWAYS);
    final HBox tagsTitleBox = new HBox(5, tagsLabelBox, this.editTagsButton);
    tagsTitleBox.setPadding(new Insets(0, 5, 0, 5));

    this.tagsList.setPrefHeight(150);
    this.tagsList.setCellFactory(ignored -> ClickableListCellFactory.forListener(this));
    VBox.setVgrow(this.tagsList, Priority.ALWAYS);

    this.setOrientation(Orientation.VERTICAL);
    this.getItems().addAll(
        imageViewBox,
        new VBox(5, fileNameBox, metadataBox, tagsTitleBox, this.tagsList)
    );
    this.setDividerPositions(0.75);
    this.setImage(null, null);
  }

  /**
   * Set the splitpane this component is in.
   *
   * @param splitPane The splitpane ancestor.
   * @param left      Whether this component is to the left (true) or right (false) of the splitpane’s divider.
   */
  @SuppressWarnings("SameParameterValue")
  public void setSplitPane(@NotNull SplitPane splitPane, boolean left) {
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
  public void setImage(Picture picture, Set<Tag> tags) {
    if (picture != null)
      Objects.requireNonNull(tags);
    this.picture = picture;

    this.openInExplorerButton.setDisable(true);
    this.imageView.setImage(null);
    this.fileNameLabel.setText(null);
    this.fileNameLabel.setTooltip(null);
    this.fileMetadataLabel.setText(null);
    this.fileMetadataLabel.setTooltip(null);
    this.editTagsButton.setDisable(picture == null);
    this.tagsList.getItems().clear();

    if (picture != null) {
      final Language language = this.config.language();
      final Path path = picture.path();
      final String fileName = path.getFileName().toString();
      this.fileNameLabel.setText(fileName);
      this.fileNameLabel.setTooltip(new Tooltip(fileName));
      boolean exists;
      try {
        exists = Files.exists(path);
      } catch (final SecurityException e) {
        exists = false;
      }
      if (!exists) {
        this.fileMetadataLabel.setText(language.translate("image_preview.missing_file"));
      } else {
        this.fileMetadataLabel.setText(language.translate("image_preview.loading"));
        FileUtils.loadImage(
            path,
            image -> {
              this.imageView.setImage(image);
              final String text = FileUtils.formatImageMetadata(path, image, this.config);
              this.fileMetadataLabel.setText(text);
              this.fileMetadataLabel.setTooltip(new Tooltip(text));
              this.openInExplorerButton.setDisable(false);
            },
            error -> {
              this.fileMetadataLabel.setText(language.translate("image_preview.missing_file"));
              this.openInExplorerButton.setDisable(true);
            }
        );
      }
      final var tagsEntries = tags.stream()
          .sorted(Comparator.comparing(Tag::label))
          .map(TagEntry::new)
          .toList();
      this.tagsList.getItems().addAll(tagsEntries);
    }
  }

  public void addTagClickListener(@NotNull TagClickListener listener) {
    this.tagClickListeners.add(Objects.requireNonNull(listener));
  }

  public void addEditTagsListener(@NotNull EditTagsListener listener) {
    this.editTagsListeners.add(Objects.requireNonNull(listener));
  }

  private void onOpenFile() {
    if (this.picture != null)
      FileUtils.openInFileExplorer(this.picture.path().toString());
  }

  @Override
  public void onItemClick(@NotNull TagEntry item) {
  }

  @Override
  public void onItemDoubleClick(@NotNull TagEntry item) {
    this.tagClickListeners.forEach(listener -> listener.onTagClick(item.tag()));
  }

  static class TagEntry extends HBox {
    private final Tag tag;

    private TagEntry(@NotNull Tag tag) {
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
    void onEditTags(@NotNull Picture picture);
  }
}
