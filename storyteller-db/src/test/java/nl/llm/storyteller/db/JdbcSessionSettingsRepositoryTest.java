package nl.llm.storyteller.db;

import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcSessionSettingsRepositoryTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  @DisplayName("Configuration patches roll back all prompt changes when a later update fails")
  void shouldRollBackConfigurationPatchOnFailure() {
    Database database = new Database("jdbc:h2:file:" + temporaryDirectory.resolve("patch"), "sa", "");
    new SchemaInitializer(database).initialize();
    Instant now = Instant.parse("2026-09-08T20:00:00Z");
    new JdbcSessionRepository(database).create(
      new SessionRecord("session-id", "Story", now, now, now, now.plusSeconds(3600), false),
      new SessionPrompts("System", "Fixed", "Rules")
    );
    JdbcSessionSettingsRepository repository = new JdbcSessionSettingsRepository(database);
    SessionSettings original = repository.load("session-id");
    var patch = new LinkedHashMap<String, String>();
    patch.put(SessionPrompts.SYSTEM_PROMPT_NAME, "Changed");
    patch.put("missing-prompt", "Will fail");

    assertThrows(DatabaseException.class, () -> repository.patchConfiguration("session-id", patch, true, 0.2));

    assertEquals(original, repository.load("session-id"));
  }

  @Test
  @DisplayName("""
    Given a database-backed story session,
    When its editable prompts and knowledge graph are saved,
    Then all settings should be loaded from the same session
    """)
  void shouldSaveAndLoadCompleteSessionSettings() {
    Database database = new Database(
      "jdbc:h2:file:" + temporaryDirectory.resolve("settings"),
      "sa",
      ""
    );
    new SchemaInitializer(database).initialize();
    Instant now = Instant.parse("2026-09-08T20:00:00Z");
    SessionPrompts initialPrompts = new SessionPrompts("System", "Fixed", "Rules");
    new JdbcSessionRepository(database).create(
      new SessionRecord("session-id", "Story", now, now, now, now.plusSeconds(3600), false),
      initialPrompts
    );
    JdbcSessionSettingsRepository repository = new JdbcSessionSettingsRepository(database);
    JdbcSessionMemoryRepository memoryRepository = new JdbcSessionMemoryRepository(database);
    SessionMemory originalMemory = memoryRepository.load("session-id");
    memoryRepository.updateCanonicalState("session-id", originalMemory, "Generated canonical state", 0);
    SessionSettings updated = new SessionSettings(
      new SessionPrompts("New system", "New fixed", "New rules"),
      KnowledgeGraphDocument.empty()
    );

    repository.save("session-id", updated);

    assertEquals(updated, repository.load("session-id"));
    assertEquals("Generated canonical state", memoryRepository.load("session-id").canonicalState());
  }

  @Test
  @DisplayName("""
    Given session generation choices saved in a file database,
    When another repository opens the database and patches unrelated settings,
    Then streaming and validation choices remain persisted and isolated per session
    """)
  void shouldPersistGenerationAcrossRepositoryReload() {
    String url = "jdbc:h2:file:" + temporaryDirectory.resolve("generation");
    Database database = new Database(url, "sa", "");
    new SchemaInitializer(database).initialize();
    Instant now = Instant.now();
    var sessions = new JdbcSessionRepository(database);
    for (String id : java.util.List.of("first", "second")) {
      sessions.create(new SessionRecord(id, "Story", now, now, now, now.plusSeconds(3600), false),
        new SessionPrompts("System", "Fixed", "Rules"));
    }
    var repository = new JdbcSessionSettingsRepository(database);
    repository.patchConfiguration("first", java.util.Map.of(), false, null, true, true, false);

    var reloaded = new JdbcSessionSettingsRepository(new Database(url, "sa", ""));
    reloaded.patchConfiguration("first", java.util.Map.of(), true, 0.2);

    assertEquals(Boolean.TRUE, reloaded.load("first").streamingEnabled());
    assertEquals(Boolean.FALSE, reloaded.load("first").validationEnabled());
    org.junit.jupiter.api.Assertions.assertNull(reloaded.load("second").streamingEnabled());
    org.junit.jupiter.api.Assertions.assertNull(reloaded.load("second").validationEnabled());
    assertThrows(IllegalArgumentException.class, () -> reloaded.patchConfiguration(
      "first", java.util.Map.of(), false, null, true, true, true));
    assertEquals(Boolean.FALSE, reloaded.load("first").validationEnabled());
  }

  @Test
  @DisplayName("""
    Given sessions whose knowledge graph use is toggled through a configuration patch,
    When another repository opens the same database,
    Then the tri-state choice persists, an unrelated patch leaves it, and an absent choice inherits
    """)
  void shouldPersistKnowledgeGraphToggleAcrossRepositoryReload() {
    String url = "jdbc:h2:file:" + temporaryDirectory.resolve("graph-toggle");
    Database database = new Database(url, "sa", "");
    new SchemaInitializer(database).initialize();
    Instant now = Instant.now();
    var sessions = new JdbcSessionRepository(database);
    for (String id : java.util.List.of("on", "off", "inherit")) {
      sessions.create(new SessionRecord(id, "Story", now, now, now, now.plusSeconds(3600), false),
        new SessionPrompts("System", "Fixed", "Rules"));
    }
    var repository = new JdbcSessionSettingsRepository(database);
    org.junit.jupiter.api.Assertions.assertNull(repository.load("on").graphEnabled(), "A fresh session inherits the core default");
    repository.patchConfiguration("on", java.util.Map.of(), false, null, false, null, null, true, true);
    repository.patchConfiguration("off", java.util.Map.of(), false, null, false, null, null, true, false);

    var reloaded = new JdbcSessionSettingsRepository(new Database(url, "sa", ""));
    reloaded.patchConfiguration("on", java.util.Map.of(), true, 0.5);

    assertEquals(Boolean.TRUE, reloaded.load("on").graphEnabled(), "An explicit on choice survives an unrelated temperature patch");
    assertEquals(Boolean.FALSE, reloaded.load("off").graphEnabled());
    org.junit.jupiter.api.Assertions.assertNull(reloaded.load("inherit").graphEnabled(), "An untouched session still inherits");
  }

  @Test
  @DisplayName("""
    Given sessions whose response limit is patched or saved,
    When another repository opens the same database,
    Then the cap, the explicit limit-free choice and the inherited choice persist
    """)
  void shouldPersistMaxTokensAcrossRepositoryReload() {
    String url = "jdbc:h2:file:" + temporaryDirectory.resolve("max-tokens");
    Database database = new Database(url, "sa", "");
    new SchemaInitializer(database).initialize();
    Instant now = Instant.now();
    var sessions = new JdbcSessionRepository(database);
    for (String id : java.util.List.of("capped", "unlimited", "inherit")) {
      sessions.create(new SessionRecord(id, "Story", now, now, now, now.plusSeconds(3600), false),
        new SessionPrompts("System", "Fixed", "Rules"));
    }
    var repository = new JdbcSessionSettingsRepository(database);
    org.junit.jupiter.api.Assertions.assertNull(repository.load("capped").maxTokens(),
      "A fresh session inherits the server default");
    repository.patchConfiguration("capped", java.util.Map.of(), false, null, false, null, null, false, null,
      true, 512);
    repository.patchConfiguration("unlimited", java.util.Map.of(), false, null, false, null, null, false, null,
      true, 0);

    var reloaded = new JdbcSessionSettingsRepository(new Database(url, "sa", ""));
    reloaded.patchConfiguration("capped", java.util.Map.of(), true, 0.5);

    assertEquals(Integer.valueOf(512), reloaded.load("capped").maxTokens(),
      "An unrelated temperature patch keeps the stored cap");
    assertEquals(Integer.valueOf(0), reloaded.load("unlimited").maxTokens(),
      "Zero persists as an explicit limit-free choice instead of becoming null");
    org.junit.jupiter.api.Assertions.assertNull(reloaded.load("inherit").maxTokens(),
      "An untouched session still inherits");

    reloaded.save("capped", new SessionSettings(reloaded.load("capped").prompts(),
      KnowledgeGraphDocument.empty(), null, null, null, null, 4096));
    assertEquals(Integer.valueOf(4096), reloaded.load("capped").maxTokens(),
      "A full save writes the response limit");

    reloaded.patchConfiguration("capped", java.util.Map.of(), false, null, false, null, null, false, null,
      true, null);
    org.junit.jupiter.api.Assertions.assertNull(reloaded.load("capped").maxTokens(),
      "An explicit null returns the session to the inherited default");
  }
}
