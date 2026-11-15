package nl.llm.storyteller.api.story;

import nl.llm.storyteller.core.config.AppConfig;
import nl.llm.storyteller.core.model.CanonicalStatePromptInput;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.model.RecentSummaryPromptInput;
import nl.llm.storyteller.core.model.SummaryPromptInput;
import nl.llm.storyteller.core.service.CanonicalStatePromptBuilder;
import nl.llm.storyteller.core.service.ChatClient;
import nl.llm.storyteller.core.service.PromptLoader;
import nl.llm.storyteller.core.service.PromptTemplateService;
import nl.llm.storyteller.core.service.RecentSummaryPromptBuilder;
import nl.llm.storyteller.core.service.RollingContextWindow;
import nl.llm.storyteller.core.service.SummaryPromptBuilder;
import nl.llm.storyteller.db.SessionMemory;
import nl.llm.storyteller.db.SessionSettings;
import nl.llm.storyteller.db.SessionMemoryRepository;
import nl.llm.storyteller.db.SessionSettingsRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SessionMemoryService implements AutoCloseable {
  private final SessionMemoryRepository memoryRepository;
  private final SessionSettingsRepository settingsRepository;
  private final AppConfig config;
  private final ChatClient client;
  private final SummaryPromptBuilder summaryPromptBuilder;
  private final RecentSummaryPromptBuilder recentSummaryPromptBuilder;
  private final CanonicalStatePromptBuilder canonicalStatePromptBuilder;
  private final ExecutorService executor;

  public SessionMemoryService(
    SessionMemoryRepository memoryRepository,
    SessionSettingsRepository settingsRepository,
    AppConfig config,
    ChatClient client
  ) {
    PromptLoader resources = new PromptLoader(config);
    PromptTemplateService templates = new PromptTemplateService(resources);
    this.memoryRepository = memoryRepository;
    this.settingsRepository = settingsRepository;
    this.config = config;
    this.client = client;
    this.summaryPromptBuilder = new SummaryPromptBuilder(resources, templates);
    this.recentSummaryPromptBuilder = new RecentSummaryPromptBuilder(resources, templates);
    this.canonicalStatePromptBuilder = new CanonicalStatePromptBuilder(resources, templates);
    this.executor = Executors.newSingleThreadExecutor(
      Thread.ofPlatform().daemon().name("storyteller-session-memory").factory()
    );
  }

  public void requestUpdate(String sessionId) {
    executor.submit(() -> refreshIgnoringFailure(sessionId));
  }

  void refresh(String sessionId) throws IOException, InterruptedException {
    SessionSettings settings = settingsRepository.load(sessionId);
    updateSummary(sessionId, settings);
    updateRecentSummary(sessionId, settings);
    updateCanonicalState(sessionId, settings);
  }

  private void updateSummary(String sessionId, SessionSettings settings) throws IOException, InterruptedException {
    SessionMemory memory = memoryRepository.loadForUpdate(sessionId);
    int cursor = safeCursor(memory.summaryCursor(), memory.messages().size());
    int cutoff = longTermCutoff(settings, memory);
    if (cutoff <= cursor || cutoff - cursor < config.summaryBatchMessages()) {
      return;
    }
    List<Message> pending = memory.messages().subList(cursor, cutoff);
    String content = chat("long-memory", summaryPromptBuilder.build(
      new SummaryPromptInput(memory.summary(), formatHistory(pending)), settings.prompts().fixedProtagonists()
    ));
    memoryRepository.updateSummary(sessionId, memory, content, cutoff);
  }

  /**
   * Long-term memory condenses what the mid-term layer already consumed, which keeps the layers one
   * chain instead of two independent windows that can summarize the same turn twice. Only an active
   * rolling window chains that way, because that is the only case where the mid-term layer always runs
   * toward a boundary the request recorded. A session in turns mode, and one whose rolling window is
   * unknown so that it still sends turn counts, condenses everything behind the mid-term window.
   */
  private int longTermCutoff(SessionSettings settings, SessionMemory memory) {
    RollingContextWindow contextWindow = rollingContextWindow(settings);
    if (contextWindow.enabled()
      && contextWindow.midTermLayerEnabled(config.recentSummaryMaxTurns(), config.maxRecentTurns())) {
      return safeCursor(memory.recentSummaryCursor(), memory.messages().size());
    }
    return memory.messages().size() - recentTurns(memory.messages(), config.recentSummaryMaxTurns()).size();
  }

  private void updateRecentSummary(String sessionId, SessionSettings settings)
    throws IOException, InterruptedException {
    RollingContextWindow contextWindow = rollingContextWindow(settings);
    boolean rolling = contextWindow.enabled();
    if (!contextWindow.midTermLayerEnabled(config.recentSummaryMaxTurns(), config.maxRecentTurns())) {
      return;
    }
    SessionMemory memory = memoryRepository.loadForUpdate(sessionId);
    int cursor = safeCursor(memory.recentSummaryCursor(), memory.messages().size());
    int cutoff;
    int verbatimMessages = 0;
    if (rolling) {
      // Condense toward the durable request frontier the turn recorded, not toward an optimistic frame
      // measured here. The frontier is the absolute boundary a real request already left behind, so a
      // pass toward it never pulls out story context a live request still sent verbatim.
      cutoff = midTermCutoff(memory);
    } else {
      verbatimMessages = recentTurns(memory.messages(), config.maxRecentTurns()).size();
      cutoff = memory.messages().size() - verbatimMessages;
    }
    if (cutoff <= cursor || cutoff - cursor < config.recentSummaryBatchMessages()) {
      return;
    }
    List<Message> window = rolling
      ? midTermWindow(memory.messages(), cutoff, cursor, config.recentSummaryMaxTurns())
      : trailingWindow(memory.messages(), verbatimMessages);
    if (window.isEmpty()) {
      return;
    }
    String content = chat("short-memory", recentSummaryPromptBuilder.build(
      new RecentSummaryPromptInput(memory.recentSummary(), formatHistory(window)),
      settings.prompts().fixedProtagonists()
    ));
    memoryRepository.updateRecentSummary(sessionId, memory, content, cutoff);
  }

  /** The turns-mode mid-term block: the turns between the verbatim window and the mid-term window. */
  private List<Message> trailingWindow(List<Message> messages, int verbatimMessages) {
    List<Message> completeWindow = recentTurns(messages, config.recentSummaryMaxTurns());
    int summarized = Math.max(0, completeWindow.size() - verbatimMessages);
    return completeWindow.subList(0, summarized);
  }

  /**
   * The mid-term boundary in rolling mode: the durable request frontier a turn recorded, clamped to the
   * messages actually loaded. That frontier is the absolute index the newest request already left
   * behind, so condensing toward it never removes story context a live request still sent verbatim.
   * A zero frontier (a turns-mode append, an undo clamp, or the first pass before any turn) summarises
   * nothing. The frontier is turn-aligned by construction; rounding down keeps that true even if a
   * stale row ever held an odd value, so a cutoff can never split a turn.
   */
  private int midTermCutoff(SessionMemory memory) {
    int cutoff = Math.min(memory.contextTargetIndex(), memory.messages().size());
    return cutoff - (cutoff % 2);
  }

  /**
   * The mid-term block in rolling mode: up to {@code maximumTurns} of the newest turns that are no
   * longer sent verbatim, skipping whatever an earlier pass already summarized. The size cap keeps the
   * layer a recent window and leaves the older story to long-term memory.
   */
  private List<Message> midTermWindow(List<Message> messages, int cutoff, int cursor, int maximumTurns) {
    List<Message> older = messages.subList(0, cutoff);
    List<Message> window = recentTurns(older, maximumTurns);
    int windowStart = older.size() - window.size();
    return window.subList(Math.max(0, cursor - windowStart), window.size());
  }

  private void updateCanonicalState(String sessionId, SessionSettings settings)
    throws IOException, InterruptedException {
    SessionMemory memory = memoryRepository.loadForUpdate(sessionId);
    int cursor = safeCursor(memory.canonicalStateCursor(), memory.messages().size());
    int cutoff = memory.messages().size();
    if (cutoff <= cursor || cutoff - cursor < config.canonicalStateBatchMessages()) {
      return;
    }
    List<Message> pending = memory.messages().subList(cursor, cutoff);
    String content = chat("canonical-state", canonicalStatePromptBuilder.build(
      new CanonicalStatePromptInput(memory.canonicalState(), formatHistory(pending)),
      settings.prompts().fixedProtagonists()
    ));
    memoryRepository.updateCanonicalState(sessionId, memory, content, cutoff);
  }

  public SessionMemory load(String sessionId) {
    return memoryRepository.load(sessionId);
  }

  public void resetMidTermMemory(String sessionId) {
    memoryRepository.resetMidTermMemory(sessionId);
  }

  public void resetLongTermMemory(String sessionId) {
    memoryRepository.resetLongTermMemory(sessionId);
  }

  public void resetCanonicalState(String sessionId) {
    memoryRepository.resetCanonicalState(sessionId);
  }

  private String chat(String purpose, List<Message> messages) throws IOException, InterruptedException {
    return client.chat(purpose, messages, config.summaryOptions(), config.summaryRequestTimeoutSeconds());
  }

  /** The session's context policy: server settings with this session's own overrides applied. */
  private RollingContextWindow rollingContextWindow(SessionSettings settings) {
    return new RollingContextWindow(RollingContextWindow.Policy.from(config)
      .withSession(settings.contextMode(), settings.contextWindow(), settings.maxTokens()));
  }

  private List<Message> recentTurns(List<Message> messages, int limitTurns) {
    if (limitTurns <= 0 || messages.isEmpty()) {
      return List.of();
    }
    int userMessages = 0;
    int start = messages.size();
    for (int index = messages.size() - 1; index >= 0; index--) {
      if ("user".equals(messages.get(index).role())) {
        userMessages++;
        start = index;
        if (userMessages >= limitTurns) {
          break;
        }
      }
    }
    return messages.subList(start, messages.size());
  }

  private int safeCursor(int cursor, int messageCount) {
    return Math.clamp(cursor, 0, messageCount);
  }

  private String formatHistory(List<Message> messages) {
    List<String> lines = new ArrayList<>(messages.size());
    for (Message message : messages) {
      lines.add(message.role().toUpperCase() + ": " + message.content());
    }
    return String.join("\n", lines);
  }

  private void refreshIgnoringFailure(String sessionId) {
    try {
      refresh(sessionId);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException _) {
      // Derived memory is best-effort and must never interrupt the main story flow.
    }
  }

  @Override
  public void close() {
    executor.close();
  }
}
