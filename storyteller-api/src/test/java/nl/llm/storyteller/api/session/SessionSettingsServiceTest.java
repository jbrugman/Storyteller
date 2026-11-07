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
import static org.junit.jupiter.api.Assertions.assertNull;
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
      false, null, false, null, true, false, false, null);

    assertTrue(repository.patchGraphCalled);
    assertEquals(Boolean.FALSE, repository.patchedGraph);
  }

  @Test
  @DisplayName("""
    Given configuration patches that set a per-session response limit,
    When each patch is applied,
    Then the capped, unlimited and inherited choices reach the repository
    """)
  void shouldPersistMaxTokensChoiceFromPatch() {
    StubRepository repository = new StubRepository(storedSettings(null));
    SessionSettingsService service = new SessionSettingsService(repository, new KnowledgeGraphValidator());

    service.patchConfiguration(SESSION_ID, null, null, null, false, null,
      false, null, false, null, false, null, true, 512);
    assertTrue(repository.patchMaxTokensCalled, "An explicit limit asks the repository for an update");
    assertEquals(Integer.valueOf(512), repository.patchedMaxTokens, "The cap reaches the repository");

    service.patchConfiguration(SESSION_ID, null, null, null, false, null,
      false, null, false, null, false, null, true, 0);
    assertEquals(Integer.valueOf(0), repository.patchedMaxTokens, "Zero is stored as an explicit limit-free choice");

    service.patchConfiguration(SESSION_ID, null, null, null, false, null,
      false, null, false, null, false, null, true, null);
    assertTrue(repository.patchMaxTokensCalled, "An explicit null still asks for an update");
    assertNull(repository.patchedMaxTokens, "Null restores the inherited choice");

    service.patchConfiguration(SESSION_ID, null, null, null, false, null,
      false, null, false, null, false, null, false, 512);
    assertFalse(repository.patchMaxTokensCalled, "An absent field must not touch the stored choice");
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 1_000_001})
  @DisplayName("""
    Given a response limit outside the accepted range,
    When the patch or the full save submits it,
    Then the request is rejected and the stored choice is unchanged
    """)
  void shouldRejectInvalidMaxTokensWithoutChangingSettings(int maxTokens) {
    StubRepository repository = new StubRepository(storedSettings(256));
    SessionSettingsService service = new SessionSettingsService(repository, new KnowledgeGraphValidator());

    assertThrows(IllegalArgumentException.class, () -> service.patchConfiguration(SESSION_ID, null, null, null,
      false, null, false, null, false, null, false, null, true, maxTokens));
    assertFalse(repository.patchMaxTokensCalled, "A rejected patch must not reach the repository");
    assertThrows(IllegalArgumentException.class, () -> service.save(SESSION_ID, "System", VALID_YAML, "Rules",
      EMPTY_GRAPH, null, null, null, null, maxTokens));
    assertEquals(storedSettings(256), repository.settings, "A rejected request leaves the stored limit intact");
  }

  @Test
  @DisplayName("""
    Given a full settings save,
    When it carries a response limit or omits one,
    Then the limit is stored and an untouched save keeps the previous choice
    """)
  void shouldStoreMaxTokensFromFullSave() {
    StubRepository repository = new StubRepository(storedSettings(256));
    SessionSettingsService service = new SessionSettingsService(repository, new KnowledgeGraphValidator());

    service.save(SESSION_ID, "System", VALID_YAML, "Rules", EMPTY_GRAPH, null);
    assertEquals(Integer.valueOf(256), repository.settings.maxTokens(),
      "A save without the field keeps the stored limit");

    service.save(SESSION_ID, "System", VALID_YAML, "Rules", EMPTY_GRAPH, null, null, null, null, 1024);
    assertEquals(Integer.valueOf(1024), repository.settings.maxTokens(),
      "A save carrying a limit replaces the stored choice");

    service.save(SESSION_ID, "System", VALID_YAML, "Rules", EMPTY_GRAPH, null, null, null, null, 0);
    assertEquals(Integer.valueOf(0), repository.settings.maxTokens(),
      "Zero survives a full save as an explicit limit-free choice");
  }

  private static SessionSettings storedSettings(Integer maxTokens) {
    return new SessionSettings(new SessionPrompts("System", VALID_YAML, "Rules"),
      KnowledgeGraphDocument.empty(), null, null, null, null, maxTokens);
  }

  private static final class StubRepository implements SessionSettingsRepository {
    private SessionSettings settings;
    private boolean patchGraphCalled;
    private Boolean patchedGraph;
    private boolean patchMaxTokensCalled;
    private Integer patchedMaxTokens;

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
      patchConfiguration(sessionId, prompts, updateTemperature, temperature, updateGeneration, streamingEnabled,
        validationEnabled, updateGraph, graphEnabled, false, null);
    }

    @Override
    public void patchConfiguration(String sessionId, java.util.Map<String, String> prompts,
      boolean updateTemperature, Double temperature, boolean updateGeneration, Boolean streamingEnabled,
      Boolean validationEnabled, boolean updateGraph, Boolean graphEnabled, boolean updateMaxTokens,
      Integer maxTokens) {
      patchGraphCalled = updateGraph;
      patchedGraph = graphEnabled;
      patchMaxTokensCalled = updateMaxTokens;
      patchedMaxTokens = maxTokens;
    }
  }
}
