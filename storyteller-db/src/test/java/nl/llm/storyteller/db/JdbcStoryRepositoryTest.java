package nl.llm.storyteller.db;

import nl.llm.storyteller.core.graph.model.Entity;
import nl.llm.storyteller.core.graph.model.EntityId;
import nl.llm.storyteller.core.graph.model.EntityType;
import nl.llm.storyteller.core.graph.model.Fact;
import nl.llm.storyteller.core.graph.model.FactSource;
import nl.llm.storyteller.core.graph.model.FactStatus;
import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;
import nl.llm.storyteller.core.graph.model.Polarity;
import nl.llm.storyteller.core.graph.model.PredicateId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcStoryRepositoryTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  @DisplayName("Given processed memory, when the last turn is undone, then memory cursors should allow its replacement")
  void shouldRewindMemoryCursorsWhenUndoingLastTurn() {
    Database database = new Database("jdbc:h2:file:" + temporaryDirectory.resolve("undo-cursors"), "sa", "");
    new SchemaInitializer(database).initialize();
    JdbcSessionRepository sessionRepository = new JdbcSessionRepository(database);
    JdbcStoryRepository storyRepository = new JdbcStoryRepository(database);
    JdbcSessionMemoryRepository memoryRepository = new JdbcSessionMemoryRepository(database);
    Instant now = Instant.parse("2026-09-09T08:00:00Z");
    String sessionId = "undo-session";
    sessionRepository.create(new SessionRecord(
      sessionId, "Undo story", now, now, now, now.plusSeconds(3600), false
    ), SessionPrompts.empty());
    storyRepository.appendTurn(sessionId, "First prompt", "First response", null, now);
    SessionMemory initial = memoryRepository.loadForUpdate(sessionId);
    assertTrue(memoryRepository.updateSummary(sessionId, initial, "Summary", 2));
    SessionMemory summarized = memoryRepository.loadForUpdate(sessionId);
    assertTrue(memoryRepository.updateRecentSummary(sessionId, summarized, "Recent", 2));
    SessionMemory recent = memoryRepository.loadForUpdate(sessionId);
    assertTrue(memoryRepository.updateCanonicalState(sessionId, recent, "Canonical", 2));

    assertTrue(storyRepository.undoLastTurn(sessionId, now.plusSeconds(1)));
    SessionMemory undone = memoryRepository.loadForUpdate(sessionId);

    assertEquals(0, undone.messages().size());
    assertEquals(0, undone.summaryCursor());
    assertEquals(0, undone.recentSummaryCursor());
    assertEquals(0, undone.canonicalStateCursor());

    storyRepository.appendTurn(sessionId, "Replacement prompt", "Replacement response", null, now.plusSeconds(2));
    SessionMemory replaced = memoryRepository.loadForUpdate(sessionId);
    assertEquals(2, replaced.messages().size());
    assertEquals(0, replaced.canonicalStateCursor());
  }

  @Test
  @DisplayName("Given a processed graph batch, when its last turn is undone, then its graph checkpoint is removed")
  void shouldRewindTurnBasedGraphWhenUndoingLastTurn() {
    Database database = new Database("jdbc:h2:file:" + temporaryDirectory.resolve("undo-graph"), "sa", "");
    new SchemaInitializer(database).initialize();
    JdbcSessionRepository sessionRepository = new JdbcSessionRepository(database);
    JdbcStoryRepository storyRepository = new JdbcStoryRepository(database);
    JdbcSessionBundleRepository bundleRepository = new JdbcSessionBundleRepository(database);
    Instant now = Instant.parse("2026-09-09T08:00:00Z");
    String sessionId = "undo-graph-session";
    sessionRepository.create(new SessionRecord(
      sessionId, "Undo graph", now, now, now, now.plusSeconds(3600), false
    ), SessionPrompts.empty());
    storyRepository.appendTurn(sessionId, "First prompt", "First response", null, now);
    Map<String, Entity> entities = Map.of(
      "alice", new Entity(EntityType.CHARACTER, "Alice", List.of(), FactSource.TURNBASED),
      "paris", new Entity(EntityType.LOCATION, "Paris", List.of(), FactSource.TURNBASED)
    );
    Fact fact = new Fact(
      "alice-lives-paris", new EntityId("alice"), new PredicateId("LIVES"), new EntityId("paris"),
      Polarity.POSITIVE, FactStatus.ACTIVE, FactSource.TURNBASED, 1, false
    );
    bundleRepository.saveKnowledgeGraph(sessionId, new KnowledgeGraphDocument(1, 1, entities, List.of(fact)));

    assertTrue(storyRepository.undoLastTurn(sessionId, now.plusSeconds(1)));
    KnowledgeGraphDocument graph = bundleRepository.loadKnowledgeGraph(sessionId);

    assertEquals(2, graph.revision());
    assertTrue(graph.facts().isEmpty());
  }

  @Test
  @DisplayName("""
    Given a story containing more than five exchanges,
    When the newest and preceding web pages are loaded,
    Then each page should contain only its requested messages in chronological order
    """)
  void shouldLoadBoundedMessagePagesBeforeAnExclusiveIndex() {
    Database database = new Database("jdbc:h2:file:" + temporaryDirectory.resolve("story-pages"), "sa", "");
    new SchemaInitializer(database).initialize();
    JdbcSessionRepository sessionRepository = new JdbcSessionRepository(database);
    JdbcStoryRepository storyRepository = new JdbcStoryRepository(database);
    Instant now = Instant.parse("2026-09-07T08:00:00Z");
    sessionRepository.create(new SessionRecord(
      "paged-session", "Paged story", now, now, now, now.plusSeconds(3600), false
    ), SessionPrompts.empty());
    for (int turn = 0; turn < 7; turn++) {
      storyRepository.appendTurn("paged-session", "Prompt " + turn, "Response " + turn, null, now);
    }

    List<StoryMessageRecord> newest = storyRepository.loadMessagesBefore(
      "paged-session", Integer.MAX_VALUE, 10
    );
    List<StoryMessageRecord> older = storyRepository.loadMessagesBefore(
      "paged-session", newest.getFirst().messageIndex(), 10
    );

    assertEquals(10, newest.size());
    assertEquals(4, newest.getFirst().messageIndex());
    assertEquals(13, newest.getLast().messageIndex());
    assertEquals(List.of(0, 1, 2, 3), older.stream().map(StoryMessageRecord::messageIndex).toList());
  }

  @Test
  @DisplayName("""
    Given a story turn with an image,
    When the turn is stored and loaded,
    Then the image should remain attached to the user message only
    """)
  void shouldStoreImageWithUserMessage() {
    Database database = new Database("jdbc:h2:file:" + temporaryDirectory.resolve("story-image"), "sa", "");
    new SchemaInitializer(database).initialize();
    JdbcSessionRepository sessionRepository = new JdbcSessionRepository(database);
    JdbcStoryRepository storyRepository = new JdbcStoryRepository(database);
    Instant now = Instant.parse("2026-09-08T08:00:00Z");
    sessionRepository.create(new SessionRecord(
      "image-session", "Image story", now, now, now, now.plusSeconds(3600), false
    ), SessionPrompts.empty());
    byte[] content = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    StoryTurnRecord turn = storyRepository.appendTurn(
      "image-session", "Describe this", "An image", new StoryImage("image/png", content), now
    );

    List<StoryMessageRecord> messages = storyRepository.loadMessagesBefore(
      "image-session", Integer.MAX_VALUE, 2
    );
    assertTrue(messages.getFirst().hasImage());
    assertFalse(messages.getLast().hasImage());
    assertEquals(new StoryImage("image/png", content), storyRepository.loadImage(
      "image-session", turn.userMessageIndex()
    ).orElseThrow());
    assertTrue(storyRepository.loadRecentMessages("image-session", 2).getFirst().imageDataUrl()
      .startsWith("data:image/png;base64,"));
  }

  @Test
  @DisplayName("""
    Given a stored story turn,
    When its assistant response is edited,
    Then only the assistant message should be updated
    """)
  void shouldUpdateAssistantMessageOnly() {
    Database database = new Database("jdbc:h2:file:" + temporaryDirectory.resolve("edit-response"), "sa", "");
    new SchemaInitializer(database).initialize();
    JdbcSessionRepository sessionRepository = new JdbcSessionRepository(database);
    JdbcStoryRepository storyRepository = new JdbcStoryRepository(database);
    Instant now = Instant.parse("2026-09-16T08:00:00Z");
    sessionRepository.create(new SessionRecord(
      "edit-session", "Edit story", now, now, now, now.plusSeconds(3600), false
    ), SessionPrompts.empty());
    StoryTurnRecord turn = storyRepository.appendTurn("edit-session", "Original prompt", "Original response", null, now);

    assertTrue(storyRepository.updateAssistantMessage(
      "edit-session", turn.assistantMessageIndex(), "Edited response", now.plusSeconds(1)
    ));
    assertFalse(storyRepository.updateAssistantMessage(
      "edit-session", turn.userMessageIndex(), "Edited prompt", now.plusSeconds(2)
    ));

    List<StoryMessageRecord> messages = storyRepository.loadMessagesBefore(
      "edit-session", Integer.MAX_VALUE, 2
    );
    assertEquals("Original prompt", messages.getFirst().content());
    assertEquals("Edited response", messages.getLast().content());
  }

  @Test
  @DisplayName("""
    Given several persisted story exchanges,
    When selected user-message indexes are loaded as past context,
    Then only complete exchanges owned by that session should be returned chronologically
    """)
  void shouldLoadSelectedPastExchanges() {
    Database database = new Database("jdbc:h2:file:" + temporaryDirectory.resolve("past-exchanges"), "sa", "");
    new SchemaInitializer(database).initialize();
    JdbcSessionRepository sessionRepository = new JdbcSessionRepository(database);
    JdbcStoryRepository storyRepository = new JdbcStoryRepository(database);
    Instant now = Instant.parse("2026-09-08T10:00:00Z");
    sessionRepository.create(new SessionRecord(
      "past-session", "Past story", now, now, now, now.plusSeconds(3600), false
    ), SessionPrompts.empty());
    storyRepository.appendTurn("past-session", "Prompt zero", "Response zero", null, now);
    storyRepository.appendTurn("past-session", "Prompt one", "Response one", null, now);
    storyRepository.appendTurn("past-session", "Prompt two", "Response two", null, now);

    List<PastStoryExchange> exchanges = storyRepository.loadPastExchanges("past-session", List.of(4, 0));

    assertEquals(5, storyRepository.lastMessageIndex("past-session"));
    assertEquals(List.of(0, 4), exchanges.stream().map(PastStoryExchange::messageIndex).toList());
    assertEquals("Prompt zero", exchanges.getFirst().prompt());
    assertEquals("Response two", exchanges.getLast().response());
  }

  @Test
  @DisplayName("""
    Given rolling turns that record the frontier of the request they sent,
    When those turns append, undo back past that frontier, and append again,
    Then the frontier should be raised on the append transaction and clamped back inside the undo
    """)
  void shouldRaiseTheFrontierOnAppendAndClampItOnUndo() {
    Database database = new Database("jdbc:h2:file:" + temporaryDirectory.resolve("undo-frontier"), "sa", "");
    new SchemaInitializer(database).initialize();
    JdbcSessionRepository sessionRepository = new JdbcSessionRepository(database);
    JdbcStoryRepository storyRepository = new JdbcStoryRepository(database);
    JdbcSessionMemoryRepository memoryRepository = new JdbcSessionMemoryRepository(database);
    Instant now = Instant.parse("2026-11-11T08:00:00Z");
    String sessionId = "undo-frontier-session";
    sessionRepository.create(new SessionRecord(
      sessionId, "Undo frontier", now, now, now, now.plusSeconds(3600), false
    ), SessionPrompts.empty());

    storyRepository.appendTurn(sessionId, "First prompt", "First response", null, now, 0);
    assertEquals(0, memoryRepository.loadForUpdate(sessionId).contextTargetIndex(),
      "A turn that sent its whole context leaves no frontier behind");

    storyRepository.appendTurn(sessionId, "Second prompt", "Second response", null, now, 2);
    assertEquals(2, memoryRepository.loadForUpdate(sessionId).contextTargetIndex());

    storyRepository.appendTurn(sessionId, "Third prompt", "Third response", null, now, 4);
    assertEquals(4, memoryRepository.loadForUpdate(sessionId).contextTargetIndex(),
      "A frontier that only moves forward raises the stored value in the append transaction");

    storyRepository.appendTurn(sessionId, "Fourth prompt", "Fourth response", null, now, 2);
    assertEquals(4, memoryRepository.loadForUpdate(sessionId).contextTargetIndex(),
      "A wider window never pulls the frontier back on a turn; only an undo does");

    assertTrue(storyRepository.undoLastTurn(sessionId, now.plusSeconds(1)));
    assertEquals(4, memoryRepository.loadForUpdate(sessionId).contextTargetIndex(),
      "The frontier never passed the story that survives this undo, so it stays");

    assertTrue(storyRepository.undoLastTurn(sessionId, now.plusSeconds(2)));
    assertEquals(4, memoryRepository.loadForUpdate(sessionId).contextTargetIndex(),
      "A frontier that stops where the surviving story ends still claims nothing that was removed");

    assertTrue(storyRepository.undoLastTurn(sessionId, now.plusSeconds(3)));
    SessionMemory undone = memoryRepository.loadForUpdate(sessionId);
    assertEquals(2, undone.messages().size());
    assertEquals(2, undone.contextTargetIndex(),
      "The undo clamps the frontier to the messages that remain, so it cannot claim a removed turn");

    storyRepository.appendTurn(sessionId, "Replacement prompt", "Replacement response", null, now.plusSeconds(4), 0);
    assertEquals(2, memoryRepository.loadForUpdate(sessionId).contextTargetIndex(),
      "A replacement turn that sent its whole context leaves the clamped frontier alone");

    storyRepository.appendTurn(sessionId, "Narrow prompt", "Narrow response", null, now.plusSeconds(5), 4);
    assertEquals(4, memoryRepository.loadForUpdate(sessionId).contextTargetIndex(),
      "The clamp is not sticky: a later rolling turn raises the frontier again");
  }
}
