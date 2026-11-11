package nl.llm.storyteller.core.service;

import nl.llm.storyteller.core.graph.turnbasedservice.TurnBasedKnowledgeGraphService;
import nl.llm.storyteller.core.model.Message;

import java.io.IOException;
import java.util.List;

public final class StorySessionService {
  private final nl.llm.storyteller.core.config.AppConfig config;
  private final StoryHistory historyStore;
  private final ChatClient chatClient;
  private final ResponseGuard responseGuard;
  private final SummaryManager summaryManager;
  private final RecentSummaryManager recentSummaryManager;
  private final CanonicalStateManager canonicalStateManager;
  private final PromptAssemblyService promptAssemblyService;
  private final TurnBasedKnowledgeGraphService turnBasedKnowledgeGraphService;
  private final StoryTurnObserver turnObserver;

  public StorySessionService(
    nl.llm.storyteller.core.config.AppConfig config,
    StoryHistory historyStore,
    ChatClient chatClient,
    ResponseGuard responseGuard,
    SummaryManager summaryManager,
    RecentSummaryManager recentSummaryManager,
    CanonicalStateManager canonicalStateManager,
    PromptAssemblyService promptAssemblyService
  ) {
    this(
      config, historyStore, chatClient, responseGuard, summaryManager, recentSummaryManager,
      canonicalStateManager, promptAssemblyService, null, StoryTurnObserver.NONE
    );
  }

  public StorySessionService(
    nl.llm.storyteller.core.config.AppConfig config,
    StoryHistory historyStore,
    ChatClient chatClient,
    ResponseGuard responseGuard,
    SummaryManager summaryManager,
    RecentSummaryManager recentSummaryManager,
    CanonicalStateManager canonicalStateManager,
    PromptAssemblyService promptAssemblyService,
    TurnBasedKnowledgeGraphService turnBasedKnowledgeGraphService
  ) {
    this(
      config, historyStore, chatClient, responseGuard, summaryManager, recentSummaryManager,
      canonicalStateManager, promptAssemblyService, turnBasedKnowledgeGraphService,
      StoryTurnObserver.NONE
    );
  }

  public StorySessionService(
    nl.llm.storyteller.core.config.AppConfig config,
    StoryHistory historyStore,
    ChatClient chatClient,
    ResponseGuard responseGuard,
    SummaryManager summaryManager,
    RecentSummaryManager recentSummaryManager,
    CanonicalStateManager canonicalStateManager,
    PromptAssemblyService promptAssemblyService,
    TurnBasedKnowledgeGraphService turnBasedKnowledgeGraphService,
    StoryTurnObserver turnObserver
  ) {
    this.config = config;
    this.historyStore = historyStore;
    this.chatClient = chatClient;
    this.responseGuard = responseGuard;
    this.summaryManager = summaryManager;
    this.recentSummaryManager = recentSummaryManager;
    this.canonicalStateManager = canonicalStateManager;
    this.promptAssemblyService = promptAssemblyService;
    this.turnBasedKnowledgeGraphService = turnBasedKnowledgeGraphService;
    this.turnObserver = turnObserver;
  }

  public String handleUserTurn(String userInput) throws IOException, InterruptedException {
    if (isResetTurn(userInput)) {
      return handleControlTurn(userInput);
    }

    PromptAssemblyService.AssembledRequest request = promptAssemblyService.buildChatRequest(userInput);
    return handleStoryTurn(userInput, request.messages(), request.contextTarget());
  }

  public String handleImageTurn(String userInput, String imageDataUrl) throws IOException, InterruptedException {
    // The assembler attaches the picture and measures it against the budget before history is trimmed,
    // so a turn carrying a picture plans the context the model will actually receive.
    PromptAssemblyService.AssembledRequest request = promptAssemblyService.buildChatRequest(userInput, imageDataUrl);
    return handleStoryTurn(userInput, request.messages(), request.contextTarget());
  }

  private String handleStoryTurn(String userInput, List<Message> messages, int contextTarget)
    throws IOException, InterruptedException {
    String draftResponse = chatClient.chat(
      messages,
      config.chatOptions(),
      config.requestTimeoutSeconds()
    );
    String response = validateResponse(userInput, draftResponse);
    turnObserver.completed(userInput, draftResponse, response);

    // Recorded only after a successful generation; a throw above leaves the request frontier untouched.
    historyStore.appendTurn(userInput, response, contextTarget);
    if (turnBasedKnowledgeGraphService != null) {
      turnBasedKnowledgeGraphService.startUpdateIfNeeded();
    }
    canonicalStateManager.startUpdateIfNeeded();
    recentSummaryManager.startUpdateIfNeeded();
    summaryManager.startUpdateSummaryIfNeeded();
    return response;
  }

  public UndoResult undoLastTurnAndReset() throws IOException, InterruptedException {
    String restoredUserInput = historyStore.removeLastTurn();
    if (restoredUserInput.isBlank()) {
      return new UndoResult("", "");
    }

    String resetResponse = handleControlTurn(config.resetStoryCommand());
    return new UndoResult(restoredUserInput, resetResponse);
  }

  public StoryHistory.LastTurn loadLastTurn() {
    return historyStore.loadLastTurn();
  }

  private boolean isResetTurn(String userInput) {
    return userInput != null
      && userInput.trim().equalsIgnoreCase(config.resetStoryCommand().trim());
  }

  private String handleControlTurn(String userInput) throws IOException, InterruptedException {
    List<Message> messages = promptAssemblyService.buildResetMessages(userInput);
    String draftResponse = chatClient.chat(
      messages,
      config.chatOptions(),
      config.requestTimeoutSeconds()
    );
    return validateResponse(userInput, draftResponse);
  }

  private String validateResponse(String userInput, String draftResponse) throws InterruptedException {
    if (!config.validationEnabled()) {
      return responseGuard.validate("", "", draftResponse);
    }
    return responseGuard.validate(
      promptAssemblyService.buildValidationSystemPrompt(),
      promptAssemblyService.buildValidationRequest(userInput, draftResponse),
      draftResponse
    );
  }

  public record UndoResult(String restoredUserInput, String resetResponse) {
    public boolean hasRestoredUserInput() {
      return restoredUserInput != null && !restoredUserInput.isBlank();
    }
  }
}
