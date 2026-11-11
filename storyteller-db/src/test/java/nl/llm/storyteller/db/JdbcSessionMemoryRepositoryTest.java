package nl.llm.storyteller.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSessionMemoryRepositoryTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  @DisplayName("""
    Given a database-backed story with two complete turns,
    When its derived memories are updated from the current snapshot,
    Then their content and cursors should be stored and stale updates should be rejected
    """)
  void shouldStoreDerivedMemorySafely() {
    Database database = new Database("jdbc:h2:file:" + temporaryDirectory.resolve("memory"), "sa", "");
    new SchemaInitializer(database).initialize();
    Instant now = Instant.parse("2026-09-08T20:00:00Z");
    String sessionId = "session-id";
    new JdbcSessionRepository(database).create(
      new SessionRecord(sessionId, "Story", now, now, now, now.plusSeconds(3600), false),
      new SessionPrompts("System", "Fixed", "Rules")
    );
    JdbcStoryRepository stories = new JdbcStoryRepository(database);
    stories.appendTurn(sessionId, "First prompt", "First response", null, now);
    stories.appendTurn(sessionId, "Second prompt", "Second response", null, now);
    JdbcSessionMemoryRepository repository = new JdbcSessionMemoryRepository(database);
    SessionMemory initial = repository.load(sessionId);

    assertTrue(repository.updateSummary(sessionId, initial, "Long history", 2));
    SessionMemory afterSummary = repository.load(sessionId);
    assertTrue(repository.updateRecentSummary(sessionId, afterSummary, "Mid-term history", 2));
    SessionMemory afterRecent = repository.load(sessionId);
    assertTrue(repository.updateCanonicalState(sessionId, afterRecent, "Canonical state", 4));
    assertFalse(repository.updateSummary(sessionId, initial, "Stale history", 4));

    SessionMemory stored = repository.load(sessionId);
    assertEquals("Long history", stored.summary());
    assertEquals("Mid-term history", stored.recentSummary());
    assertEquals("Canonical state", stored.canonicalState());
    assertEquals(2, stored.summaryCursor());
    assertEquals(2, stored.recentSummaryCursor());
    assertEquals(4, stored.canonicalStateCursor());
    assertTrue(stored.messages().isEmpty());
    assertEquals(4, repository.loadForUpdate(sessionId).messages().size());
  }

  @Test
  @DisplayName("""
    Given a session whose turn recorded a request frontier,
    When every memory layer is written from the current snapshot,
    Then the frontier should round-trip unchanged through each layer write
    """)
  void shouldPreserveTheRequestFrontierAcrossMemoryWrites() {
    Database database = new Database("jdbc:h2:file:" + temporaryDirectory.resolve("frontier"), "sa", "");
    new SchemaInitializer(database).initialize();
    Instant now = Instant.parse("2026-11-11T20:00:00Z");
    String sessionId = "frontier-session";
    new JdbcSessionRepository(database).create(
      new SessionRecord(sessionId, "Story", now, now, now, now.plusSeconds(3600), false),
      new SessionPrompts("System", "Fixed", "Rules")
    );
    JdbcStoryRepository stories = new JdbcStoryRepository(database);
    stories.appendTurn(sessionId, "First prompt", "First response", null, now, 0);
    stories.appendTurn(sessionId, "Second prompt", "Second response", null, now, 2);
    JdbcSessionMemoryRepository repository = new JdbcSessionMemoryRepository(database);

    SessionMemory initial = repository.load(sessionId);
    assertEquals(2, initial.contextTargetIndex(), "load exposes the frontier the turn recorded");
    assertEquals(2, repository.loadForUpdate(sessionId).contextTargetIndex(),
      "the compare-and-swap load exposes the same frontier");

    assertTrue(repository.updateSummary(sessionId, initial, "Long history", 2));
    SessionMemory afterSummary = repository.load(sessionId);
    assertTrue(repository.updateRecentSummary(sessionId, afterSummary, "Mid-term history", 4));
    assertTrue(repository.updateCanonicalState(sessionId, repository.load(sessionId), "Canonical state", 4));

    SessionMemory stored = repository.load(sessionId);
    assertEquals(2, stored.summaryCursor());
    assertEquals(4, stored.recentSummaryCursor());
    assertEquals(4, stored.canonicalStateCursor());
    assertEquals(2, stored.contextTargetIndex(),
      "cursor-only layer writes leave the frontier to the turn that records it");
  }
}
