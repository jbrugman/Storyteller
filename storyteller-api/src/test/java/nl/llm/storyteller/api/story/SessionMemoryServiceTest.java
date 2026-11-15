package nl.llm.storyteller.api.story;

import nl.llm.storyteller.core.config.AppConfig;
import nl.llm.storyteller.core.config.AppConfigLoader;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.service.ChatClient;
import nl.llm.storyteller.db.SessionMemory;
import nl.llm.storyteller.db.SessionMemoryRepository;
import nl.llm.storyteller.db.SessionPrompts;
import nl.llm.storyteller.db.SessionSettings;
import nl.llm.storyteller.db.SessionSettingsRepository;
import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMemoryServiceTest {
  @Test
  @DisplayName("""
    Given enough database-backed story turns for every memory window,
    When the session memory refresh runs,
    Then it should persist long-term, mid-term, and canonical memory with their cursors
    """)
  void shouldRefreshAllDerivedMemory() throws Exception {
    List<Message> messages = new ArrayList<>();
    for (int turn = 1; turn <= 18; turn++) {
      messages.add(new Message("user", "Prompt " + turn));
      messages.add(new Message("assistant", "Response " + turn));
    }
    MemoryRepository memoryRepository = new MemoryRepository(new SessionMemory("", "", "", 0, 0, 0, messages));
    SettingsRepository settingsRepository = new SettingsRepository();
    SequencedChatClient client = new SequencedChatClient(List.of("Long history", "Mid-term history", "Canonical"));

    try (SessionMemoryService service = new SessionMemoryService(
      memoryRepository, settingsRepository, AppConfig.load(), client
    )) {
      service.refresh("session-id");
    }

    SessionMemory stored = memoryRepository.load("session-id");
    assertEquals("Long history", stored.summary());
    assertEquals("Mid-term history", stored.recentSummary());
    assertEquals("Canonical", stored.canonicalState());
    assertEquals(12, stored.summaryCursor());
    assertEquals(32, stored.recentSummaryCursor());
    assertEquals(36, stored.canonicalStateCursor());
    assertEquals(3, client.requests.size());
    assertTrue(client.requests.stream().allMatch(request -> request.getFirst().content().contains("Edited fixed")));
  }

  @Test
  @DisplayName("""
    Given a session with mid-term memory,
    When the mid-term memory is reset,
    Then the recent summary and cursor should be cleared
    """)
  void shouldResetMidTermMemory() throws Exception {
    List<Message> messages = new ArrayList<>();
    for (int turn = 1; turn <= 18; turn++) {
      messages.add(new Message("user", "Prompt " + turn));
      messages.add(new Message("assistant", "Response " + turn));
    }
    SessionMemory initialMemory = new SessionMemory(
      "Existing mid-term summary", "Existing recent summary", "Existing canonical",
      10, 32, 36, messages
    );
    MemoryRepository memoryRepository = new MemoryRepository(initialMemory);
    SettingsRepository settingsRepository = new SettingsRepository();
    String sessionId = "session-id";

    try (SessionMemoryService service = new SessionMemoryService(
      memoryRepository, settingsRepository, AppConfig.load(), new SequencedChatClient(List.of())
    )) {
      service.resetMidTermMemory(sessionId);
    }

    SessionMemory stored = memoryRepository.load(sessionId);
    assertEquals("Existing mid-term summary", stored.summary());
    assertEquals(10, stored.summaryCursor());
    assertEquals("", stored.recentSummary());
    assertEquals(0, stored.recentSummaryCursor());
    assertEquals("Existing canonical", stored.canonicalState());
    assertEquals(36, stored.canonicalStateCursor());
  }

  @Test
  @DisplayName("""
    Given a session with long-term memory,
    When the long-term memory is reset,
    Then the summary and cursor should be cleared
    """)
  void shouldResetLongTermMemory() throws Exception {
    List<Message> messages = new ArrayList<>();
    for (int turn = 1; turn <= 18; turn++) {
      messages.add(new Message("user", "Prompt " + turn));
      messages.add(new Message("assistant", "Response " + turn));
    }
    SessionMemory initialMemory = new SessionMemory(
      "Existing mid-term summary", "Existing recent summary", "Existing canonical",
      10, 32, 36, messages
    );
    MemoryRepository memoryRepository = new MemoryRepository(initialMemory);
    SettingsRepository settingsRepository = new SettingsRepository();
    String sessionId = "session-id";

    try (SessionMemoryService service = new SessionMemoryService(
      memoryRepository, settingsRepository, AppConfig.load(), new SequencedChatClient(List.of())
    )) {
      service.resetLongTermMemory(sessionId);
    }

    SessionMemory stored = memoryRepository.load(sessionId);
    assertEquals("", stored.summary());
    assertEquals(0, stored.summaryCursor());
    assertEquals("Existing recent summary", stored.recentSummary());
    assertEquals(32, stored.recentSummaryCursor());
    assertEquals("Existing canonical", stored.canonicalState());
    assertEquals(36, stored.canonicalStateCursor());
  }

  @Test
  @DisplayName("""
    Given a session with canonical state,
    When the canonical state is reset,
    Then the canonical state and cursor should be cleared
    """)
  void shouldResetCanonicalState() throws Exception {
    List<Message> messages = new ArrayList<>();
    for (int turn = 1; turn <= 18; turn++) {
      messages.add(new Message("user", "Prompt " + turn));
      messages.add(new Message("assistant", "Response " + turn));
    }
    SessionMemory initialMemory = new SessionMemory(
      "Existing mid-term summary", "Existing recent summary", "Existing canonical",
      10, 32, 36, messages
    );
    MemoryRepository memoryRepository = new MemoryRepository(initialMemory);
    SettingsRepository settingsRepository = new SettingsRepository();
    String sessionId = "session-id";

    try (SessionMemoryService service = new SessionMemoryService(
      memoryRepository, settingsRepository, AppConfig.load(), new SequencedChatClient(List.of())
    )) {
      service.resetCanonicalState(sessionId);
    }

    SessionMemory stored = memoryRepository.load(sessionId);
    assertEquals("Existing mid-term summary", stored.summary());
    assertEquals(10, stored.summaryCursor());
    assertEquals("Existing recent summary", stored.recentSummary());
    assertEquals(32, stored.recentSummaryCursor());
    assertEquals("", stored.canonicalState());
    assertEquals(0, stored.canonicalStateCursor());
  }

  @Test
  @DisplayName("""
    Given a rolling session whose turns recorded a request frontier below the newest turns,
    When the session memory refreshes twice,
    Then the mid-term layer should condense toward the frontier rather than the story size, long-term
        memory should follow one pass behind it, and the frontier should survive every memory write
    """)
  void shouldCondenseMidTermTowardTheFrontierAndChainLongTermBehindIt() throws Exception {
    List<Message> messages = new ArrayList<>();
    for (int turn = 1; turn <= 20; turn++) {
      messages.add(new Message("user", "Prompt " + turn));
      messages.add(new Message("assistant", "Response " + turn));
    }
    // Frontier at the boundary after turn 9 (message index 18); the newest turns stay verbatim.
    // The turns-mode rule would have condensed toward size - 2*chat.maxRecentTurns = 36 instead.
    SessionMemory initialMemory = new SessionMemory("", "", "", 0, 0, 0, 18, messages);
    MemoryRepository memoryRepository = new MemoryRepository(initialMemory);
    SettingsRepository settingsRepository = new SettingsRepository("rolling", 8192);
    SequencedChatClient client = new SequencedChatClient(List.of("Mid-term history", "Canonical", "Long history"));

    try (SessionMemoryService service = new SessionMemoryService(
      memoryRepository, settingsRepository, AppConfig.load(), client
    )) {
      service.refresh("session-id");

      SessionMemory afterFirst = memoryRepository.load("session-id");
      assertEquals(18, afterFirst.recentSummaryCursor());
      assertEquals(0, afterFirst.summaryCursor());
      assertEquals(40, afterFirst.canonicalStateCursor());
      assertEquals("Mid-term history", afterFirst.recentSummary());
      assertEquals(18, afterFirst.contextTargetIndex());

      service.refresh("session-id");
    }

    SessionMemory stored = memoryRepository.load("session-id");
    assertEquals(18, stored.summaryCursor());
    assertEquals(18, stored.recentSummaryCursor());
    assertEquals(40, stored.canonicalStateCursor());
    assertEquals("Long history", stored.summary());
    assertEquals(18, stored.contextTargetIndex());
    assertEquals(3, client.requests.size());
  }

  @Test
  @DisplayName("""
    Given a rolling session whose recorded frontier is an odd index inside the stored story,
    When the session memory refreshes,
    Then the mid-term boundary should round down to a whole turn while the stored frontier is kept intact
    """)
  void shouldRoundTheRollingFrontierDownToAWholeTurn() throws Exception {
    List<Message> messages = new ArrayList<>();
    for (int turn = 1; turn <= 19; turn++) {
      messages.add(new Message("user", "Prompt " + turn));
      messages.add(new Message("assistant", "Response " + turn));
    }
    // Frontier 37 sits inside the 38 stored messages on an odd index: the cutoff rounds down to 36 so
    // a turn is never split, while the durable frontier itself is never rewritten by a memory pass.
    SessionMemory initialMemory = new SessionMemory("", "", "", 0, 0, 0, 37, messages);
    MemoryRepository memoryRepository = new MemoryRepository(initialMemory);
    SettingsRepository settingsRepository = new SettingsRepository("rolling", 8192);
    SequencedChatClient client = new SequencedChatClient(List.of("Mid-term history", "Canonical"));

    try (SessionMemoryService service = new SessionMemoryService(
      memoryRepository, settingsRepository, AppConfig.load(), client
    )) {
      service.refresh("session-id");
    }

    SessionMemory stored = memoryRepository.load("session-id");
    assertEquals(36, stored.recentSummaryCursor());
    assertEquals(0, stored.summaryCursor());
    assertEquals(38, stored.canonicalStateCursor());
    assertEquals(37, stored.contextTargetIndex());
  }

  @Test
  @DisplayName("""
    Given a rolling session whose mid-term window equals the verbatim turn count,
    When the session memory refreshes twice,
    Then both layers should still run, the mid-term layer toward the frontier and long-term memory one
        pass behind it, instead of the equal turn counts silencing the mid-term layer
    """)
  void shouldKeepBothMemoryLayersChainedInRollingModeWithEqualTurnLimits() throws Exception {
    List<Message> messages = new ArrayList<>();
    for (int turn = 1; turn <= 20; turn++) {
      messages.add(new Message("user", "Prompt " + turn));
      messages.add(new Message("assistant", "Response " + turn));
    }
    java.nio.file.Path baseDirectory = java.nio.file.Files.createTempDirectory("storyteller-session-memory-equal-limits");
    java.nio.file.Path configFile = baseDirectory.resolve("systemprompts/application.config");
    java.nio.file.Files.createDirectories(configFile.getParent());
    java.nio.file.Files.writeString(configFile, """
      chat.contextMode=rolling
      chat.contextWindow=8192
      chat.maxRecentTurns=2
      recentSummary.maxRecentTurns=2
      recentSummary.batchMessages=4
      summary.batchMessages=4
      """);
    // Frontier after turn 9 (index 18): the newest turns stay verbatim. With equal turn limits the
    // turns-mode rule would have put the mid-term boundary at size - 2 * chat.maxRecentTurns = 36.
    SessionMemory initialMemory = new SessionMemory("", "", "", 0, 0, 0, 18, messages);
    MemoryRepository memoryRepository = new MemoryRepository(initialMemory);
    SettingsRepository settingsRepository = new SettingsRepository("rolling", 8192);
    SequencedChatClient client = new SequencedChatClient(
      List.of("Mid-term history", "Canonical", "Long history", "Canonical again"));

    try (SessionMemoryService service = new SessionMemoryService(
      memoryRepository, settingsRepository, AppConfigLoader.load(baseDirectory, null), client
    )) {
      service.refresh("session-id");

      SessionMemory afterFirst = memoryRepository.load("session-id");
      assertEquals(18, afterFirst.recentSummaryCursor(),
        "a rolling session always has a mid-term layer, so equal turn counts must not silence it");
      assertEquals(0, afterFirst.summaryCursor(),
        "long-term memory condenses what mid-term already consumed, one pass behind it");
      assertEquals("Mid-term history", afterFirst.recentSummary());

      service.refresh("session-id");
    }

    SessionMemory stored = memoryRepository.load("session-id");
    assertEquals(18, stored.summaryCursor(), "the second pass condenses the story mid-term already consumed");
    assertEquals(18, stored.recentSummaryCursor());
    assertEquals(40, stored.canonicalStateCursor());
    assertEquals("Long history", stored.summary());
    assertEquals(18, stored.contextTargetIndex(), "a memory pass never moves the durable frontier");
  }

  private static final class MemoryRepository implements SessionMemoryRepository {
    private SessionMemory memory;

    private MemoryRepository(SessionMemory memory) {
      this.memory = memory;
    }

    @Override
    public SessionMemory load(String sessionId) {
      return new SessionMemory(
        memory.summary(), memory.recentSummary(), memory.canonicalState(), memory.summaryCursor(),
        memory.recentSummaryCursor(), memory.canonicalStateCursor(), memory.contextTargetIndex(), List.of()
      );
    }

    @Override
    public SessionMemory loadForUpdate(String sessionId) {
      return memory;
    }

    @Override
    public boolean updateSummary(String sessionId, SessionMemory expected, String content, int cursor) {
      memory = new SessionMemory(
        content, memory.recentSummary(), memory.canonicalState(), cursor,
        memory.recentSummaryCursor(), memory.canonicalStateCursor(), memory.contextTargetIndex(), memory.messages()
      );
      return true;
    }

    @Override
    public boolean updateRecentSummary(String sessionId, SessionMemory expected, String content, int cursor) {
      memory = new SessionMemory(
        memory.summary(), content, memory.canonicalState(), memory.summaryCursor(),
        cursor, memory.canonicalStateCursor(), memory.contextTargetIndex(), memory.messages()
      );
      return true;
    }

    @Override
    public boolean updateCanonicalState(String sessionId, SessionMemory expected, String content, int cursor) {
      memory = new SessionMemory(
        memory.summary(), memory.recentSummary(), content, memory.summaryCursor(),
        memory.recentSummaryCursor(), cursor, memory.contextTargetIndex(), memory.messages()
      );
      return true;
    }

    @Override
    public boolean resetMidTermMemory(String sessionId) {
      memory = new SessionMemory(
        memory.summary(), "", memory.canonicalState(), memory.summaryCursor(),
        0, memory.canonicalStateCursor(), memory.contextTargetIndex(), memory.messages()
      );
      return true;
    }

    @Override
    public boolean resetLongTermMemory(String sessionId) {
      memory = new SessionMemory(
        "", memory.recentSummary(), memory.canonicalState(), 0,
        memory.recentSummaryCursor(), memory.canonicalStateCursor(), memory.contextTargetIndex(), memory.messages()
      );
      return true;
    }

    @Override
    public boolean resetCanonicalState(String sessionId) {
      memory = new SessionMemory(
        memory.summary(), memory.recentSummary(), "", memory.summaryCursor(),
        memory.recentSummaryCursor(), 0, memory.contextTargetIndex(), memory.messages()
      );
      return true;
    }
  }

  private static final class SettingsRepository implements SessionSettingsRepository {
    private final String contextMode;
    private final Integer contextWindow;

    private SettingsRepository() {
      this(null, null);
    }

    private SettingsRepository(String contextMode, Integer contextWindow) {
      this.contextMode = contextMode;
      this.contextWindow = contextWindow;
    }

    @Override
    public SessionSettings load(String sessionId) {
      return new SessionSettings(
        new SessionPrompts("System", "Edited fixed", "Rules"),
        KnowledgeGraphDocument.empty(),
        null, null, null, null, null,
        contextMode, contextWindow
      );
    }

    @Override
    public void save(String sessionId, SessionSettings settings) {
    }

    @Override
    public void setTemperature(String sessionId, Double temperature) {
    }

    @Override
    public void patchConfiguration(String sessionId, Map<String, String> prompts,
                                   boolean updateTemperature, Double temperature) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class SequencedChatClient implements ChatClient {
    private final List<String> responses;
    private final List<List<Message>> requests = new ArrayList<>();

    private SequencedChatClient(List<String> responses) {
      this.responses = responses;
    }

    @Override
    public String chat(List<Message> messages, Map<String, Object> options, int timeoutSeconds) {
      requests.add(List.copyOf(messages));
      return responses.get(requests.size() - 1);
    }
  }
}
