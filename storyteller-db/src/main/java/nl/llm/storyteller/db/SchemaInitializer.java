package nl.llm.storyteller.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SchemaInitializer {
  private static final String SCHEMA_RESOURCE = "/db/schema.sql";
  private static final Set<String> EXPECTED_TABLES = Set.of(
    "story_session",
    "session_configuration",
    "session_prompt_override",
    "story_message",
    "session_memory",
    "turn_state",
    "turn_protagonist",
    "knowledge_graph",
    "knowledge_entity",
    "knowledge_entity_alias",
    "knowledge_fact"
  );

  private final Database database;

  public SchemaInitializer(Database database) {
    this.database = database;
  }

  public void initialize() {
    try (Connection connection = database.openConnection()) {
      Set<String> existingTables = existingTables(connection);
      Set<String> existingSchemaTables = new HashSet<>(existingTables);
      existingSchemaTables.retainAll(EXPECTED_TABLES);
      if (existingSchemaTables.equals(EXPECTED_TABLES)) {
        addInfiniteSessionColumnIfMissing(connection);
        addColumnIfMissing(connection, "SESSION_CONFIGURATION", "STREAMING_ENABLED", "streaming_enabled BOOLEAN");
        addColumnIfMissing(connection, "SESSION_CONFIGURATION", "GRAPH_ENABLED", "graph_enabled BOOLEAN");
        addColumnIfMissing(connection, "SESSION_CONFIGURATION", "MAX_TOKENS", "max_tokens INTEGER");
        addStoryImageColumnsIfMissing(connection);
        DatabaseEncryptionMigration.initialize(database, connection, existingTables);
        return;
      }
      if (!existingSchemaTables.isEmpty()) {
        throw new IllegalStateException("Database contains an incomplete Storyteller schema.");
      }
      executeSchema(connection, loadSchema());
      DatabaseEncryptionMigration.initialize(database, connection, existingTables);
    } catch (SQLException ex) {
      throw new DatabaseException("Could not initialize the Storyteller database schema.", ex);
    }
  }

  private void addStoryImageColumnsIfMissing(Connection connection) throws SQLException {
    addColumnIfMissing(connection, "STORY_MESSAGE", "IMAGE_MEDIA_TYPE", "image_media_type VARCHAR(64)");
    addColumnIfMissing(connection, "STORY_MESSAGE", "IMAGE_CONTENT", mysql() ? "image_content LONGBLOB" : "image_content BLOB");
  }

  private void addColumnIfMissing(Connection connection, String table, String column, String definition)
    throws SQLException {
    try (ResultSet columns = connection.getMetaData().getColumns(
      connection.getCatalog(), null, identifier(connection, table), identifier(connection, column))) {
      if (columns.next()) {
        return;
      }
    }
    try (var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE " + identifier(connection, table) + " ADD COLUMN " + definition);
    }
  }

  private void addInfiniteSessionColumnIfMissing(Connection connection) throws SQLException {
    try (ResultSet columns = connection.getMetaData().getColumns(
      connection.getCatalog(), null, identifier(connection, "STORY_SESSION"), identifier(connection, "INFINITE"))) {
      if (columns.next()) {
        return;
      }
    }
    try (var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE story_session ADD COLUMN infinite BOOLEAN DEFAULT FALSE NOT NULL");
    }
  }

  private Set<String> existingTables(Connection connection) throws SQLException {
    Set<String> tables = new HashSet<>();
    try (ResultSet resultSet = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
      while (resultSet.next()) {
        tables.add(resultSet.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
      }
    }
    return tables;
  }

  private void executeSchema(Connection connection, String schema) throws SQLException {
    for (String statement : Arrays.stream(schema.split(";"))
      .map(String::trim)
      .filter(value -> !value.isEmpty())
      .toList()) {
      try (var sql = connection.createStatement()) {
        sql.execute(statement);
      }
    }
  }

  private boolean mysql() {
    return database.url().startsWith("jdbc:mysql:");
  }

  private String identifier(Connection connection, String name) throws SQLException {
    return connection.getMetaData().storesUpperCaseIdentifiers() ? name.toUpperCase(Locale.ROOT)
      : name.toLowerCase(Locale.ROOT);
  }

  private String loadSchema() {
    String resource = mysql() ? "/db/schema-mysql.sql" : SCHEMA_RESOURCE;
    try (var input = SchemaInitializer.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("Missing database schema resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("Could not read database schema resource.", ex);
    }
  }
}
