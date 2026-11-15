package nl.llm.storyteller.core.service;

import nl.llm.storyteller.core.model.HistoryState;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.model.SummaryPromptInput;

import java.util.ArrayList;
import java.util.List;

public final class SummaryManager extends DerivedMemoryManager {
  private final SummaryPromptBuilder summaryPromptBuilder;
  private final RollingContextWindow rollingContextWindow;

  public SummaryManager(
    StoryHistory historyStore,
    ChatClient client,
    nl.llm.storyteller.core.config.AppConfig config,
    PromptLoader promptResourceLoader,
    PromptTemplateService promptTemplateService,
    SummaryPromptBuilder summaryPromptBuilder
  ) {
    this(
      historyStore, client, config, promptResourceLoader, promptTemplateService, summaryPromptBuilder,
      new FileTextMemory(config.summaryFile()), new DerivedMemoryTaskQueue(), true
    );
  }

  public SummaryManager(
    StoryHistory historyStore,
    ChatClient client,
    nl.llm.storyteller.core.config.AppConfig config,
    PromptLoader promptResourceLoader,
    PromptTemplateService promptTemplateService,
    SummaryPromptBuilder summaryPromptBuilder,
    DerivedMemoryTaskQueue taskQueue
  ) {
    this(
      historyStore, client, config, promptResourceLoader, promptTemplateService, summaryPromptBuilder,
      new FileTextMemory(config.summaryFile()), taskQueue, false
    );
  }

  public SummaryManager(
    StoryHistory historyStore,
    ChatClient client,
    nl.llm.storyteller.core.config.AppConfig config,
    PromptLoader promptResourceLoader,
    PromptTemplateService promptTemplateService,
    SummaryPromptBuilder summaryPromptBuilder,
    TextMemory memory,
    DerivedMemoryTaskQueue taskQueue
  ) {
    this(historyStore, client, config, promptResourceLoader, promptTemplateService, summaryPromptBuilder,
      memory, taskQueue, false);
  }

  private SummaryManager(
    StoryHistory historyStore,
    ChatClient client,
    nl.llm.storyteller.core.config.AppConfig config,
    PromptLoader promptResourceLoader,
    PromptTemplateService promptTemplateService,
    SummaryPromptBuilder summaryPromptBuilder,
    TextMemory memory,
    DerivedMemoryTaskQueue taskQueue,
    boolean ownsTaskQueue
  ) {
    super(historyStore, client, config, promptResourceLoader, promptTemplateService, memory, taskQueue, ownsTaskQueue);
    this.summaryPromptBuilder = summaryPromptBuilder;
    this.rollingContextWindow = new RollingContextWindow(config);
  }

  public String loadSummary() {
    return loadMemory();
  }

  public void startUpdateSummaryIfNeeded() {
    triggerUpdateIfNeeded();
  }

  @Override
  protected boolean isDisabled() {
    return false;
  }

  @Override
  protected String purpose() {
    return "long-memory";
  }

  @Override
  protected DerivedMemoryJob prepareJob() {
    HistoryState state = historyStore.load();
    int cutoffIndex = longTermCutoff(state);
    int cursor = safeCursor(state.summaryCursor(), state.messages().size());

    if (cutoffIndex <= cursor) {
      return null;
    }

    List<Message> pendingMessages = new ArrayList<>(state.messages().subList(cursor, cutoffIndex));
    if (pendingMessages.size() < config.summaryBatchMessages()) {
      return null;
    }

    return new DerivedMemoryJob(cursor, cutoffIndex, loadSummary(), pendingMessages);
  }

  /**
   * Long-term memory condenses what the mid-term layer already consumed, which keeps the layers one
   * chain instead of two independent turn-count windows. Only an active rolling window chains that
   * way, because that is the only case where the mid-term layer always runs toward a boundary the
   * request recorded. Turns mode, and a rolling session whose window is unknown so that it still
   * sends turn counts, condenses everything behind the mid-term window.
   */
  private int longTermCutoff(HistoryState state) {
    if (rollingContextWindow.enabled()
      && rollingContextWindow.midTermLayerEnabled(config.recentSummaryMaxTurns(), config.maxRecentTurns())) {
      return safeCursor(state.recentSummaryCursor(), state.messages().size());
    }
    return state.messages().size() - historyStore.recentMessages(config.recentSummaryMaxTurns()).size();
  }

  @Override
  protected List<Message> buildUpdateMessages(String existingContent, List<Message> pendingMessages) {
    return summaryPromptBuilder.build(new SummaryPromptInput(existingContent, formatHistory(pendingMessages)));
  }

  @Override
  protected int currentCursor(HistoryState state) {
    return state.summaryCursor();
  }

  @Override
  protected void markUpdated(int cutoffIndex) {
    historyStore.markSummarized(cutoffIndex);
  }

  @Override
  protected void ignoreFailure() {
    // Summary refresh is best-effort and must never interrupt the main chat flow.
  }
}
