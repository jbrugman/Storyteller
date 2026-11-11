package nl.llm.storyteller.api.session;

import nl.llm.storyteller.core.graph.KnowledgeGraphValidator;
import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;
import nl.llm.storyteller.db.ConfigurationPatch;
import nl.llm.storyteller.db.SessionPrompts;
import nl.llm.storyteller.db.SessionSettings;
import nl.llm.storyteller.db.SessionSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

  @Test
  @DisplayName("""
    Given configuration patches that choose a context mode and window,
    When each patch is applied,
    Then the rolling choice, the empty inherit choice and absent fields behave as designed
    """)
  void shouldPersistContextChoiceFromPatch() {
    StubRepository repository = new StubRepository(storedSettings(null));
    SessionSettingsService service = new SessionSettingsService(repository, new KnowledgeGraphValidator());

    service.patchConfiguration(SESSION_ID, null, null, null, false, null,
      false, null, false, null, false, null, false, null, true, "rolling", true, 8192);
    assertTrue(repository.patchContextModeCalled, "A submitted mode asks the repository for an update");
    assertTrue(repository.patchContextWindowCalled, "A submitted window asks the repository for an update");
    assertEquals("rolling", repository.patchedContextMode, "The chosen mode reaches the repository");
    assertEquals(Integer.valueOf(8192), repository.patchedContextWindow, "The chosen window reaches the repository");

    service.patchConfiguration(SESSION_ID, null, null, null, false, null,
      false, null, false, null, false, null, false, null, true, "", true, 0);
    assertTrue(repository.patchContextModeCalled, "An explicit empty mode still asks for an update");
    assertNull(repository.patchedContextMode, "An empty mode restores the inherited choice");
    assertEquals(Integer.valueOf(0), repository.patchedContextWindow, "Zero persists as an explicitly unknown window");

    service.patchConfiguration(SESSION_ID, null, null, null, false, null,
      false, null, false, null, false, null, true, 512);
    assertFalse(repository.patchContextModeCalled, "An absent mode must not touch the stored choice");
    assertFalse(repository.patchContextWindowCalled, "An absent window must not touch the stored choice");
  }

  @Test
  @DisplayName("""
    Given a session that chose rolling mode and its own window,
    When a patch submits only one of the two context settings,
    Then only that setting is updated and the other keeps its stored choice
    """)
  void shouldPatchOneContextSettingWithoutTouchingTheOther() {
    StubRepository repository = new StubRepository(
      new SessionSettings(new SessionPrompts("System", VALID_YAML, "Rules"),
        KnowledgeGraphDocument.empty(), null, null, null, null, null, "rolling", 8192));
    SessionSettingsService service = new SessionSettingsService(repository, new KnowledgeGraphValidator());

    service.patchConfiguration(SESSION_ID, null, null, null, false, null,
      false, null, false, null, false, null, false, null, false, null, true, 16384);
    assertFalse(repository.patchContextModeCalled,
      "A patch carrying only the window must leave the stored mode alone");
    assertTrue(repository.patchContextWindowCalled);
    assertEquals(Integer.valueOf(16384), repository.patchedContextWindow);

    service.patchConfiguration(SESSION_ID, null, null, null, false, null,
      false, null, false, null, false, null, false, null, true, "turns", false, null);
    assertTrue(repository.patchContextModeCalled);
    assertEquals("turns", repository.patchedContextMode);
    assertFalse(repository.patchContextWindowCalled,
      "A patch carrying only the mode must leave the stored window alone");

    service.patchConfiguration(SESSION_ID, null, null, null, false, null,
      false, null, false, null, false, null, false, null, true, null, false, null);
    assertTrue(repository.patchContextModeCalled, "An explicit null still returns the mode to the server default");
    assertNull(repository.patchedContextMode);
    assertFalse(repository.patchContextWindowCalled, "An absent window is not the same as an inherited one");
  }

  @ParameterizedTest
  @CsvSource(nullValues = "NULL", value = {"bogus,8192", "NULL,-1", "NULL,100000001"})
  @DisplayName("""
    Given a context mode or window outside the accepted range,
    When the patch or the full save submits it,
    Then the request is rejected and the stored choices are unchanged
    """)
  void shouldRejectInvalidContextWithoutChangingSettings(String contextMode, Integer contextWindow) {
    StubRepository repository = new StubRepository(storedSettings(null));
    SessionSettingsService service = new SessionSettingsService(repository, new KnowledgeGraphValidator());

    assertThrows(IllegalArgumentException.class, () -> service.patchConfiguration(SESSION_ID, null, null, null,
      false, null, false, null, false, null, false, null, false, null, true, contextMode, true, contextWindow));
    assertFalse(repository.patchContextModeCalled, "A rejected patch must not reach the repository");
    assertFalse(repository.patchContextWindowCalled, "A rejected patch must not reach the repository");
    assertThrows(IllegalArgumentException.class, () -> service.save(SESSION_ID, "System", VALID_YAML, "Rules",
      EMPTY_GRAPH, null, null, null, null, null, contextMode, contextWindow));
    assertEquals(storedSettings(null), repository.settings, "A rejected request leaves the stored choices intact");
  }

  @Test
  @DisplayName("""
    Given a full settings save,
    When it carries a context choice or returns the mode to empty,
    Then the window persists while an empty mode inherits again
    """)
  void shouldStoreContextFromFullSave() {
    StubRepository repository = new StubRepository(storedSettings(null));
    SessionSettingsService service = new SessionSettingsService(repository, new KnowledgeGraphValidator());

    service.save(SESSION_ID, "System", VALID_YAML, "Rules", EMPTY_GRAPH, null, null, null, null, null,
      "rolling", 4096);
    assertEquals("rolling", repository.settings.contextMode());
    assertEquals(Integer.valueOf(4096), repository.settings.contextWindow());

    service.save(SESSION_ID, "System", VALID_YAML, "Rules", EMPTY_GRAPH, null, null, null, null, null, "", 0);
    assertNull(repository.settings.contextMode(), "An empty mode inherits the server's choice again");
    assertEquals(Integer.valueOf(0), repository.settings.contextWindow(),
      "Zero survives a full save as an explicitly unknown window");
  }

  @Test
  @DisplayName("""
    Given a server that defaults to a rolling window,
    When effective context settings are resolved,
    Then sessions inherit it unless they carry their own mode or window
    """)
  void shouldResolveContextFromCoreDefault() {
    SessionSettings inherit = storedSettings(null);
    SessionSettings forcedTurns = new SessionSettings(new SessionPrompts("System", VALID_YAML, "Rules"),
      KnowledgeGraphDocument.empty(), null, null, null, null, null, "turns", 2048);
    SessionSettingsService service = new SessionSettingsService(new StubRepository(inherit),
      new KnowledgeGraphValidator(), false, true, true, "rolling", 8192, 512);

    assertEquals("rolling", service.contextMode(inherit), "A null mode inherits the server's mode");
    assertEquals(8192, service.contextWindow(inherit), "A null window inherits the server's window");
    assertEquals("turns", service.contextMode(forcedTurns), "An explicit mode overrides the server's choice");
    assertEquals(2048, service.contextWindow(forcedTurns), "An explicit window overrides the server's choice");
    assertEquals("rolling", service.defaultContextMode(), "The form needs the server's mode to explain an empty choice");
    assertEquals(8192, service.defaultContextWindow(), "The form needs the server's window to explain an empty choice");
    assertEquals(512, service.responseReserveTokens(), "The reserve is what the server configured");
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
    private boolean patchContextModeCalled;
    private boolean patchContextWindowCalled;
    private String patchedContextMode;
    private Integer patchedContextWindow;

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
    public void patchConfiguration(String sessionId, ConfigurationPatch patch) {
      patchGraphCalled = patch.updateGraph();
      patchedGraph = patch.graphEnabled();
      patchMaxTokensCalled = patch.updateMaxTokens();
      patchedMaxTokens = patch.maxTokens();
      patchContextModeCalled = patch.updateContextMode();
      patchContextWindowCalled = patch.updateContextWindow();
      patchedContextMode = patch.contextMode();
      patchedContextWindow = patch.contextWindow();
    }
  }
}
