module net.darmo_creations.imageslibrary {
  // JavaFX
  requires javafx.controls;
  requires java.desktop;

  // JavaFX libs
  requires org.controlsfx.controls;
  requires org.fxmisc.richtext;
  requires org.fxmisc.flowless;
  requires reactfx;

  // ImageIO
  requires javafx.swing;

  // Video frame extraction
  requires org.bytedeco.javacv;

  // Database
  requires org.xerial.sqlitejdbc;

  // Tag query parsing
  requires org.antlr.antlr4.runtime;
  requires logicng;

  // Logging
  requires org.slf4j;

  // Config and CLI
  requires ini4j;
  requires com.google.gson;
  requires org.apache.commons.cli;

  // Annotations
  requires org.jetbrains.annotations;

  exports net.darmo_creations.imageslibrary;
  exports net.darmo_creations.imageslibrary.ui;
  exports net.darmo_creations.imageslibrary.ui.syntax_highlighting;
  exports net.darmo_creations.imageslibrary.ui.dialogs;
}