package net.darmo_creations.imageslibrary.ui.dialogs;

import javafx.event.*;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.*;
import net.darmo_creations.imageslibrary.*;
import net.darmo_creations.imageslibrary.config.*;
import net.darmo_creations.imageslibrary.data.*;
import net.darmo_creations.imageslibrary.themes.*;
import net.darmo_creations.imageslibrary.ui.*;
import net.darmo_creations.imageslibrary.ui.syntax_highlighting.*;
import net.darmo_creations.imageslibrary.utils.*;
import org.controlsfx.control.*;
import org.fxmisc.richtext.*;
import org.jetbrains.annotations.*;

import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * Dialog to create/edit images.
 */
public class EditImagesDialog extends DialogBase<Boolean> {
  private final HBox imageViewBox;
  private final ImageView imageView = new ImageView();
  private final TextField fileNameField = new TextField();
  private final Label fileMetadataLabel = new Label();
  private final Button viewSimilarImagesButton = new Button();
  private final Button moveButton = new Button();
  private final Button showInExplorerButton = new Button();
  private final Button clearPathButton = new Button();
  private final CheckBox overwriteTargetCheckBox = new CheckBox();
  private final Label targetPathLabel = new Label();
  private final HBox pathBox = new HBox(5);
  private final TextPopOver tagsErrorPopup;
  private final AutoCompleteField<Tag, String> tagsField;
  private final Button nextButton;
  private final Button skipButton;
  private final Button finishButton;

  private final SimilarImagesDialog similarImagesDialog;

  private boolean areTagsValid = false;

  private final DatabaseConnection db;

  private int totalPictures;
  private final Queue<Picture> pictures = new LinkedList<>();
  @Nullable
  private Hash computedHash;
  private final Set<Tag> currentPictureTags = new HashSet<>();
  private Path targetPath;
  private Picture currentPicture;
  private boolean insert;
  private boolean anyUpdate;
  private boolean preventClosing;

  public EditImagesDialog(@NotNull Config config, @NotNull DatabaseConnection db) {
    super(config, "edit_images", true, ButtonTypes.FINISH, ButtonTypes.SKIP, ButtonTypes.NEXT, ButtonTypes.CANCEL);
    this.db = Objects.requireNonNull(db);

    this.similarImagesDialog = new SimilarImagesDialog(config, db);
    this.similarImagesDialog.addTagCopyListener(this::onCopyTags);

    this.tagsErrorPopup = new TextPopOver(PopOver.ArrowLocation.LEFT_CENTER, config);
    this.tagsField = new AutoCompleteField<>(
        new InlineCssTextArea(),
        this.db.getAllTags(),
        t -> t.definition().isEmpty(),
        Tag::label,
        new TagListSyntaxHighlighter(db.getAllTags(), db.getAllTagTypes()),
        Function.identity(),
        config
    );

    this.nextButton = (Button) this.getDialogPane().lookupButton(ButtonTypes.NEXT);
    this.nextButton.addEventFilter(ActionEvent.ACTION, event -> {
      if (this.applyChanges())
        this.nextPicture();
      event.consume();
    });
    this.skipButton = (Button) this.getDialogPane().lookupButton(ButtonTypes.SKIP);
    this.skipButton.addEventFilter(ActionEvent.ACTION, event -> {
      this.nextPicture();
      event.consume();
    });
    this.finishButton = (Button) this.getDialogPane().lookupButton(ButtonTypes.FINISH);

    this.imageViewBox = new HBox(this.imageView);
    this.getDialogPane().setContent(this.createContent());

    final Stage stage = this.stage();
    stage.setMinWidth(800);
    stage.setMinHeight(600);

    this.setResultConverter(buttonType -> {
      if (!buttonType.getButtonData().isCancelButton())
        this.preventClosing = !this.applyChanges();
      return this.anyUpdate;
    });

    this.setOnCloseRequest(event -> {
      if (this.preventClosing) {
        event.consume();
        this.preventClosing = false;
      } else // Hide the similar images dialog when this one closes
        this.similarImagesDialog.hide();
    });
  }

  private Node createContent() {
    final Language language = this.config.language();

    this.imageViewBox.setAlignment(Pos.CENTER);
    this.imageViewBox.setMinHeight(200);
    this.imageViewBox.heightProperty().addListener((observable, oldValue, newValue) -> this.updateImageViewSize());
    this.stage().widthProperty().addListener((observable, oldValue, newValue) -> this.updateImageViewSize());

    this.imageView.setPreserveRatio(true);

    this.fileNameField.textProperty().addListener((observable, oldValue, newValue) -> this.updateState());
    HBox.setHgrow(this.fileNameField, Priority.ALWAYS);
    final HBox fileNameBox = new HBox(
        5,
        new Label(language.translate("dialog.edit_images.file_name")),
        this.fileNameField
    );
    fileNameBox.setAlignment(Pos.CENTER_LEFT);

    final HBox metadataBox = new HBox(this.fileMetadataLabel);
    metadataBox.setAlignment(Pos.CENTER);

    this.viewSimilarImagesButton.setText(language.translate("dialog.edit_images.view_similar_images"));
    this.viewSimilarImagesButton.setGraphic(this.config.theme().getIcon(Icon.SHOW_SILIMAR_IMAGES, Icon.Size.SMALL));
    this.viewSimilarImagesButton.setOnAction(event -> this.onViewSimilarAction());

    this.moveButton.setText(language.translate("dialog.edit_images.move_image"));
    this.moveButton.setGraphic(this.config.theme().getIcon(Icon.MOVE_IMAGES, Icon.Size.SMALL));
    this.moveButton.setOnAction(event -> this.onMoveAction());

    this.showInExplorerButton.setText(language.translate("dialog.edit_images.show_in_explorer"));
    this.showInExplorerButton.setGraphic(this.config.theme().getIcon(Icon.OPEN_FILE_IN_EXPLORER, Icon.Size.SMALL));
    this.showInExplorerButton.setOnAction(event -> this.onOpenFileAction());

    this.clearPathButton.setText(language.translate("dialog.edit_images.clear_path"));
    this.clearPathButton.setGraphic(this.config.theme().getIcon(Icon.CLEAR_TEXT, Icon.Size.SMALL));
    this.clearPathButton.setOnAction(event -> this.clearTargetPath());
    this.clearPathButton.setDisable(true);

    this.overwriteTargetCheckBox.setText(language.translate("dialog.edit_images.overwrite_target"));
    this.overwriteTargetCheckBox.setDisable(true);

    final HBox buttonsBox = new HBox(
        5,
        this.viewSimilarImagesButton,
        this.showInExplorerButton,
        this.moveButton,
        this.clearPathButton,
        this.overwriteTargetCheckBox
    );
    buttonsBox.setAlignment(Pos.CENTER);
    buttonsBox.setPadding(new Insets(0, 5, 0, 5));

    this.pathBox.setAlignment(Pos.CENTER);
    this.pathBox.setPadding(new Insets(0, 5, 0, 5));
    this.pathBox.managedProperty().bind(this.pathBox.visibleProperty());
    this.pathBox.getChildren().addAll(
        new Label(language.translate("dialog.edit_images.target_directory.label")),
        this.targetPathLabel
    );
    this.pathBox.setVisible(false);

    this.tagsField.textProperty().addListener((observable, oldValue, newValue) -> {
      final var styleClass = this.tagsField.getStyleClass();
      if (!newValue.isBlank()) {
        this.areTagsValid = false;
        try {
          JavaFxUtils.parseTags(this.tagsField, this.db);
          this.areTagsValid = true;
        } catch (final TagParseException e) {
          this.tagsErrorPopup.setText(language.translate(e.translationKey(), e.formatArgs()));
        }
      } else {
        this.areTagsValid = false;
        this.tagsErrorPopup.setText(language.translate("dialog.edit_images.no_tags"));
      }

      if (!this.areTagsValid) {
        this.showTagsErrorPopup();
        if (!styleClass.contains("invalid"))
          styleClass.add("invalid");
      } else {
        this.tagsErrorPopup.hide();
        styleClass.remove("invalid");
      }
      this.updateState();
    });
    this.tagsField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
      if (event.getCode() == KeyCode.ENTER && event.isShortcutDown()) {
        if (!this.nextButton.isDisabled())
          this.nextButton.fire();
        else
          this.finishButton.fire();
        event.consume();
      }
    });
    VBox.setVgrow(this.tagsField, Priority.ALWAYS);

    final SplitPane splitPane = new SplitPane(
        this.imageViewBox,
        new VBox(5, metadataBox, buttonsBox, this.pathBox, fileNameBox, this.tagsField)
    );
    splitPane.setOrientation(Orientation.VERTICAL);
    splitPane.setDividerPositions(0.75);
    splitPane.setPrefWidth(800);
    splitPane.setPrefHeight(600);
    return splitPane;
  }

  @Override
  protected List<FormatArg> getTitleFormatArgs() {
    return List.of(
        new FormatArg("count", this.totalPictures - (this.pictures != null ? this.pictures.size() : 0)),
        new FormatArg("total", this.totalPictures)
    );
  }

  private void showTagsErrorPopup() {
    if (this.getDialogPane().getScene() != null && this.stage().isShowing()) // Fix an NPE that sometimes occur
      this.tagsErrorPopup.show(this.tagsField);
  }

  private void onCopyTags(final @NotNull Set<Tag> tags) {
    final var joiner = new StringJoiner(" ");
    tags.stream()
        .map(Tag::label)
        .sorted()
        .forEach(joiner::add);
    this.tagsField.setText(joiner.toString());
  }

  /**
   * Set the list of pictures to insert or edit.
   * If inserting, this dialog will compute each picture’s hash.
   *
   * @param pictures The pictures to insert/edit.
   * @param insert   If true the pictures will be inserted, otherwise they will be updated.
   */
  public void setPictures(final @NotNull Collection<Picture> pictures, boolean insert) {
    if (pictures.isEmpty())
      throw new IllegalArgumentException("pictures must not be empty");
    this.insert = insert;
    this.name = insert ? "insert_images" : "edit_images";
    this.refreshTitle();
    this.pictures.clear();
    this.pictures.addAll(pictures);
    this.totalPictures = pictures.size();
    this.currentPictureTags.clear();
    this.tagsField.setText("");
    this.anyUpdate = false;
    this.clearTargetPath();
    this.nextPicture();
  }

  private void updateImageViewSize() {
    final Image image = this.imageView.getImage();
    if (image != null) {
      final double width = Math.min(image.getWidth(), this.stage().getWidth() - 20);
      this.imageView.setFitWidth(width);
      final double height = Math.min(image.getHeight(), this.imageViewBox.getHeight() - 10);
      this.imageView.setFitHeight(height);
    }
  }

  /**
   * Pop the next picture from the queue and reset the edit form with the image’s data.
   */
  private void nextPicture() {
    if (this.pictures.isEmpty())
      return;
    this.currentPicture = this.pictures.poll();
    if (!this.insert)
      try {
        this.currentPictureTags.clear();
        this.currentPictureTags.addAll(this.db.getImageTags(this.currentPicture));
      } catch (final DatabaseOperationException e) {
        Alerts.error(this.config, "dialog.edit_images.tags_querying_error.header", null, null);
      }
    this.imageView.setImage(null);
    final Path path = this.currentPicture.path();
    final String fileName = path.getFileName().toString();
    this.fileNameField.setText(fileName);
    this.fileMetadataLabel.setText(null);
    this.fileMetadataLabel.setTooltip(null);
    this.fileMetadataLabel.setGraphic(
        this.config.theme().getIcon(this.currentPicture.isVideo() ? Icon.VIDEO : Icon.IMAGE, Icon.Size.SMALL));
    final Language language = this.config.language();
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
            this.updateImageViewSize();
          },
          error -> this.fileMetadataLabel.setText(language.translate("image_preview.missing_file"))
      );
    }
    final var joiner = new StringJoiner(" ");
    if (!this.insert) {
      this.currentPictureTags.stream()
          .map(Tag::label)
          .sorted()
          .forEach(joiner::add);
      this.tagsField.setText(joiner.toString());
    } else
      this.tagsField.refreshHighlighting();
    this.updateState();
    List<Pair<Picture, Float>> similarPictures = List.of();
    final Optional<Hash> hash = this.currentPicture.hash()
        // Try to compute missing hash
        .or(() -> {
          final Optional<Hash> h = Hash.computeForFile(path);
          this.computedHash = h.orElse(null);
          return h;
        });
    if (hash.isPresent())
      try {
        similarPictures = this.db.getSimilarImages(hash.get(), this.currentPicture);
      } catch (final DatabaseOperationException e) {
        App.logger().error("Error fetching similar pictures", e);
      }
    this.viewSimilarImagesButton.setDisable(similarPictures.isEmpty());
    this.similarImagesDialog.setPictures(similarPictures);
    this.refreshTitle();
  }

  private boolean applyChanges() {
    final Optional<PictureUpdate> update = this.getPictureUpdate(true);
    if (update.isPresent()) {
      if (this.insert)
        try {
          this.currentPicture = this.db.insertPicture(update.get());
          this.anyUpdate = true;
        } catch (final DatabaseOperationException e) {
          Alerts.databaseError(this.config, e.errorCode());
          return false;
        }
      else
        try {
          this.db.updatePicture(update.get());
          this.anyUpdate = true;
        } catch (final DatabaseOperationException e) {
          Alerts.databaseError(this.config, e.errorCode());
          return false;
        }
    }

    //noinspection OptionalGetWithoutIsPresent
    final String name = Objects.requireNonNull(StringUtils.stripNullable(this.fileNameField.getText()).get());
    Path targetPath = this.targetPath;
    if (targetPath == null)
      targetPath = this.currentPicture.path().getParent();
    targetPath = targetPath.resolve(name);

    if (!targetPath.equals(this.currentPicture.path()))
      try {
        this.db.moveOrRenamePicture(this.currentPicture, targetPath, this.overwriteTargetCheckBox.isSelected());
        this.anyUpdate = true;
      } catch (final DatabaseOperationException e) {
        Alerts.databaseError(this.config, e.errorCode());
        return false;
      }

    return true;
  }

  private Optional<PictureUpdate> getPictureUpdate(boolean recomputeHash) {
    if (!this.areTagsValid)
      return Optional.empty();

    final Set<ParsedTag> parsedTags;
    try {
      parsedTags = JavaFxUtils.parseTags(this.tagsField, this.db);
    } catch (final TagParseException e) {
      return Optional.empty();
    }
    final var toAdd = parsedTags.stream()
        // Parsed tags that are not in the current tags
        .filter(parsedTag -> this.currentPictureTags.stream().noneMatch(tag -> tag.label().equals(parsedTag.label())))
        .map(parsedTag -> new ParsedTag(parsedTag.tagType(), parsedTag.label()))
        .collect(Collectors.toSet());
    final var toRemove = this.currentPictureTags.stream()
        // Current tags that are not in the parsed tags
        .filter(tag -> parsedTags.stream().noneMatch(pair -> pair.label().equals(tag.label())))
        .collect(Collectors.toSet());

    final Optional<String> name = StringUtils.stripNullable(this.fileNameField.getText());
    if (name.isEmpty() || !FileUtils.isValidFile(Path.of(name.get())))
      return Optional.empty();

    Optional<Hash> hash = Optional.ofNullable(this.computedHash);
    if (recomputeHash && hash.isEmpty())
      hash = Hash.computeForFile(this.currentPicture.path());

    return Optional.of(new PictureUpdate(
        this.insert ? 0 : this.currentPicture.id(),
        this.currentPicture.path(),
        hash,
        toAdd,
        toRemove
    ));
  }

  private void onViewSimilarAction() {
    if (!this.similarImagesDialog.isShowing())
      this.similarImagesDialog.show();
  }

  private void onMoveAction() {
    final Optional<Path> path = FileChoosers.showDirectoryChooser(this.config, this.stage());
    this.pathBox.setVisible(path.isPresent());
    if (path.isEmpty())
      return;
    this.targetPath = path.get();
    this.targetPathLabel.setText(this.targetPath.toString());
    this.clearPathButton.setDisable(false);
    this.overwriteTargetCheckBox.setDisable(false);
  }

  private void onOpenFileAction() {
    if (this.currentPicture != null)
      FileUtils.openInFileExplorer(this.currentPicture.path().toString());
  }

  private void clearTargetPath() {
    this.pathBox.setVisible(false);
    this.targetPath = null;
    this.targetPathLabel.setText(null);
    this.clearPathButton.setDisable(true);
    this.overwriteTargetCheckBox.setDisable(true);
  }

  /**
   * Update the state of this dialog’s buttons.
   */
  private void updateState() {
    final boolean noneRemaining = this.pictures.isEmpty();
    final boolean invalid = this.getPictureUpdate(false).isEmpty();
    this.nextButton.setDisable(noneRemaining || invalid);
    this.skipButton.setDisable(noneRemaining);
    this.finishButton.setDisable(!noneRemaining || invalid);
  }
}
