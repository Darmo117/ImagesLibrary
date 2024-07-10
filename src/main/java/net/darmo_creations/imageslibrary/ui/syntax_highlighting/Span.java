package net.darmo_creations.imageslibrary.ui.syntax_highlighting;

import net.darmo_creations.imageslibrary.ui.*;
import org.jetbrains.annotations.*;

import java.util.*;

/**
 * This class represents a span of text to which a CSS class should be applied to in an {@link AutoCompleteField}.
 *
 * @param cssClass The name of the CSS class.
 * @param start    The start text position.
 * @param end      The end text position (included).
 */
public record Span(@NotNull String cssClass, int start, int end) {
  public Span {
    Objects.requireNonNull(cssClass);
  }
}
