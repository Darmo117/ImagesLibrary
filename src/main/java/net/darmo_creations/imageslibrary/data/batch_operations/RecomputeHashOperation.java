package net.darmo_creations.imageslibrary.data.batch_operations;

import javafx.util.*;
import net.darmo_creations.imageslibrary.data.*;
import org.jetbrains.annotations.*;

import java.util.*;

/**
 * An operation that computes the hash of a {@link Picture}.
 */
public final class RecomputeHashOperation extends Operation {
  public static final String KEY = "recompute_hash";

  /**
   * Create a new operation that computes the hash of {@link Picture}s.
   *
   * @param condition An optional condition.
   */
  public RecomputeHashOperation(Condition condition) {
    super(condition);
  }

  @Override
  protected Pair<Boolean, Picture> execute(@NotNull Picture picture, @NotNull DatabaseConnection db)
      throws DatabaseOperationException {
    final Optional<Hash> currentHash = picture.hash();
    final Optional<Hash> hash = Hash.computeForFile(picture.path());
    db.updatePicture(new PictureUpdate(picture.id(), picture.path(), hash, Set.of(), Set.of()));
    return new Pair<>(!currentHash.equals(hash), new Picture(picture.id(), picture.path(), hash.orElse(null)));
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public String serialize() {
    return "";
  }
}
