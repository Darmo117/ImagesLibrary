package net.darmo_creations.imageslibrary.data;

import net.darmo_creations.imageslibrary.query_parser.*;
import org.jetbrains.annotations.*;

import java.util.*;

/**
 * A flag is an element of a tag query that is used to filter images based on a boolean property.
 *
 * @param sqlTemplate The SQL query template for this flag.
 * @param predicate   A predicate representing this flag.
 */
public record BooleanFlag(@SQLite @NotNull String sqlTemplate, @NotNull TagQueryPredicate predicate)
    implements PseudoTag {
  public BooleanFlag {
    Objects.requireNonNull(sqlTemplate);
    Objects.requireNonNull(predicate);
  }
}
