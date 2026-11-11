package nl.llm.storyteller.db;

import nl.llm.storyteller.core.model.HistoryState;
import nl.llm.storyteller.core.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcApplicationStorageTest {
  private static final String SESSION_ID = "cli-session";

  @TempDir
  Path temporaryDirectory;

  private Database database;

  @BeforeEach
  void createDatabaseSession() {
    database = new Database("jdbc:h2:file:" + temporaryDirectory.resolve("cli-storage"), "sa", "");
    new SchemaInitializer(database).initialize();
    Instant now = Instant.parse("2026-09-08T12:00:00Z");
    new JdbcSessionRepository(database).create(new SessionRecord(
      SESSION_ID, "CLI story", now, now, now, now.plusSeconds(3600), true
    ), SessionPrompts.empty());
  }

  @Test
  @DisplayName("""
    Given a CLI session stored in H2,
    When story turns, cursors, and derived memory are updated,
    Then the JDBC application storage should return the complete current state
    """)
  void shouldPersistCliStoryAndDerivedMemory() {
    JdbcStoryHistory history = new JdbcStoryHistory(database, SESSION_ID);
    JdbcTextMemory summary = new JdbcTextMemory(database, SESSION_ID, JdbcTextMemory.Type.SUMMARY);

    history.appendTurn("First prompt", "First response");
    history.appendTurn("Second prompt", "Second response");
    history.markSummarized(2);
    summary.save("Persisted summary");

    assertEquals(List.of(
      new Message("user", "First prompt"),
      new Message("assistant", "First response"),
      new Message("user", "Second prompt"),
      new Message("assistant", "Second response")
    ), history.load().messages());
    assertEquals(2, history.load().summaryCursor());
    assertEquals("Persisted summary", summary.load());
    assertEquals("Second prompt", history.loadLastTurn().userInput());
  }

  @Test
  @DisplayName("""
    Given imported history ending in an incomplete turn,
    When the last complete user turn is inspected and removed,
    Then JDBC history should preserve the file-backed history semantics
    """)
  void shouldHandleIncompleteImportedTurnsLikeFileHistory() {
    JdbcStoryHistory history = new JdbcStoryHistory(database, SESSION_ID);
    history.save(new HistoryState(List.of(
      new Message("user", "Complete prompt"),
      new Message("assistant", "Complete response"),
      new Message("user", "Incomplete prompt")
    ), 3, 3, 3));

    String removed = history.removeLastTurn();

    assertEquals("Incomplete prompt", removed);
    assertEquals(List.of(
      new Message("user", "Complete prompt"),
      new Message("assistant", "Complete response")
    ), history.load().messages());
    assertEquals(2, history.load().summaryCursor());
  }

  @Test
  @DisplayName("""
    Given CLI turns that record the frontier their request actually left behind,
    When later turns, memory bookkeeping, and two undos touch the same session,
    Then JDBC history should grow the frontier on a turn and clamp it back on an undo
    """)
  void shouldKeepTheRequestFrontierWithinHistorySemantics() {
    JdbcStoryHistory history = new JdbcStoryHistory(database, SESSION_ID);

    history.appendTurn("First prompt", "First response", 0);
    assertEquals(0, history.load().contextTargetIndex(),
      "A turn without a frontier leaves it where it was");

    history.appendTurn("Second prompt", "Second response", 2);
    assertEquals(2, history.load().contextTargetIndex(), "A turn raises the frontier to its own boundary");

    history.appendTurn("Third prompt", "Third response", 4);
    assertEquals(4, history.load().contextTargetIndex());

    history.markSummarized(2);
    history.markRecentSummarized(2);
    assertEquals(4, history.load().contextTargetIndex(),
      "Derived-memory bookkeeping never rewrites the frontier");

    assertEquals("Third prompt", history.removeLastTurn());
    assertEquals(4, history.load().contextTargetIndex(),
      "The frontier never passed the story that survives the first undo, so it stays");

    assertEquals("Second prompt", history.removeLastTurn());
    assertEquals(2, history.load().contextTargetIndex(),
      "The second undo pulls the frontier back to the story that remains, so it cannot claim a removed turn");

    history.save(new HistoryState(List.of(
      new Message("user", "Imported prompt"),
      new Message("assistant", "Imported response")
    ), 0, 0, 0, 1));
    assertEquals(1, history.load().contextTargetIndex(),
      "Only the full state replace writes a frontier verbatim, because it replaces the whole state");
  }
}
