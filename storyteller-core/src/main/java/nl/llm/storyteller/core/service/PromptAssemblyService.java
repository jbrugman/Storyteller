package nl.llm.storyteller.core.service;

import nl.llm.storyteller.core.graph.service.KnowledgeGraphService;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.model.StoryChatPromptInput;
import nl.llm.storyteller.core.model.TurnRuleDecision;
import nl.llm.storyteller.core.model.ValidationPromptInput;

import java.util.List;

public final class PromptAssemblyService {
  private final StoryHistory historyStore;
  private final SummaryManager summaryManager;
  private final RecentSummaryManager recentSummaryManager;
  private final CanonicalStateManager canonicalStateManager;
  private final TurnManager turnManager;
  private final StoryChatPromptBuilder storyChatPromptBuilder;
  private final ValidationPromptBuilder validationPromptBuilder;
  private final KnowledgeGraphService knowledgeGraphService;
  private final RollingContextWindow rollingContextWindow;

  public PromptAssemblyService(
    StoryHistory historyStore,
    SummaryManager summaryManager,
    RecentSummaryManager recentSummaryManager,
    CanonicalStateManager canonicalStateManager,
    TurnManager turnManager,
    StoryChatPromptBuilder storyChatPromptBuilder,
    ValidationPromptBuilder validationPromptBuilder
  ) {
    this(historyStore, summaryManager, recentSummaryManager, canonicalStateManager, turnManager,
      storyChatPromptBuilder, validationPromptBuilder, null);
  }

  public PromptAssemblyService(
    StoryHistory historyStore,
    SummaryManager summaryManager,
    RecentSummaryManager recentSummaryManager,
    CanonicalStateManager canonicalStateManager,
    TurnManager turnManager,
    StoryChatPromptBuilder storyChatPromptBuilder,
    ValidationPromptBuilder validationPromptBuilder,
    KnowledgeGraphService knowledgeGraphService
  ) {
    this.historyStore = historyStore;
    this.summaryManager = summaryManager;
    this.recentSummaryManager = recentSummaryManager;
    this.canonicalStateManager = canonicalStateManager;
    this.turnManager = turnManager;
    this.storyChatPromptBuilder = storyChatPromptBuilder;
    this.validationPromptBuilder = validationPromptBuilder;
    this.knowledgeGraphService = knowledgeGraphService;
    this.rollingContextWindow = new RollingContextWindow(summaryManager.config());
  }

  public List<Message> buildChatMessages(String userInput) {
    return buildChatRequest(userInput).messages();
  }

  /** A CLI chat request: the messages to send plus the request frontier the turn should record. */
  public record AssembledRequest(List<Message> messages, int contextTarget) {
  }

  public AssembledRequest buildChatRequest(String userInput) {
    return buildChatRequest(userInput, null);
  }

  /**
   * Assembles the chat request for a turn and the durable request frontier to record with it. Both the
   * frame measurement and the request go through {@link #withAttachedImage}, so a picture is charged
   * against the context budget before history is trimmed and is still attached when the request leaves.
   *
   * <p>Rolling mode records the frontier; turns mode records nothing. A rolling frontier is the absolute
   * index of the first message sent verbatim, because everything before it leaves this request and derived
   * memory must still condense up to it. {@code historyStore.load()} holds the whole story here, so an
   * index into it is already absolute; {@code total - verbatim} is that index.
   */
  public AssembledRequest buildChatRequest(String userInput, String imageDataUrl) {
    TurnRuleDecision turnRuleDecision = turnManager.evaluate(userInput);
    List<Message> verbatim;
    int contextTarget;
    if (rollingContextWindow.enabled()) {
      List<Message> history = historyStore.load().messages();
      int frameTokens = RollingContextWindow.estimateMessages(
        withAttachedImage(
          buildStoryChatMessages(userInput, List.of(), turnRuleDecision.promptInstruction()), imageDataUrl));
      verbatim = rollingContextWindow.verbatimMessages(history, rollingContextWindow.plan(history, frameTokens));
      contextTarget = Math.clamp(history.size() - verbatim.size(), 0, history.size());
    } else {
      verbatim = historyStore.recentMessages(summaryManager.config().maxRecentTurns());
      // Turns mode keeps its own memory layout and leaves the durable frontier untouched, like the API.
      contextTarget = 0;
    }
    List<Message> messages = withAttachedImage(
      buildStoryChatMessages(userInput, verbatim, turnRuleDecision.promptInstruction()), imageDataUrl);
    return new AssembledRequest(messages, contextTarget);
  }

  /**
   * Attaches an attached picture to the user message, so the cost estimate and the request agree. Without
   * an image it returns the messages untouched.
   */
  private static List<Message> withAttachedImage(List<Message> messages, String imageDataUrl) {
    if (imageDataUrl == null || imageDataUrl.isBlank()) {
      return messages;
    }
    List<Message> withImage = new java.util.ArrayList<>(messages);
    Message userMessage = withImage.getLast();
    withImage.set(withImage.size() - 1,
      Message.withImage(userMessage.role(), userMessage.content(), imageDataUrl));
    return withImage;
  }

  public List<Message> buildResetMessages(String userInput) {
    return buildStoryChatMessages(userInput, List.of(), "");
  }

  private List<Message> buildStoryChatMessages(String userInput, List<Message> recentMessages, String extraSystemInstruction) {
    return storyChatPromptBuilder.build(
      new StoryChatPromptInput(
        userInput,
        canonicalStateManager.loadCanonicalState(),
        summaryManager.loadSummary(),
        recentSummaryManager.loadRecentSummary(),
        relevantFacts(userInput),
        "",
        recentMessages,
        extraSystemInstruction
      )
    );
  }

  public String buildValidationSystemPrompt() {
    return validationPromptBuilder.buildSystemPrompt();
  }

  public String buildValidationRequest(String userInput, String draftResponse) {
    return validationPromptBuilder.buildRequest(new ValidationPromptInput(
      userInput, draftResponse, relevantFacts(userInput + "\n" + draftResponse)
    ));
  }

  private String relevantFacts(String text) {
    return knowledgeGraphService == null ? "" : knowledgeGraphService.relevantFacts(text);
  }
}
