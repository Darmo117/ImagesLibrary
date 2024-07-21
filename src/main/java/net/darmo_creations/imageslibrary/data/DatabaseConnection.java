package net.darmo_creations.imageslibrary.data;

import javafx.application.*;
import javafx.util.*;
import net.darmo_creations.imageslibrary.*;
import net.darmo_creations.imageslibrary.data.sql_functions.*;
import net.darmo_creations.imageslibrary.query_parser.*;
import net.darmo_creations.imageslibrary.ui.*;
import net.darmo_creations.imageslibrary.utils.*;
import org.intellij.lang.annotations.*;
import org.jetbrains.annotations.*;
import org.slf4j.*;
import org.sqlite.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.*;
import java.util.stream.*;

/**
 * This class acts as the access point to an SQLite database file.
 * <p>
 * Instances maintain an internal cache of all tags and tag types.
 */
public final class DatabaseConnection implements AutoCloseable {
  public static final String DATABASE_FILE_EXT = "sqlite3";

  /**
   * A map of all pseudo-tags that can be used in tag queries.
   */
  @Unmodifiable
  public static final Map<String, PseudoTag> PSEUDO_TAGS = Map.of(
      "ext",
      new PatternPseudoTag("""
          SELECT id, path, hash
          FROM images
          WHERE "REGEX"(SUBSTR(path, "RINSTR"(path, '.') + 1), '%s', '%s')
          """, true),

      "no_file",
      new BooleanFlag("""
          SELECT id, path, hash
          FROM images
          WHERE NOT "FILE_EXISTS"(path)
          """),

      "no_tags",
      new BooleanFlag("""
          SELECT i.id, i.path, i.hash
          FROM images AS i
          WHERE (
            SELECT COUNT(*)
            FROM image_tag AS it
            WHERE it.image_id = i.id
          ) = 0
          """),

      "name",
      new PatternPseudoTag("""
          SELECT id, path, hash
          FROM images
          WHERE "REGEX"(SUBSTR(path, "RINSTR"(path, '/') + 1), '%s', '%s')
          """.replace("/", File.separator), true),

      "path",
      new PatternPseudoTag("""
          SELECT id, path, hash
          FROM images
          WHERE "REGEX"(path, '%s', '%s')
          """, true),

      "similar_to",
      new PatternPseudoTag("""
          SELECT id, path, hash
          FROM images
          WHERE hash IS NOT NULL
            AND "SIMILAR_HASHES"(hash, (
              SELECT hash
              FROM images
              WHERE path = '%s'
            ))
          """, false)
  );

  /**
   * The current database schema version.
   */
  private static final int CURRENT_SCHEMA_VERSION = 0;
  /**
   * The name of the database setup file.
   */
  private static final String SETUP_FILE_NAME = "setup.sql";

  private final Logger logger;
  private final Connection connection;

  private final Map<Integer, TagType> tagTypesCache = new HashMap<>();
  private final Map<Integer, Integer> tagTypesCounts = new HashMap<>();
  private final Map<Integer, Tag> tagsCache = new HashMap<>();
  private final Map<Integer, Integer> tagsCounts = new HashMap<>();

  /**
   * Create a new connection to the given SQLite database file.
   *
   * @param file The file containing the database. If it does not exist, it will be created.
   *             If null, the database will be loaded in-memory only.
   * @throws DatabaseOperationException If the file exists but is not a database file or is incompatible.
   */
  public DatabaseConnection(Path file) throws DatabaseOperationException {
    final String fileName = file == null ? ":memory:" : file.toString();
    this.logger = LoggerFactory.getLogger("DB (%s)".formatted(fileName));
    this.logger.info("Connecting to database file at {}", fileName);
    try {
      final boolean needToSetup = file == null || !Files.exists(file);
      final SQLiteConfig sqLiteConfig = new SQLiteConfig();
      sqLiteConfig.enforceForeignKeys(true);
      this.connection = DriverManager.getConnection("jdbc:sqlite:%s".formatted(fileName), sqLiteConfig.toProperties());
      this.injectCustomFunctions();
      this.connection.setAutoCommit(false);
      this.logger.info("Foreign keys enabled.");
      if (needToSetup) // If the DB file does not exist, create it
        this.setupDatabase();
      else
        this.checkSchemaVersion();
    } catch (final SQLException | IOException | SecurityException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
    this.logger.info("Connection established.");

    try {
      this.initCaches();
    } catch (final SQLException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
  }

  /**
   * Auto-detect and inject custom SQL functions into the driver.
   * <p>
   * Functions are automatically detected by checking every class annotated with {@link SqlFunction} in the
   * {@link net.darmo_creations.imageslibrary.data.sql_functions} package.
   *
   * @throws SQLException If any database error occurs.
   */
  private void injectCustomFunctions() throws SQLException {
    this.logger.info("Injecting custom SQL functions…");
    // Cannot use reflection to get classes as it does not work in tests
    @SuppressWarnings("unchecked")
    final Class<? extends org.sqlite.Function>[] functions = new Class[] {
        FileExistsFunction.class,
        HashesSimilarityFunction.class,
        RegexFunction.class,
        RightIndexFunction.class,
        SimilarHashesFunction.class,
    };
    int total = 0;
    for (final var functionClass : functions) {
      final var annotation = functionClass.getAnnotation(SqlFunction.class);
      this.logger.info("Found SQL function '{}'.", annotation.name());
      try {
        org.sqlite.Function.create(
            this.connection,
            annotation.name(),
            functionClass.getConstructor().newInstance(),
            annotation.nArgs(),
            annotation.flags()
        );
      } catch (final InstantiationException | IllegalAccessException | InvocationTargetException |
                     NoSuchMethodException e) {
        this.logCaughtError(e);
      }
      total++;
    }
    this.logger.info("Loaded {} functions.", total);
  }

  @SQLite
  private static final String CHECK_FOR_PYTHON_DB_0001_QUERY = """
      SELECT name
      FROM sqlite_master
      WHERE type = 'table'
        AND name = 'version'
      """;

  /**
   * Check whether the connected database has the correct format.
   *
   * @throws SQLException               If any database error occurs.
   * @throws DatabaseOperationException If the database has an incorrect structure.
   */
  private void checkSchemaVersion() throws SQLException, DatabaseOperationException {
    // Check if the "images.hash" column is missing
    boolean hashFound = false;
    try (final var statement = this.connection.prepareStatement("PRAGMA TABLE_INFO (images)");
         final var resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        if (resultSet.getString("name").equals("hash")) {
          hashFound = true;
          break;
        }
      }
      if (!hashFound)
        throw this.logThrownError(new DatabaseOperationException(DatabaseErrorCode.PYTHON_DATABASE));
    }
    // Check if the "version" table is present
    try (final var statement = this.connection.prepareStatement(CHECK_FOR_PYTHON_DB_0001_QUERY);
         final var resultSet = statement.executeQuery()) {
      if (resultSet.next())
        throw this.logThrownError(new DatabaseOperationException(DatabaseErrorCode.PYTHON_DATABASE));
    }

    final int schemaVersion;
    try (final var statement = this.connection.prepareStatement("PRAGMA USER_VERSION");
         final var resultSet = statement.executeQuery()) {
      resultSet.next();
      schemaVersion = resultSet.getInt(1);
      if (schemaVersion > CURRENT_SCHEMA_VERSION)
        throw this.logThrownError(new DatabaseOperationException(DatabaseErrorCode.INVALID_SCHEMA_VERSION));
    }
  }

  /**
   * A view to the set of all tag types defined in the database.
   *
   * @return A new view of the set.
   */
  @Contract(pure = true, value = "-> new")
  @UnmodifiableView
  public Set<TagType> getAllTagTypes() {
    return new MapValuesSetView<>(this.tagTypesCache);
  }

  /**
   * A view to the map containing the use counts of all tag types.
   *
   * @return A new view of the map.
   */
  @Contract(pure = true, value = "-> new")
  @UnmodifiableView
  public Map<Integer, Integer> getAllTagTypesCounts() {
    return Collections.unmodifiableMap(this.tagTypesCounts);
  }

  @SQLite
  public static final String INSERT_TAG_TYPES_QUERY = """
      INSERT INTO tag_types (label, symbol, color)
      VALUES (?, ?, ?)
      """;

  /**
   * Insert the given tag types. This is done in a single transaction,
   * if any error occurs, the transaction is rolled back.
   *
   * @param tagTypeUpdates The list of tag types to insert.
   * @throws DatabaseOperationException If any database error occurs.
   */
  public void insertTagTypes(final @NotNull Set<TagTypeUpdate> tagTypeUpdates) throws DatabaseOperationException {
    final List<Pair<Integer, TagTypeUpdate>> generatedIds = new LinkedList<>();

    try (final var statement = this.connection.prepareStatement(INSERT_TAG_TYPES_QUERY, Statement.RETURN_GENERATED_KEYS)) {
      for (final var tagTypeUpdate : tagTypeUpdates) {
        statement.setString(1, tagTypeUpdate.label());
        statement.setString(2, String.valueOf(tagTypeUpdate.symbol()));
        statement.setInt(3, tagTypeUpdate.color());
        statement.executeUpdate();
        final var id = getFirstGeneratedId(statement);
        if (id.isEmpty())
          throw this.logThrownError(new SQLException("Query did not generate any key"));
        generatedIds.add(new Pair<>(id.get(), tagTypeUpdate));
      }
    } catch (final SQLException e) {
      this.rollback();
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
    this.commit();

    // Update caches
    for (final var entry : generatedIds) {
      final int id = entry.getKey();
      final TagTypeUpdate tagTypeUpdate = entry.getValue();
      this.tagTypesCache.put(id, new TagType(
          id,
          tagTypeUpdate.label(),
          tagTypeUpdate.symbol(),
          tagTypeUpdate.color()
      ));
      this.tagTypesCounts.put(id, 0);
    }
  }

  @SQLite
  public static final String UPDATE_TAG_TYPES_QUERY = """
      UPDATE tag_types
      SET label = ?1, symbol = ?2, color = ?3, updating = ?4
      WHERE id = ?5
      """;
  @SQLite
  public static final String RESET_UPDATING_TAG_TYPES_QUERY = """
      UPDATE tag_types
      SET updating = 0
      WHERE updating != 0
      """;

  /**
   * Update the given tag types. This is done in a single transaction,
   * if any error occurs, the transaction is rolled back.
   *
   * @param tagTypeUpdates The list of tag type updates to perform.
   * @throws DatabaseOperationException If any database error occurs.
   */
  public void updateTagTypes(final @NotNull Set<TagTypeUpdate> tagTypeUpdates) throws DatabaseOperationException {
    try (final var statement = this.connection.prepareStatement(UPDATE_TAG_TYPES_QUERY)) {
      int i = 1;
      for (final var tagTypeUpdate : tagTypeUpdates) {
        final int id = tagTypeUpdate.id();
        statement.setString(1, tagTypeUpdate.label());
        statement.setString(2, String.valueOf(tagTypeUpdate.symbol()));
        statement.setInt(3, tagTypeUpdate.color());
        statement.setInt(4, i++);
        statement.setInt(5, id);
        if (statement.executeUpdate() == 0)
          throw this.logThrownError(new SQLException("No tag type with ID %d".formatted(id)));
      }
      try (final var statement1 = this.connection.prepareStatement(RESET_UPDATING_TAG_TYPES_QUERY)) {
        statement1.executeUpdate();
      }
    } catch (final SQLException e) {
      this.rollback();
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
    this.commit();

    // Update caches
    for (final var tagTypeUpdate : tagTypeUpdates) {
      final TagType tagType = this.tagTypesCache.get(tagTypeUpdate.id());
      tagType.setLabel(tagTypeUpdate.label());
      tagType.setSymbol(tagTypeUpdate.symbol());
      tagType.setColor(tagTypeUpdate.color());
    }
  }

  /**
   * Delete the given tag types. This is done in a single transaction,
   * if any error occurs, the transaction is rolled back.
   *
   * @param tagTypes The set of tag types to delete.
   * @throws DatabaseOperationException If any database error occurs.
   */
  public void deleteTagTypes(final @NotNull Set<TagType> tagTypes) throws DatabaseOperationException {
    this.deleteObjects(tagTypes, "tag_types");
    // Update caches
    for (final var tagType : tagTypes) {
      this.tagTypesCache.remove(tagType.id());
      this.tagTypesCounts.remove(tagType.id());
      for (final var tag : this.tagsCache.values()) {
        tag.type().ifPresent(currentType -> {
          if (currentType.id() == tagType.id())
            tag.setType(null);
        });
      }
    }
  }

  /**
   * A view to the set of all tag defined in the database.
   *
   * @return A new view of the set.
   */
  @Contract(pure = true, value = "-> new")
  @UnmodifiableView
  public Set<Tag> getAllTags() {
    return new MapValuesSetView<>(this.tagsCache);
  }

  /**
   * A view to the map containing the use counts of all tags.
   *
   * @return A new view of the map.
   */
  @Contract(pure = true, value = "-> new")
  @UnmodifiableView
  public Map<Integer, Integer> getAllTagsCounts() {
    return Collections.unmodifiableMap(this.tagsCounts);
  }

  @SQLite
  public static final String INSERT_TAGS_QUERY = """
      INSERT INTO tags (label, type_id, definition)
      VALUES (?, ?, ?)
      """;

  /**
   * Insert the given tags. This is done in a single transaction,
   * if any error occurs, the transaction is rolled back.
   *
   * @param tagUpdates The list of tags to insert.
   * @throws DatabaseOperationException If any database error occurs.
   */
  public void insertTags(final @NotNull Set<TagUpdate> tagUpdates) throws DatabaseOperationException {
    final List<Integer> generatedIds;
    final List<TagUpdate> updates = new ArrayList<>(tagUpdates);
    try {
      generatedIds = this.insertTagsNoCommit(updates);
    } catch (final SQLException e) {
      this.rollback();
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
    this.commit();

    // Update caches
    for (int i = 0; i < updates.size(); i++) {
      final TagUpdate tagUpdate = updates.get(i).withId(generatedIds.get(i));
      final int id = tagUpdate.id();
      this.tagsCache.put(id, new Tag(
          id,
          tagUpdate.label(),
          tagUpdate.type().map(tt -> this.tagTypesCache.get(tt.id())).orElse(null),
          tagUpdate.definition().orElse(null)
      ));
      this.tagsCounts.put(id, 0);
      tagUpdate.type().ifPresent(
          tagType -> this.tagTypesCounts.put(tagType.id(), this.tagTypesCounts.get(tagType.id()) + 1));
    }
  }

  /**
   * Insert the given tags. This method does not perform any kind of transaction managment,
   * it is the responsablity of the caller to do so.
   *
   * @param tagUpdates The list of tags to insert. Updates are performed in the order of the list.
   * @return The list of generated IDs for each {@link TagUpdate} object, in the same order.
   * @throws SQLException If a tag with the same label already exists in the database, or any database error occurs.
   */
  private List<Integer> insertTagsNoCommit(final @NotNull List<TagUpdate> tagUpdates) throws SQLException {
    final List<Integer> generatedIds = new LinkedList<>();
    try (final var statement = this.connection.prepareStatement(INSERT_TAGS_QUERY, Statement.RETURN_GENERATED_KEYS)) {
      for (final var tagUpdate : tagUpdates) {
        statement.setString(1, tagUpdate.label());
        if (tagUpdate.type().isEmpty())
          statement.setNull(2, Types.INTEGER);
        else
          statement.setInt(2, tagUpdate.type().get().id());
        statement.setString(3, tagUpdate.definition().orElse(null));
        statement.executeUpdate();
        final var id = getFirstGeneratedId(statement);
        if (id.isEmpty())
          throw this.logThrownError(new SQLException("Query did not generate any key"));
        generatedIds.add(id.get());
      }
    }
    return generatedIds;
  }

  /**
   * Update the given tags. This is done in a single transaction,
   * if any error occurs, the transaction is rolled back.
   *
   * @param tagUpdates The list of tag updates to perform. Updates are performed in the order of the list.
   * @throws DatabaseOperationException If any database error occurs.
   */
  public void updateTags(final @NotNull Set<TagUpdate> tagUpdates) throws DatabaseOperationException {
    try {
      this.updateTagsNoCommit(new ArrayList<>(tagUpdates));
    } catch (final SQLException e) {
      this.rollback();
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    } catch (final DatabaseOperationException e) {
      this.rollback();
      throw e;
    }
    this.commit();

    // Update caches
    for (final var tagUpdate : tagUpdates) {
      final Tag tag = this.tagsCache.get(tagUpdate.id());
      tag.setDefinition(tagUpdate.definition().orElse(null));
      tag.setLabel(tagUpdate.label());
      if (tag.type().isPresent()) {
        final TagType oldTagType = tag.type().get();
        this.tagTypesCounts.put(oldTagType.id(), this.tagTypesCounts.get(oldTagType.id()) - 1);
      }
      final var typeOpt = tagUpdate.type();
      if (typeOpt.isPresent()) {
        final TagType newTagType = typeOpt.get();
        this.tagTypesCounts.put(newTagType.id(), this.tagTypesCounts.get(newTagType.id()) + 1);
        tag.setType(newTagType);
      } else {
        tag.setType(null);
      }
    }
  }

  @SQLite
  public static final String UPDATE_TAGS_QUERY = """
      UPDATE tags
      SET label = ?1, type_id = ?2, definition = ?3, updating = ?4
      WHERE id = ?5
      """;
  @SQLite
  public static final String RESET_UPDATING_TAGS_QUERY = """
      UPDATE tags
      SET updating = 0
      WHERE updating != 0
      """;

  /**
   * Update the given tags. This method does not perform any kind of transaction managment,
   * it is the responsablity of the caller to do so.
   *
   * @param tagUpdates The list of tag updates to perform. Updates are performed in the order of the list.
   * @throws SQLException               If any database error occurs.
   * @throws DatabaseOperationException If any database error occurs.
   */
  private void updateTagsNoCommit(final @NotNull List<TagUpdate> tagUpdates)
      throws SQLException, DatabaseOperationException {
    int i = 1;
    try (final var statement = this.connection.prepareStatement(UPDATE_TAGS_QUERY)) {
      for (final var tagUpdate : tagUpdates) {
        this.ensureInDatabase(tagUpdate);
        final String label = tagUpdate.label();
        final int id = tagUpdate.id();
        statement.setString(1, label);
        if (tagUpdate.type().isEmpty())
          statement.setNull(2, Types.INTEGER);
        else
          statement.setInt(2, tagUpdate.type().get().id());
        if (tagUpdate.definition().isPresent() && this.isTagUsed(tagUpdate))
          throw this.logThrownError(new DatabaseOperationException(DatabaseErrorCode.BOUND_TAG_HAS_DEFINITION));
        statement.setString(3, tagUpdate.definition().orElse(null));
        statement.setInt(4, i++);
        statement.setInt(5, id);
        if (statement.executeUpdate() == 0)
          throw this.logThrownError(new SQLException("No tag with ID %d".formatted(id)));
      }
      try (final var statement1 = this.connection.prepareStatement(RESET_UPDATING_TAGS_QUERY)) {
        statement1.executeUpdate();
      }
    }
  }

  @SQLite
  private static final String SELECT_PICTURES_FOR_TAG_QUERY = """
      SELECT *
      FROM image_tag
      WHERE tag_id = ?
      """;

  /**
   * Check whether the given tag is associated to any picture.
   *
   * @param tag The tag to check.
   * @return True if the tag is associated to at least one picture, false otherwise.
   * @throws SQLException If any database error occurs.
   */
  private boolean isTagUsed(final @NotNull TagLike tag) throws SQLException {
    try (final var statement = this.connection.prepareStatement(SELECT_PICTURES_FOR_TAG_QUERY)) {
      statement.setInt(1, tag.id());
      try (final var resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  /**
   * Delete the given tags. This is done in a single transaction,
   * if any error occurs, the transaction is rolled back.
   *
   * @param tags The set of tags to delete.
   * @throws DatabaseOperationException If any database error occurs.
   */
  public void deleteTags(final @NotNull Set<Tag> tags) throws DatabaseOperationException {
    this.deleteObjects(tags, "tags");
    // Update caches
    for (final var tagUpdate : tags) {
      this.tagsCache.remove(tagUpdate.id());
      this.tagsCounts.remove(tagUpdate.id());
      tagUpdate.type().ifPresent(
          tagType -> this.tagTypesCounts.put(tagType.id(), this.tagTypesCounts.get(tagType.id()) - 1));
    }
  }

  /**
   * Delete the given {@link DatabaseObject}s. This is done in a single transaction,
   * if any error occurs, the transaction is rolled back.
   *
   * @param objects   The set of objects to delete.
   * @param tableName The name of the table to delete the objects from.
   * @throws DatabaseOperationException If any database error occurs.
   */
  private <T extends DatabaseObject> void deleteObjects(
      final @NotNull Set<T> objects,
      @Language(value = "sqlite", prefix = "DELETE FROM ", suffix = " WHERE 1")
      @NotNull String tableName
  ) throws DatabaseOperationException {
    try (final var statement = this.connection.prepareStatement("DELETE FROM %s WHERE id = ?".formatted(tableName))) {
      for (final T o : objects) {
        this.ensureInDatabase(o);
        statement.setInt(1, o.id());
        statement.executeUpdate();
      }
    } catch (final SQLException e) {
      this.rollback();
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    } catch (final DatabaseOperationException e) {
      this.rollback();
      throw e;
    }
    this.commit();
  }

  /**
   * Fetch all images that match the given tag query.
   *
   * @param query A tag query.
   * @return The set of images that match the query.
   * @throws DatabaseOperationException If any database error occurs.
   */
  @Contract(pure = true, value = "_ -> new")
  public Set<Picture> queryPictures(final @NotNull TagQuery query) throws DatabaseOperationException {
    final var sql = query.asSQL();
    if (sql.isEmpty())
      return Set.of();
    return this.getPictures(sql.get());
  }

  private Set<Picture> getPictures(@SQLite @NotNull String query) throws DatabaseOperationException {
    final Set<Picture> pictures = new HashSet<>();
    try (final var statement = this.connection.prepareStatement(query);
         final var resultSet = statement.executeQuery()) {
      while (resultSet.next())
        pictures.add(newPicture(resultSet));
    } catch (final SQLException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
    return pictures;
  }

  @SQLite
  private static final String SELECT_IMAGE_TAGS_QUERY = """
      SELECT t.id
      FROM tags AS t, image_tag AS it
      WHERE it.image_id = ?
        AND it.tag_id = t.id
      """;

  /**
   * Fetch all tags for the given image.
   *
   * @param picture The picture to fetch the tags of.
   * @return The set of all tags attached to the image.
   * @throws DatabaseOperationException If any database error occurs.
   */
  @Contract(pure = true, value = "_ -> new")
  public Set<Tag> getImageTags(@NotNull Picture picture) throws DatabaseOperationException {
    final Set<Tag> tags = new HashSet<>();
    try (final var statement = this.connection.prepareStatement(SELECT_IMAGE_TAGS_QUERY)) {
      statement.setInt(1, picture.id());
      try (final var resultSet = statement.executeQuery()) {
        while (resultSet.next())
          tags.add(this.tagsCache.get(resultSet.getInt("id")));
      }
    } catch (final SQLException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
    return tags;
  }

  @SQLite
  private static final String IMAGES_WITH_PATH_QUERY = """
      SELECT *
      FROM images
      WHERE path = ?1
      """;

  /**
   * Check whether the given file path is already registered in this database.
   * <p>
   * A path is considered registered if any picture has the <em>exact</em> same path.
   *
   * @param path The path to check.
   * @return True if the path is already registered, false otherwise.
   * @throws DatabaseOperationException If any database error occurs.
   */
  @Contract(pure = true)
  public boolean isFileRegistered(@NotNull Path path) throws DatabaseOperationException {
    try (final var statement = this.connection.prepareStatement(IMAGES_WITH_PATH_QUERY)) {
      statement.setString(1, path.toAbsolutePath().toString());
      try (final var resultSet = statement.executeQuery()) {
        return resultSet.next(); // Check if there are any rows
      }
    } catch (final SQLException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
  }

  @SQLite
  private static final String PICTURE_ID_EXISTS_QUERY = """
      SELECT COUNT(*)
      FROM images
      WHERE id = ?1
      """;

  /**
   * Check whether the given picture ID exists.
   *
   * @param pictureId A picture ID.
   * @return True if it exists in the database, false otherwise.
   * @throws DatabaseOperationException If any database error occurs.
   */
  public boolean pictureExists(int pictureId) throws DatabaseOperationException {
    try (final var statement = this.connection.prepareStatement(PICTURE_ID_EXISTS_QUERY)) {
      statement.setInt(1, pictureId);
      try (final var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1) != 0;
      }
    } catch (final SQLException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
  }

  @SQLite
  private static final String SELECT_SIMILAR_IMAGES_QUERY = """
      SELECT id, path, hash, "SIMILARITY_CONFIDENCE"(hash, ?1) AS confidence
      FROM images
      WHERE id != ?2
        AND "SIMILAR_HASHES"(hash, ?1) = 1
      ORDER BY confidence DESC, path
      """;

  /**
   * Fetch all images that have a hash similar to the given one,
   * according to the {@link Hash#computeSimilarity(Hash)} method.
   *
   * @param hash    The reference hash.
   * @param exclude A picture that should be excluded from the result. May be null.
   * @return A list of pairs each containing a picture whose hash is similar the argument
   * and the similarity confidence index. Pairs are sorted in descending confidence index order.
   * @throws DatabaseOperationException If any database error occurs.
   */
  @Contract(pure = true, value = "_, _ -> new")
  public List<Pair<Picture, Float>> getSimilarImages(@NotNull Hash hash, Picture exclude)
      throws DatabaseOperationException {
    final List<Pair<Picture, Float>> pictures = new LinkedList<>();
    try (final var statement = this.connection.prepareStatement(SELECT_SIMILAR_IMAGES_QUERY)) {
      statement.setLong(1, hash.bytes());
      statement.setInt(2, exclude != null ? exclude.id() : -1);
      try (final var resultSet = statement.executeQuery()) {
        while (resultSet.next())
          pictures.add(new Pair<>(
              newPicture(resultSet),
              resultSet.getFloat("confidence")
          ));
      }
    } catch (final SQLException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
    return pictures;
  }

  @SQLite
  private static final String INSERT_IMAGE_QUERY = """
      INSERT INTO images (path, hash)
      VALUES (?, ?)
      """;
  @SQLite
  private static final String IMAGE_WITH_ID_QUERY = """
      SELECT id, path, hash
      FROM images
      WHERE id = ?1
      """;

  /**
   * Insert the given picture.
   *
   * @param pictureUpdate The picture to insert.
   * @return The inserted picture.
   * @throws DatabaseOperationException If any data base error occurs.
   * @throws IllegalArgumentException   If the {@code tagsToRemove} property is not empty.
   */
  public Picture insertPicture(@NotNull PictureUpdate pictureUpdate) throws DatabaseOperationException {
    if (!pictureUpdate.tagsToRemove().isEmpty())
      throw this.logThrownError(new IllegalArgumentException("Cannot remove tags from a picture that is not yet registered"));

    final Pair<Set<Pair<Tag, Boolean>>, Set<Tag>> result;
    final int newId;
    try (final var statement = this.connection.prepareStatement(INSERT_IMAGE_QUERY, Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, pictureUpdate.path().toString());
      statement.setLong(2, pictureUpdate.hash().bytes());
      statement.executeUpdate();
      final var id = getFirstGeneratedId(statement);
      if (id.isEmpty())
        throw this.logThrownError(new SQLException("Query did not generate any key"));
      newId = id.get();
      result = this.updatePictureTagsNoCommit(pictureUpdate.withId(newId));
    } catch (final SQLException e) {
      this.rollback();
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    } catch (final DatabaseOperationException e) {
      this.rollback();
      throw e;
    }
    this.commit();
    this.updateTagsCache(result.getKey(), result.getValue());

    try (final var statement = this.connection.prepareStatement(IMAGE_WITH_ID_QUERY)) {
      statement.setInt(1, newId);
      try (final var resultSet = statement.executeQuery()) {
        resultSet.next();
        return newPicture(resultSet);
      }
    } catch (final SQLException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
  }

  private static Picture newPicture(final @NotNull ResultSet resultSet) throws SQLException {
    return new Picture(
        resultSet.getInt("id"),
        Path.of(resultSet.getString("path")),
        new Hash(resultSet.getLong("hash"))
    );
  }

  @SQLite
  private static final String UPDATE_IMAGE_HASH_QUERY = """
      UPDATE images
      SET hash = ?1
      WHERE id = ?2
      """;

  /**
   * Update the given picture’s hash and tags.
   * <p>
   * To move or rename a picture, see {@link #moveOrRenamePicture(Picture, Path, boolean)}.
   * To merge two pictures, see {@link #mergePictures(Picture, Picture, boolean)}.
   *
   * @param pictureUpdate The picture to update.
   * @throws DatabaseOperationException If any data base error occurs.
   */
  public void updatePicture(@NotNull PictureUpdate pictureUpdate) throws DatabaseOperationException {
    this.ensureInDatabase(pictureUpdate);
    final Pair<Set<Pair<Tag, Boolean>>, Set<Tag>> result;
    try (final var statement = this.connection.prepareStatement(UPDATE_IMAGE_HASH_QUERY)) {
      statement.setLong(1, pictureUpdate.hash().bytes());
      statement.setInt(2, pictureUpdate.id());
      statement.executeUpdate();
      result = this.updatePictureTagsNoCommit(pictureUpdate);
    } catch (final SQLException e) {
      this.rollback();
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    } catch (final DatabaseOperationException e) {
      this.rollback();
      throw e;
    }
    this.commit();
    this.updateTagsCache(result.getKey(), result.getValue());
  }

  @SQLite
  private static final String UPDATE_IMAGE_PATH_QUERY = """
      UPDATE images
      SET path = ?1
      WHERE id = ?2
      """;

  /**
   * Move/rename the given picture. If the underlying file does not exist,
   * the picture’s path still gets updated in the database.
   *
   * @param picture              The picture to move/rename.
   * @param newPath              The destination path.
   * @param overwriteDestination Indicate whether to overwrite any pre-existing file with the same name as the target.
   * @throws DatabaseOperationException If any database or file system error occurs.
   */
  public void moveOrRenamePicture(@NotNull Picture picture, @NotNull Path newPath, boolean overwriteDestination)
      throws DatabaseOperationException {
    this.ensureInDatabase(picture);

    if (picture.path().equals(newPath))
      return;
    try {
      if (!overwriteDestination && Files.exists(picture.path()) && Files.exists(newPath))
        throw this.logThrownError(new DatabaseOperationException(DatabaseErrorCode.FILE_ALREADY_EXISTS_ERROR));
    } catch (final SecurityException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e)));
    }

    try {
      Files.move(picture.path(), newPath);
    } catch (final NoSuchFileException ignored) {
    } catch (final IOException | SecurityException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }

    try (final var statement = this.connection.prepareStatement(UPDATE_IMAGE_PATH_QUERY)) {
      statement.setString(1, newPath.toString());
      statement.setInt(2, picture.id());
      statement.executeUpdate();
    } catch (final SQLException e) {
      this.rollback();
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
    this.commit();
  }

  /**
   * Merge the tags of {@code picture1} into those of {@code picture2},
   * deleting {@code picture1} from the database and optionaly from the disk.
   *
   * @param picture1       The picture whose tags ought to be merged into those of {@code picture2}.
   * @param picture2       The picture which should receive the tags of {@code picture1}.
   * @param deleteFromDisk Whether {@code picture1} should be deleted from the disk.
   * @throws DatabaseOperationException If any database error occurs.
   * @throws IllegalArgumentException   If the two pictures have the same ID and/or path.
   */
  public void mergePictures(@NotNull Picture picture1, @NotNull Picture picture2, boolean deleteFromDisk)
      throws DatabaseOperationException {
    this.ensureInDatabase(picture1);
    this.ensureInDatabase(picture2);
    if (picture1.id() == picture2.id())
      throw this.logThrownError(new IllegalArgumentException("Both pictures have the same ID"));
    if (picture1.path().equals(picture2.path()))
      throw this.logThrownError(new IllegalArgumentException("Both pictures have the same path"));

    final var pic1Tags = this.getImageTags(picture1).stream()
        .map(t -> new ParsedTag(t.type(), t.label()))
        .collect(Collectors.toSet());
    final Pair<Set<Pair<Tag, Boolean>>, Set<Tag>> result;
    try {
      // Add tags of picture1 to picture2
      result = this.updatePictureTagsNoCommit(new PictureUpdate(picture2.id(), picture2.path(), picture2.hash(), pic1Tags, Set.of()));
    } catch (final SQLException e) {
      this.rollback();
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
    this.commit();
    this.updateTagsCache(result.getKey(), result.getValue());
    this.deletePicture(picture1, deleteFromDisk);
  }

  /**
   * Update the tags cache and counts.
   *
   * @param addedTags   The set of tags that were added to an image.
   *                    A boolean value of true indicates that the tag was created,
   *                    false indicates that it already existed.
   * @param removedTags The set of tags that were removed from an image.
   */
  private void updateTagsCache(final @NotNull Set<Pair<Tag, Boolean>> addedTags, final @NotNull Set<Tag> removedTags) {
    for (final var addedTag : addedTags) {
      final Tag tag = addedTag.getKey();
      final int tagId = tag.id();
      final boolean inserted = addedTag.getValue();
      if (inserted) {
        this.tagsCache.put(tagId, tag);
        this.tagsCounts.put(tagId, 1);
        tag.type().ifPresent(
            tagType -> this.tagTypesCounts.put(tagType.id(), this.tagTypesCounts.get(tagType.id()) + 1));
      } else
        this.tagsCounts.put(tagId, this.tagsCounts.get(tagId) + 1);
    }
    for (final var removedTag : removedTags)
      this.tagsCounts.put(removedTag.id(), this.tagsCounts.get(removedTag.id()) - 1);
  }

  @SQLite
  private static final String SELECT_TAG_FROM_LABEL_QUERY = """
      SELECT id, type_id, definition
      FROM tags
      WHERE label = ?
      """;
  @SQLite
  private static final String REMOVE_TAG_FROM_IMAGE_QUERY = """
      DELETE FROM image_tag
      WHERE image_id = ?1
        AND tag_id = ?2
      """;

  /**
   * Update the tags of the given image. This method does not perform any kind of transaction managment,
   * it is the responsablity of the caller to do so.
   *
   * @param pictureUpdate The image to update.
   * @return A pair containing the set of tags that were added to the image
   * and the set of those that were removed from it. In the left set,
   * a boolean value of true indicates that the tag was created,
   * false indicates that it already existed.
   * @throws SQLException               If any database error occurs.
   * @throws DatabaseOperationException If any database error occurs.
   */
  private Pair<Set<Pair<Tag, Boolean>>, Set<Tag>> updatePictureTagsNoCommit(@NotNull PictureUpdate pictureUpdate)
      throws SQLException, DatabaseOperationException {
    // Insert tags
    final Set<Pair<Tag, Boolean>> addedTags = new HashSet<>();
    final List<TagUpdate> toInsert = new LinkedList<>();
    for (final var tagUpdate : pictureUpdate.tagsToAdd()) {
      @Nullable
      final TagType tagType = tagUpdate.getKey();
      if (tagType != null)
        this.ensureInDatabase(tagType);
      final String tagLabel = tagUpdate.getValue();
      final Optional<TagType> tagType = tagUpdate.tagType();
      if (tagType.isPresent())
        this.ensureInDatabase(tagType.get());
      final String tagLabel = tagUpdate.label();
      final var tagOpt = this.getTagForLabel(tagLabel);
      if (tagOpt.isPresent()) {
        final Tag tag = tagOpt.get();
        if (tag.definition().isPresent())
          throw this.logThrownError(new DatabaseOperationException(DatabaseErrorCode.BOUND_TAG_HAS_DEFINITION));
        if (!this.imageHasTag(pictureUpdate.id(), tag.id())) {
          this.addTagToImageNoCommit(pictureUpdate.id(), tag.id());
          addedTags.add(new Pair<>(this.tagsCache.get(tag.id()), false));
        }
      } else
        toInsert.add(new TagUpdate(0, tagLabel, tagType.orElse(null), null));
    }
    final var generatedIds = this.insertTagsNoCommit(toInsert);

    // Remove tags
    try (final var statement = this.connection.prepareStatement(REMOVE_TAG_FROM_IMAGE_QUERY)) {
      statement.setInt(1, pictureUpdate.id());
      for (final var toRemove : pictureUpdate.tagsToRemove()) {
        final int tagId = toRemove.id();
        this.ensureInDatabase(toRemove);
        statement.setInt(2, tagId);
        statement.executeUpdate();
      }
    }

    final Set<Tag> removedTags = new HashSet<>();
    for (int i = 0, generatedIdsSize = generatedIds.size(); i < generatedIdsSize; i++) {
      final var generatedId = generatedIds.get(i);
      final var tagUpdate = toInsert.get(i);
      this.addTagToImageNoCommit(pictureUpdate.id(), generatedId);
      addedTags.add(new Pair<>(new Tag(generatedId, tagUpdate.label(), tagUpdate.type().orElse(null), null), true));
    }
    return new Pair<>(addedTags, removedTags);
  }

  @SQLite
  private static final String SELECT_IMAGE_TAG_QUERY = """
      SELECT COUNT(*)
      FROM image_tag
      WHERE image_id = ?1
        AND tag_id = ?2
      """;

  /**
   * Check whether a picture has a specific tag.
   *
   * @param pictureId The picture’s ID.
   * @param tagId     The tag’s ID.
   * @return True if the picture has the tag, false otherwise.
   * @throws SQLException If any database error occurs.
   */
  private boolean imageHasTag(int pictureId, int tagId) throws SQLException {
    try (final var statement = this.connection.prepareStatement(SELECT_IMAGE_TAG_QUERY)) {
      statement.setInt(1, pictureId);
      statement.setInt(2, tagId);
      try (final var resultSet = statement.executeQuery()) {
        return resultSet.next() && resultSet.getInt(1) != 0;
      }
    }
  }

  /**
   * Return the tag for that has the given label.
   *
   * @param label A tag label.
   * @return An {@link Optional} containing the tag for the label if found, an empty {@link Optional} otherwise.
   * @throws SQLException If any database error occurs.
   */
  private Optional<Tag> getTagForLabel(@NotNull String label) throws SQLException {
    try (final var statement = this.connection.prepareStatement(SELECT_TAG_FROM_LABEL_QUERY)) {
      statement.setString(1, label);
      try (final var resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          final int id = resultSet.getInt("id");
          final int tagTypeId = resultSet.getInt("type_id");
          final String definition = resultSet.getString("definition");
          return Optional.of(new Tag(id, label, this.tagTypesCache.get(tagTypeId), definition));
        } else
          return Optional.empty();
      }
    }
  }

  @SQLite
  private static final String ADD_TAG_TO_IMAGE_QUERY = """
      INSERT INTO image_tag (image_id, tag_id)
      VALUES (?, ?)
      """;

  /**
   * Add a tag to an image. This method does not perform any kind of transaction managment,
   * it is the responsablity of the caller to do so.
   *
   * @param imageId The ID of the image to add the tag to.
   * @param tagId   The ID of the tag to add.
   * @throws SQLException If any database error occurs.
   */
  private void addTagToImageNoCommit(int imageId, int tagId) throws SQLException {
    try (final var statement1 = this.connection.prepareStatement(ADD_TAG_TO_IMAGE_QUERY)) {
      statement1.setInt(1, imageId);
      statement1.setInt(2, tagId);
      statement1.executeUpdate();
    }
  }

  @SQLite
  private static final String DELETE_IMAGE_QUERY = """
      DELETE FROM images
      WHERE id = ?
      """;

  /**
   * Delete the given picture from the database.
   * If the file cannot be deleted, the associated database entry will not be deleted.
   * If the file does not exist, the database entry still gets deleted.
   *
   * @param picture  The picture to delete.
   * @param fromDisk If true, the associated files will be deleted from the disk.
   * @throws DatabaseOperationException If any database or file system error occurs.
   */
  public void deletePicture(final @NotNull Picture picture, boolean fromDisk) throws DatabaseOperationException {
    this.ensureInDatabase(picture);
    if (fromDisk) {
      try {
        Files.delete(picture.path());
      } catch (final NoSuchFileException ignored) {
      } catch (final IOException | SecurityException e) {
        throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
      }
    }

    final Set<Tag> imageTags = this.getImageTags(picture);
    try (final var statement = this.connection.prepareStatement(DELETE_IMAGE_QUERY)) {
      statement.setInt(1, picture.id());
      statement.executeUpdate();
    } catch (final SQLException e) {
      this.rollback();
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
    this.commit();

    // Update tag counts
    imageTags.forEach(imageTag -> this.tagsCounts.put(imageTag.id(), this.tagsCounts.get(imageTag.id()) - 1));
  }

  @SQLite
  private static final String SELECT_SAVED_QUERIES = """
      SELECT name, `query`
      FROM saved_queries
      ORDER BY `order`
      """;

  /**
   * Get an ordered list of all saved queries.
   *
   * @return A new list.
   * @throws DatabaseOperationException If any database error occurs.
   */
  @Contract("-> new")
  public List<SavedQuery> getSavedQueries() throws DatabaseOperationException {
    try (final var statement = this.connection.createStatement();
         final var resultSet = statement.executeQuery(SELECT_SAVED_QUERIES)) {
      final List<SavedQuery> savedQueries = new LinkedList<>();
      while (resultSet.next()) {
        savedQueries.add(new SavedQuery(
            resultSet.getString("name"),
            resultSet.getString("query")
        ));
      }
      return savedQueries;
    } catch (final SQLException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
  }

  @SQLite
  private static final String DELETE_SAVED_QUERIES = """
      -- noinspection SqlWithoutWhere
      DELETE FROM saved_queries
      """;

  @SQLite
  private static final String INSERT_SAVED_QUERY = """
      INSERT INTO saved_queries (name, `query`, `order`)
      VALUES (?, ?, ?)
      """;

  /**
   * Save a list of queries. All pre-existing saved queries are first deleted.
   *
   * @param queries The queries to save.
   * @throws DatabaseOperationException If any database error occurs.
   */
  public void setSavedQueries(final @NotNull List<SavedQuery> queries) throws DatabaseOperationException {
    try (final var clearStatement = this.connection.createStatement();
         final var statement = this.connection.prepareStatement(INSERT_SAVED_QUERY)) {
      clearStatement.executeUpdate(DELETE_SAVED_QUERIES);
      for (int i = 0; i < queries.size(); i++) {
        final SavedQuery savedQuery = queries.get(i);
        statement.setString(1, savedQuery.name());
        statement.setString(2, savedQuery.query());
        statement.setInt(3, i);
        statement.executeUpdate();
      }
    } catch (final SQLException e) {
      this.rollback();
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
    this.commit();
  }

  @SuppressWarnings("SqlResolve")
  @SQLite
  private static final String SELECT_OBJECT_BY_ID_QUERY = """
      SELECT *
      FROM %s
      WHERE id = ?
      """;

  /**
   * Ensure that the given object exists in the database based on its ID.
   *
   * @param element The object to check.
   * @throws DatabaseOperationException If the object is not in the database or any database error occurs.
   */
  private void ensureInDatabase(final @NotNull DatabaseElement element) throws DatabaseOperationException {
    @Language(value = "sqlite", prefix = "SELECT * FROM ", suffix = " WHERE 1")
    final String tableName;
    if (element instanceof TagTypeLike)
      tableName = "tag_types";
    else if (element instanceof TagLike)
      tableName = "tags";
    else if (element instanceof PictureLike)
      tableName = "images";
    else
      throw this.logThrownError(new IllegalArgumentException("Unsupported type: " + element.getClass().getName()));

    try (final var statement = this.connection.prepareStatement(SELECT_OBJECT_BY_ID_QUERY.formatted(tableName))) {
      statement.setInt(1, element.id());
      try (final var resultSet = statement.executeQuery()) {
        if (!resultSet.next())
          throw this.logThrownError(new DatabaseOperationException(DatabaseErrorCode.OBJECT_DOES_NOT_EXIST));
      }
    } catch (final SQLException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
  }

  /**
   * Close this connection, rendering this object unusable.
   *
   * @throws DatabaseOperationException If any database error occurs.
   */
  @Override
  public void close() throws DatabaseOperationException {
    try {
      this.connection.close();
    } catch (final SQLException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
  }

  /**
   * Setup the database using the file at {@link #SETUP_FILE_NAME}.
   */
  private void setupDatabase() throws SQLException, IOException {
    this.logger.info("Creating database file…");
    final var stream = this.getClass().getResourceAsStream("/" + SETUP_FILE_NAME);
    if (stream == null)
      throw this.logThrownError(new IOException("Missing file: %s".formatted(SETUP_FILE_NAME)));
    final var query = new StringBuilder();
    try (final var reader = new BufferedReader(new InputStreamReader(stream))) {
      for (String line; (line = reader.readLine()) != null; )
        query.append(line).append('\n');
    }
    this.executeUpdateQuery(query.toString());
    this.logger.info("Done.");
  }

  /**
   * Initalize the internal tag and tag type caches.
   */
  private void initCaches() throws SQLException {
    this.logger.info("Initializing caches…");
    try (final var statement = this.connection.prepareStatement("SELECT id, label, symbol, color FROM tag_types");
         final var resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        final int id = resultSet.getInt("id");
        this.tagTypesCache.put(id, new TagType(
            id,
            resultSet.getString("label"),
            resultSet.getString("symbol").charAt(0),
            resultSet.getInt("color")
        ));
        this.tagTypesCounts.put(id, 0);
      }
    } catch (final SQLException e) {
      throw this.logThrownError(e);
    }
    this.logger.info("Found {} tag type(s)", this.tagTypesCache.size());

    try (final var statement = this.connection.prepareStatement("SELECT id, label, type_id, definition FROM tags");
         final var resultSet = statement.executeQuery();
         final var countStatement = this.connection.prepareStatement("SELECT COUNT(*) FROM image_tag WHERE tag_id = ?")) {
      while (resultSet.next()) {
        final int id = resultSet.getInt("id");
        @Nullable
        final TagType tagType = this.tagTypesCache.get(resultSet.getInt("type_id"));
        this.tagsCache.put(id, new Tag(
            id,
            resultSet.getString("label"),
            tagType,
            resultSet.getString("definition")
        ));
        countStatement.setInt(1, id);
        try (final var countResultSet = countStatement.executeQuery()) {
          countResultSet.next();
          this.tagsCounts.put(id, countResultSet.getInt(1));
          if (tagType != null)
            this.tagTypesCounts.put(tagType.id(), this.tagTypesCounts.get(tagType.id()) + 1);
        }
      }
    } catch (final SQLException e) {
      throw this.logThrownError(e);
    }
    this.logger.info("Found {} tag(s)", this.tagsCache.size());
    this.logger.info("Done.");
  }

  /**
   * Execute the given non-{@code SELECT} SQL query in a single transaction.
   * <p>
   * If the method throws a {@link SQLException}, the transaction is rollbacked.
   *
   * @param query The SQL query to execute, may contain several statements.
   */
  private void executeUpdateQuery(@SQLite @NotNull String query) throws SQLException {
    try (final var statement = this.connection.createStatement()) {
      statement.executeUpdate(query);
    } catch (final SQLException e) {
      this.connection.rollback();
      throw this.logThrownError(e);
    }
    this.connection.commit();
  }

  /**
   * Rollback the current transaction.
   *
   * @throws DatabaseOperationException If any database error occurs.
   */
  private void rollback() throws DatabaseOperationException {
    try {
      this.connection.rollback();
    } catch (final SQLException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
  }

  /**
   * Commit the current transaction.
   *
   * @throws DatabaseOperationException If any database error occurs.
   */
  private void commit() throws DatabaseOperationException {
    try {
      this.connection.commit();
    } catch (final SQLException e) {
      throw this.logThrownError(new DatabaseOperationException(getErrorCode(e), e));
    }
  }

  /**
   * Log an exception that is being thrown by a method of this class.
   *
   * @param e The exception to log.
   * @return The passed exception.
   */
  @Contract("_ -> param1")
  private <E extends Exception> E logThrownError(final @NotNull E e) {
    final var out = new StringWriter();
    try (final var writer = new PrintWriter(out)) {
      e.printStackTrace(writer);
    }
    this.logger.error("Exception thrown in method {}:\n{}", ReflectionUtils.getCallingMethodName(), out);
    return e;
  }

  /**
   * Log an exception that was caught.
   *
   * @param e The exception to log.
   */
  private void logCaughtError(final @NotNull Exception e) {
    final var stackTrack = new StringWriter();
    try (final var writer = new PrintWriter(stackTrack)) {
      e.printStackTrace(writer);
    }
    this.logger.warn("Caught exception in method {}:\n{}", ReflectionUtils.getCallingMethodName(), stackTrack);
  }

  /**
   * Return a {@link DatabaseErrorCode} for the given {@link Exception}.
   *
   * @param e The exception to get a code for.
   * @return The code for that exception.
   */
  private static DatabaseErrorCode getErrorCode(final @NotNull Exception e) {
    if (e instanceof org.sqlite.SQLiteException ex)
      return DatabaseErrorCode.forSQLiteCode(ex.getResultCode());
    if (e instanceof FileAlreadyExistsException)
      return DatabaseErrorCode.FILE_ALREADY_EXISTS_ERROR;
    if (e instanceof FileNotFoundException)
      return DatabaseErrorCode.MISSING_FILE_ERROR;
    if (e instanceof IOException)
      return DatabaseErrorCode.UNKNOWN_FILE_ERROR;
    if (e instanceof SecurityException)
      return DatabaseErrorCode.MISSING_PERMISSIONS_ERROR;
    return DatabaseErrorCode.UNKNOWN_ERROR;
  }

  /**
   * Return the first key generated by the given statement.
   *
   * @param statement The statement to retrieve the generated keys from.
   * @return An {@link Optional} containing the first key generated by the statement
   * or an empty {@link Optional} if the statement did not generate any key.
   */
  private static Optional<Integer> getFirstGeneratedId(@NotNull Statement statement) throws SQLException {
    try (final var generatedKeys = statement.getGeneratedKeys()) {
      if (!generatedKeys.next())
        return Optional.empty();
      return Optional.of(generatedKeys.getInt(1));
    }
  }

  /**
   * Convert a database file created by the Python app to this app’s format.
   * The original file remains unchanged and the converted database is written to a new file.
   * <p>
   * Callbacks will be called on the JavaFX application thread, except for {@link ProgressManager#isCancelled()}.
   *
   * @param file            The path to the file to convert.
   * @param onSuccess       A callback invoked when the file has been converted.
   * @param onError         A callback invoked when any error occurs and the conversion is aborted.
   * @param progressManager An object that can receive progress updates
   *                        and indicates whether the process should be cancelled.
   */
  public static void convertPythonDatabase(
      final @NotNull Path file,
      @NotNull Consumer<Path> onSuccess,
      @NotNull Consumer<DatabaseOperationException> onError,
      @NotNull ProgressManager progressManager
  ) {
    new Thread(() -> {
      try {
        convertPythonDb(file, progressManager)
            .ifPresent(outputFile -> Platform.runLater(() -> onSuccess.accept(outputFile)));
      } catch (final DatabaseOperationException e) {
        Platform.runLater(() -> onError.accept(e));
      }
    }, "Python DB Converter Thread").start();
  }

  private static Optional<Path> convertPythonDb(
      @NotNull Path file,
      @NotNull ProgressManager progressManager
  ) throws DatabaseOperationException {
    final Path outputPath = file.toAbsolutePath().getParent().resolve("converted-" + file.getFileName());

    try {
      Files.deleteIfExists(outputPath);
    } catch (final IOException | SecurityException e) {
      throw new DatabaseOperationException(getErrorCode(e), e);
    }

    try (final var db = new DatabaseConnection(outputPath);
         final var conn = DriverManager.getConnection("jdbc:sqlite:%s".formatted(file))) {
      final Map<Integer, String> oldTagTypeIds = new HashMap<>();
      if (!convertTagTypes(progressManager, conn, db, oldTagTypeIds)) {
        App.logger().info("Conversion cancelled.");
        deleteConvertedFile(outputPath);
        return Optional.empty();
      }
      final var tagTypes = db.getAllTagTypes().stream()
          .collect(Collectors.toMap(TagType::label, Function.identity()));

      final Map<Integer, String> oldTagIds = new HashMap<>();
      if (!convertTags(progressManager, conn, db, oldTagIds, tagTypes, oldTagTypeIds)) {
        App.logger().info("Conversion cancelled.");
        deleteConvertedFile(outputPath);
        return Optional.empty();
      }
      final var tags = db.getAllTags().stream()
          .collect(Collectors.toMap(Tag::label, Function.identity()));

      if (!convertImages(progressManager, conn, db, tags, oldTagIds)) {
        App.logger().info("Conversion cancelled.");
        deleteConvertedFile(outputPath);
        return Optional.empty();
      }
    } catch (final SQLException e) {
      deleteConvertedFile(outputPath);
      throw new DatabaseOperationException(getErrorCode(e), e);
    } catch (final DatabaseOperationException e) {
      deleteConvertedFile(outputPath);
      throw e;
    }
    return Optional.of(outputPath);
  }

  private static boolean convertTagTypes(
      @NotNull ProgressManager progressManager,
      @NotNull Connection connection,
      @NotNull DatabaseConnection db,
      @NotNull Map<Integer, String> oldTagTypeIds
  ) throws SQLException, DatabaseOperationException {
    try (final var statement = connection.prepareStatement("SELECT id, label, symbol, color FROM tag_types");
         final var resultSet = statement.executeQuery()) {
      final int total = getTableRowCount("tag_types", connection);
      int counter = 0;
      notifyProgress(progressManager, "progress.converting_python_db.tag_types", total, counter);
      final Set<TagTypeUpdate> updates = new HashSet<>();
      while (resultSet.next()) {
        if (progressManager.isCancelled())
          return false;
        final int id = resultSet.getInt("id");
        final String label = resultSet.getString("label");
        final char symbol = resultSet.getString("symbol").charAt(0);
        final int color = resultSet.getInt("color");
        updates.add(new TagTypeUpdate(0, label, symbol, color));
        oldTagTypeIds.put(id, label);
        counter++;
        notifyProgress(progressManager, "progress.converting_python_db.tag_types", total, counter);
      }
      db.insertTagTypes(updates);
    }
    return true;
  }

  private static boolean convertTags(
      @NotNull ProgressManager progressManager,
      @NotNull Connection connection,
      @NotNull DatabaseConnection db,
      @NotNull Map<Integer, String> oldTagIds,
      final @NotNull Map<String, TagType> tagTypes,
      final @NotNull Map<Integer, String> oldTagTypeIds
  ) throws SQLException, DatabaseOperationException {
    try (final var statement = connection.prepareStatement("SELECT id, label, type_id, definition FROM tags");
         final var resultSet = statement.executeQuery()) {
      final int total = getTableRowCount("tags", connection);
      int counter = 0;
      notifyProgress(progressManager, "progress.converting_python_db.tags", total, counter);
      final Set<TagUpdate> updates = new HashSet<>();
      while (resultSet.next()) {
        if (progressManager.isCancelled())
          return false;
        final int id = resultSet.getInt("id");
        final String label = resultSet.getString("label");
        final int typeId = resultSet.getInt("type_id");
        final String definition = resultSet.getString("definition");
        updates.add(new TagUpdate(0, label, typeId != 0 ? tagTypes.get(oldTagTypeIds.get(typeId)) : null, definition));
        oldTagIds.put(id, label);
        counter++;
        notifyProgress(progressManager, "progress.converting_python_db.tags", total, counter);
      }
      db.insertTags(updates);
    }
    return true;
  }

  private static boolean convertImages(
      @NotNull ProgressManager progressManager,
      @NotNull Connection connection,
      @NotNull DatabaseConnection db,
      final @NotNull Map<String, Tag> tags,
      final @NotNull Map<Integer, String> oldTagIds
  ) throws SQLException, DatabaseOperationException {
    try (final var statement = connection.prepareStatement("SELECT id, path FROM images"); // Ignoring "hash" column as we recompute it
         final var tagsStatement = connection.prepareStatement("SELECT tag_id FROM image_tag WHERE image_id = ?");
         final var resultSet = statement.executeQuery()) {
      final int total = getTableRowCount("images", connection);
      int counter = 0;
      notifyProgress(progressManager, "progress.converting_python_db.images", total, counter);
      while (resultSet.next()) {
        if (progressManager.isCancelled())
          return false;
        final int id = resultSet.getInt("id");
        final Path path = Path.of(resultSet.getString("path"));
        Hash hash;
        try {
          hash = Hash.computeForFile(path);
        } catch (final Exception e) {
          App.logger().error("Failed to compute hash for {}", path, e);
          hash = new Hash(0);
        }
        // Fetch associated tags
        final Set<ParsedTag> imageTags = new HashSet<>();
        tagsStatement.setInt(1, id);
        try (final var tagsResultSet = tagsStatement.executeQuery()) {
          while (tagsResultSet.next()) {
            if (progressManager.isCancelled())
              return false;
            final Tag tag = tags.get(oldTagIds.get(tagsResultSet.getInt("tag_id")));
            imageTags.add(new ParsedTag(tag.type(), tag.label()));
          }
        }
        db.insertPicture(new PictureUpdate(0, path.toAbsolutePath(), hash, imageTags, Set.of()));
        counter++;
        notifyProgress(progressManager, "progress.converting_python_db.images", total, counter);
      }
    }
    return true;
  }

  private static void notifyProgress(
      @NotNull ProgressManager progressManager,
      @NotNull String key,
      int total,
      int counter
  ) {
    Platform.runLater(() -> progressManager.notifyProgress(key, total, counter));
  }

  /**
   * Return the total number of rows of the given table.
   *
   * @param tableName  The table’s name.
   * @param connection A database connection.
   * @return The total number of rows.
   * @throws SQLException If any database error occurs.
   */
  private static int getTableRowCount(
      @Language(value = "sqlite", prefix = "SELECT COUNT(*) FROM ") @NotNull String tableName,
      @NotNull Connection connection
  ) throws SQLException {
    try (final var rowCountStatement = connection.prepareStatement("SELECT COUNT(*) FROM " + tableName);
         final var rowCountResultSet = rowCountStatement.executeQuery()) {
      rowCountResultSet.next();
      return rowCountResultSet.getInt(1);
    }
  }

  /**
   * Delete the given converted database file.
   * <p>
   * Logs any {@link IOException} using the {@link App}’s global logger.
   *
   * @param file The file to delete.
   */
  private static void deleteConvertedFile(@NotNull Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (final IOException | SecurityException ex) {
      App.logger().error("Failed to delete incomplete database file {}", file, ex);
    }
  }
}
