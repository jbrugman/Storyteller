package nl.llm.storyteller.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaInitializerTest {
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

  @TempDir
  Path temporaryDirectory;

  @Test
  @DisplayName("""
    Given an empty Storyteller database,
    When the schema is initialized more than once,
    Then it should contain the complete schema without failing
    """)
  void shouldInitializeCompleteSchemaIdempotently() throws Exception {
    Database database = database();
    SchemaInitializer initializer = new SchemaInitializer(database);

    initializer.initialize();
    initializer.initialize();

    assertEquals(EXPECTED_TABLES, storytellerTables(database));
    assertTrue(hasColumn(database, "STORY_MESSAGE", "IMAGE_MEDIA_TYPE"));
    assertTrue(hasColumn(database, "STORY_MESSAGE", "IMAGE_CONTENT"));
  }

  @Test
  @DisplayName("""
    Given an existing API schema without image columns,
    When the schema is initialized,
    Then the additive image-storage upgrade should be applied
    """)
  void shouldAddImageColumnsToExistingSchema() throws Exception {
    Database database = database();
    SchemaInitializer initializer = new SchemaInitializer(database);
    initializer.initialize();
    try (Connection connection = database.openConnection(); var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE story_message DROP COLUMN image_media_type");
      statement.execute("ALTER TABLE story_message DROP COLUMN image_content");
    }

    initializer.initialize();

    assertTrue(hasColumn(database, "STORY_MESSAGE", "IMAGE_MEDIA_TYPE"));
    assertTrue(hasColumn(database, "STORY_MESSAGE", "IMAGE_CONTENT"));
  }

  @Test
  @DisplayName("""
    Given an existing database without a streaming setting,
    When schema initialization runs twice,
    Then the new nullable column is added and existing validation choices are preserved
    """)
  void shouldMigrateGenerationSettings() throws Exception {
    Database database = database();
    var initializer = new SchemaInitializer(database);
    initializer.initialize();
    var now = java.time.Instant.now();
    new JdbcSessionRepository(database).create(
      new SessionRecord("session", "Story", now, now, now, now.plusSeconds(3600), false),
      new SessionPrompts("System", "Fixed", "Rules"));
    try (var connection = database.openConnection(); var statement = connection.createStatement()) {
      statement.execute("UPDATE session_configuration SET validation_enabled = FALSE");
      statement.execute("ALTER TABLE session_configuration DROP COLUMN streaming_enabled");
    }
    initializer.initialize();
    initializer.initialize();
    var loaded = new JdbcSessionSettingsRepository(database).load("session");
    org.junit.jupiter.api.Assertions.assertNull(loaded.streamingEnabled());
    assertEquals(Boolean.FALSE, loaded.validationEnabled());
  }

  private Database database() {
    return new Database("jdbc:h2:file:" + temporaryDirectory.resolve("schema"), "sa", "");
  }

  private Set<String> storytellerTables(Database database) throws Exception {
    Set<String> tables = new HashSet<>();
    try (Connection connection = database.openConnection();
         ResultSet resultSet = connection.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
      while (resultSet.next()) {
        String table = resultSet.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
        if (EXPECTED_TABLES.contains(table)) {
          tables.add(table);
        }
      }
    }
    return tables;
  }

  private boolean hasColumn(Database database, String table, String column) throws Exception {
    try (Connection connection = database.openConnection();
         ResultSet resultSet = connection.getMetaData().getColumns(null, null, table, column)) {
      return resultSet.next();
    }
  }
}
