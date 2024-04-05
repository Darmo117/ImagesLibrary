package net.darmo_creations.imageslibrary.data;

import java.util.*;

/**
 * This class indicates how to update a tag type in the database.
 *
 * @param id     The ID of the tag type to update.
 * @param label  The tag type’s new label.
 * @param symbol The tag type’s new symbol.
 * @param color  The tag type’s new color.
 */
public record TagTypeUpdate(
    int id,
    String label,
    char symbol,
    int color
) {
  public TagTypeUpdate {
    Objects.requireNonNull(label);
  }
}
