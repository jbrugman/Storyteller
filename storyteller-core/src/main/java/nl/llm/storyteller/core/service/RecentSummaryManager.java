package nl.llm.storyteller.core.service;

import nl.llm.storyteller.core.model.HistoryState;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.model.RecentSummaryPromptInput;

import java.util.List;

public final class RecentSummaryManager extends DerivedMemoryManager {
  private final RecentSummaryPromptBuilder recentSummaryPromptBuilder;
  private final RollingContextWindow rollingContextWindow;

  public RecentSummaryManager(
    StoryHistory historyStore,
    ChatClient client,
    nl.llm.storyteller.core.config.AppConfig config,
    PromptLoader promptResourceLoader,
    PromptTemplateService promptTemplateService,
    RecentSummaryPromptBuilder recentSummaryPromptBuilder
  ) {
    this(
      historyStore, client, config, promptResourceLoader, promptTemplateService, recentSummaryPromptBuilder,
      new FileTextMemory(config.recentSummaryFile()), new DerivedMemoryTaskQueue(), true
    );
  }

  public RecentSummaryManager(
    StoryHistory historyStore,
    ChatClient client,
    nl.llm.storyteller.core.config.AppConfig config,
    PromptLoader promptResourceLoader,
    PromptTemplateService promptTemplateService,
    RecentSummaryPromptBuilder recentSummaryPromptBuilder,
    TextMemory memory,
    DerivedMemoryTaskQueue taskQueue
  ) {
    this(historyStore, client, config, promptResourceLoader, promptTemplateService, recentSummaryPromptBuilder,
      memory, taskQueue, false);
  }

  private RecentSummaryManager(
    StoryHistory historyStore,
    ChatClient client,
    nl.llm.storyteller.core.config.AppConfig config,
    PromptLoader promptResourceLoader,
    PromptTemplateService promptTemplateService,
    RecentSummaryPromptBuilder recentSummaryPromptBuilder,
    TextMemory memory,
    DerivedMemoryTaskQueue taskQueue,
    boolean ownsTaskQueue
  ) {
    super(historyStore, client, config, promptResourceLoader, promptTemplateService, memory, taskQueue, ownsTaskQueue);
    this.recentSummaryPromptBuilder = recentSummaryPromptBuilder;
    this.rollingContextWindow = new RollingContextWindow(config);
  }

  public String loadRecentSummary() {
    return loadMemory();
  }

  public void startUpdateIfNeeded() {
    triggerUpdateIfNeeded();
  }

  /**
   * A rolling session with a known window always has a mid-term layer: it condenses toward the request
   * frontier its turns recorded, so the configured turn counts cannot describe a state with nothing to
   * do. They only decide whether the layer exists in turns mode, including a rolling session whose
   * window is unknown and therefore sends turn counts.
   */
  @Override
  protected boolean isDisabled() {
    return !rollingContextWindow.midTermLayerEnabled(
      config.recentSummaryMaxTurns(), config.maxRecentTurns());
  }

  @Override
  protected String purpose() {
    return "short-memory";
  }

  @Override
  protected DerivedMemoryJob prepareJob() {
    HistoryState state = historyStore.load();
    if (rollingContextWindow.enabled()) {
      return rollingJob(state);
    }

    List<Message> recent = historyStore.recentMessages(config.maxRecentTurns());
    int cutoffIndex = state.messages().size() - recent.size();
    int cursor = safeCursor(state.recentSummaryCursor(), state.messages().size());

    if (cutoffIndex <= cursor) {
      return null;
    }

    int pendingMessagesCount = cutoffIndex - cursor;
    if (pendingMessagesCount < config.recentSummaryBatchMessages()) {
      return null;
    }

    List<Message> windowMessages = historyStore.recentMessagesWindow(
      config.recentSummaryMaxTurns(),
      config.maxRecentTurns()
    );
    if (windowMessages.isEmpty()) {
      return null;
    }

    return new DerivedMemoryJob(cursor, cutoffIndex, loadRecentSummary(), windowMessages);
  }

  /**
   * Mid-term job for a rolling session. It condenses toward the durable request frontier the turns
   * recorded, not toward the configured turn count, so the memory boundary tracks the real request
   * frontier even when the window keeps more or fewer verbatim turns than {@code chat.maxRecentTurns}.
   * The block is the newest {@code recentSummaryMaxTurns} turns behind that boundary, minus whatever an
   * earlier pass already summarized, which keeps the layer a bounded recent window and leaves older
   * story to long-term memory. This mirrors the API mid-term rule so both runtimes advance the same way.
   */
  private DerivedMemoryJob rollingJob(HistoryState state) {
    int cutoffIndex = Math.min(state.contextTargetIndex(), state.messages().size());
    cutoffIndex = cutoffIndex - (cutoffIndex % 2);
    int cursor = safeCursor(state.recentSummaryCursor(), state.messages().size());
    if (cutoffIndex <= cursor) {
      return null;
    }
    if (cutoffIndex - cursor < config.recentSummaryBatchMessages()) {
      return null;
    }
    List<Message> windowMessages = midTermWindow(state.messages(), cutoffIndex, cursor, config.recentSummaryMaxTurns());
    if (windowMessages.isEmpty()) {
      return null;
    }
    return new DerivedMemoryJob(cursor, cutoffIndex, loadRecentSummary(), windowMessages);
  }

  private List<Message> midTermWindow(List<Message> messages, int cutoff, int cursor, int maximumTurns) {
    List<Message> older = messages.subList(0, cutoff);
    List<Message> window = recentTurns(older, maximumTurns);
    int windowStart = older.size() - window.size();
    return window.subList(Math.max(0, cursor - windowStart), window.size());
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

  @Override
  protected List<Message> buildUpdateMessages(String existingContent, List<Message> pendingMessages) {
    return recentSummaryPromptBuilder.build(
      new RecentSummaryPromptInput(existingContent, formatHistory(pendingMessages))
    );
  }

  @Override
  protected int currentCursor(HistoryState state) {
    return state.recentSummaryCursor();
  }

  @Override
  protected void markUpdated(int cutoffIndex) {
    historyStore.markRecentSummarized(cutoffIndex);
  }

  @Override
  protected void ignoreFailure() {
    // Recent summary refresh is best-effort and must never interrupt the main chat flow.
  }
}
