package nl.llm.storyteller.api.session;

import nl.llm.storyteller.core.graph.KnowledgeGraphValidator;
import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;
import nl.llm.storyteller.db.SessionPrompts;
import nl.llm.storyteller.db.SessionSettings;
import nl.llm.storyteller.db.SessionSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSettingsServiceTest {
  private static final String SESSION_ID = "session-id";
  private static final String EMPTY_GRAPH = """
    {"schemaVersion":1,"revision":0,"entities":{},"facts":[]}
    """;
  private static final String VALID_YAML = """
    fixed_protagonists:
      Valerie:
        role: protagonist
    """;

  @Test
  @DisplayName("""
    Given valid fixed-protagonist YAML,
    When the session prompts are saved,
    Then all normalized prompts should be persisted
    """)
  void shouldSaveValidPrompts() {
    StubRepository repository = new StubRepository(new SessionSettings(
      new SessionPrompts("Old system", VALID_YAML, "Old rules"),
      KnowledgeGraphDocument.empty()
    ));
    SessionSettingsService service = new SessionSettingsService(repository, new KnowledgeGraphValidator());

    service.save(SESSION_ID, " New system ", VALID_YAML, " New rules ", EMPTY_GRAPH, null);

    assertEquals(new SessionSettings(
      new SessionPrompts("New system", VALID_YAML.strip(), "New rules"),
      KnowledgeGraphDocument.empty()
    ), repository.settings);
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "fixed_protagonists:\n  Valerie:\n    role: [protagonist",
    "protagonists:\n  Valerie:\n    role: protagonist"
  })
  @DisplayName("""
    Given invalid fixed-protagonist YAML,
    When the session prompts are saved,
    Then the error should identify a source position and leave the stored prompts unchanged
    """)
  void shouldRejectInvalidYamlWithoutSaving(String yaml) {
    SessionPrompts stored = new SessionPrompts("Old system", VALID_YAML, "Old rules");
    SessionSettings original = new SessionSettings(stored, KnowledgeGraphDocument.empty());
    StubRepository repository = new StubRepository(original);
    SessionSettingsService service = new SessionSettingsService(repository, new KnowledgeGraphValidator());

    InvalidFixedProtagonistsException error = assertThrows(
      InvalidFixedProtagonistsException.class,
      () -> service.save(SESSION_ID, "New system", yaml, "New rules", EMPTY_GRAPH, null)
    );

    assertTrue(error.line() > 0);
    assertTrue(error.column() > 0);
    assertEquals(original, repository.settings);
  }

  @Test
  @DisplayName("""
    Given invalid knowledge-graph JSON,
    When the session settings are saved,
    Then the graph and prompts should remain unchanged
    """)
  void shouldRejectInvalidKnowledgeGraphWithoutSaving() {
    SessionSettings original = new SessionSettings(
      new SessionPrompts("Old system", VALID_YAML, "Old rules"),
      KnowledgeGraphDocument.empty()
    );
    StubRepository repository = new StubRepository(original);
    SessionSettingsService service = new SessionSettingsService(repository, new KnowledgeGraphValidator());

    assertThrows(
      InvalidKnowledgeGraphException.class,
      () -> service.save(SESSION_ID, "New system", VALID_YAML, "New rules", "{invalid", null)
    );

    assertEquals(original, repository.settings);
  }

  @Test
  @DisplayName("""
    Given a stored session with no explicit knowledge-graph choice,
    When the effective knowledge-graph use is resolved,
    Then it inherits the core default while an explicit per-session choice wins
    """)
  void shouldResolveKnowledgeGraphUseFromCoreDefault() {
    SessionSettings inherit = new SessionSettings(
      new SessionPrompts("s", VALID_YAML, "r"), KnowledgeGraphDocument.empty());
    SessionSettings forcedOff = new SessionSettings(
      new SessionPrompts("s", VALID_YAML, "r"), KnowledgeGraphDocument.empty(), null, null, null, false);
    SessionSettingsService inheritsDisabled = new SessionSettingsService(
      new StubRepository(inherit), new KnowledgeGraphValidator(), false, true, false);
    SessionSettingsService inheritsEnabled = new SessionSettingsService(
      new StubRepository(inherit), new KnowledgeGraphValidator(), false, true, true);

    assertFalse(inheritsDisabled.graphEnabled(inherit), "A null choice inherits a disabled core default");
    assertTrue(inheritsEnabled.graphEnabled(inherit), "A null choice inherits an enabled core default");
    assertFalse(inheritsEnabled.graphEnabled(forcedOff), "An explicit per-session off overrides an enabled core default");
  }

  @Test
  @DisplayName("""
    Given a configuration patch that disables the knowledge graph for one session,
    When the patch is applied,
    Then the effective false choice is persisted through the repository
    """)
  void shouldPersistKnowledgeGraphChoiceFromPatch() {
    StubRepository repository = new StubRepository(new SessionSettings(
      new SessionPrompts("s", VALID_YAML, "r"), KnowledgeGraphDocument.empty()));
    SessionSettingsService service = new SessionSettingsService(repository, new KnowledgeGraphValidator());

    service.patchConfiguration(SESSION_ID, null, null, null, false, null,
      false, null, false, null, true, false);

    assertTrue(repository.patchGraphCalled);
    assertEquals(Boolean.FALSE, repository.patchedGraph);
  }

  private static final class StubRepository implements SessionSettingsRepository {
    private SessionSettings settings;
    private boolean patchGraphCalled;
    private Boolean patchedGraph;

    private StubRepository(SessionSettings settings) {
      this.settings = settings;
    }

    @Override
    public SessionSettings load(String sessionId) {
      return settings;
    }

    @Override
    public void save(String sessionId, SessionSettings settings) {
      this.settings = settings;
    }

    @Override
    public void setTemperature(String sessionId, Double temperature) {
      // no-op for tests
    }

    @Override
    public void patchConfiguration(String sessionId, java.util.Map<String, String> prompts,
                                   boolean updateTemperature, Double temperature) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void patchConfiguration(String sessionId, java.util.Map<String, String> prompts,
      boolean updateTemperature, Double temperature, boolean updateGeneration, Boolean streamingEnabled,
      Boolean validationEnabled, boolean updateGraph, Boolean graphEnabled) {
      patchGraphCalled = updateGraph;
      patchedGraph = graphEnabled;
    }
  }
}
