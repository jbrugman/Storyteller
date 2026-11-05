package nl.llm.storyteller.api.story;

import nl.llm.storyteller.db.PastStoryExchange;
import nl.llm.storyteller.db.SessionPrompts;
import nl.llm.storyteller.db.SessionMemory;
import nl.llm.storyteller.db.SessionMemoryRepository;
import nl.llm.storyteller.db.SessionSettings;
import nl.llm.storyteller.db.SessionSettingsRepository;
import nl.llm.storyteller.db.StoryImage;
import nl.llm.storyteller.db.StoryRepository;
import nl.llm.storyteller.db.StoryTurnRecord;
import nl.llm.storyteller.core.config.AppConfig;
import nl.llm.storyteller.core.graph.KnowledgeGraphSnapshot;
import nl.llm.storyteller.core.graph.KnowledgeGraphValidator;
import nl.llm.storyteller.core.graph.PredicateCatalog;
import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;
import nl.llm.storyteller.core.graph.service.ReadOnlyKnowledgeGraphService;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.model.StoryChatPromptInput;
import nl.llm.storyteller.core.model.ValidationPromptInput;
import nl.llm.storyteller.core.service.ChatClient;
import nl.llm.storyteller.core.service.PromptLoader;
import nl.llm.storyteller.core.service.PromptTemplateService;
import nl.llm.storyteller.core.service.ResponseGuard;
import nl.llm.storyteller.core.service.StoryChatPromptBuilder;
import nl.llm.storyteller.core.service.ValidationPromptBuilder;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static nl.llm.storyteller.api.input.TextInputNormalizer.requiredMultiline;

public final class StoryTurnService {
  private static final int MAX_PROMPT_LENGTH = 100_000;
  public static final int MAX_PAST_EXCHANGES = 3;

  private final StoryRepository repository;
  private final SessionSettingsRepository settingsRepository;
  private final SessionMemoryRepository memoryRepository;
  private final SessionDerivedStateService derivedStateService;
  private final AppConfig config;
  private final ChatClient chatClient;
  private final ResponseGuard responseGuard;
  private final StoryChatPromptBuilder storyPromptBuilder;
  private final ValidationPromptBuilder validationPromptBuilder;
  private final KnowledgeGraphValidator graphValidator;
  private final PredicateCatalog predicates;
  private final Clock clock;

  public StoryTurnService(
    StoryRepository repository,
    SessionSettingsRepository settingsRepository,
    SessionMemoryRepository memoryRepository,
    SessionDerivedStateService derivedStateService,
    AppConfig config,
    ChatClient chatClient,
    ChatClient validationClient,
    KnowledgeGraphValidator graphValidator,
    PredicateCatalog predicates
  ) {
    this(
      repository, settingsRepository, memoryRepository, derivedStateService,
      config, chatClient, validationClient, graphValidator, predicates, Clock.systemUTC()
    );
  }

  StoryTurnService(
    StoryRepository repository,
    SessionSettingsRepository settingsRepository,
    SessionMemoryRepository memoryRepository,
    SessionDerivedStateService derivedStateService,
    AppConfig config,
    ChatClient chatClient,
    ChatClient validationClient,
    KnowledgeGraphValidator graphValidator,
    PredicateCatalog predicates,
    Clock clock
  ) {
    PromptLoader resources = new PromptLoader(config);
    PromptTemplateService templates = new PromptTemplateService(resources);
    this.repository = repository;
    this.settingsRepository = settingsRepository;
    this.memoryRepository = memoryRepository;
    this.derivedStateService = derivedStateService;
    this.config = config;
    this.chatClient = chatClient;
    this.responseGuard = new ResponseGuard(validationClient, config);
    this.storyPromptBuilder = new StoryChatPromptBuilder(resources, templates);
    this.validationPromptBuilder = new ValidationPromptBuilder(resources, templates);
    this.graphValidator = graphValidator;
    this.predicates = predicates;
    this.clock = clock;
  }

  public synchronized StoryTurnResult execute(String sessionId, String prompt)
    throws IOException, InterruptedException {
    return execute(sessionId, prompt, null, List.of());
  }

  public synchronized StoryTurnResult execute(String sessionId, String prompt, StoryImage image)
    throws IOException, InterruptedException {
    return execute(sessionId, prompt, image, List.of());
  }

  public synchronized StoryTurnResult execute(
    String sessionId,
    String prompt,
    StoryImage image,
    List<Integer> pastMessageIndexes
  ) throws IOException, InterruptedException {
    return execute(sessionId, prompt, image, pastMessageIndexes, null);
  }

  public boolean streamingEnabled(String sessionId) {
    SessionSettings settings = settingsRepository.load(sessionId);
    boolean streaming = settings.streamingEnabled(config.streamingEnabled());
    if (streaming && settings.validationEnabled(config.validationEnabled())) {
      throw new IllegalArgumentException("Streaming and validation cannot both be enabled.");
    }
    return streaming;
  }

  public synchronized StoryTurnResult execute(String sessionId, String prompt, StoryImage image,
    List<Integer> pastMessageIndexes, java.util.function.Consumer<String> onText)
    throws IOException, InterruptedException {
    String userInput = normalizePrompt(prompt);
    SessionSettings settings = settingsRepository.load(sessionId);
    SessionPrompts prompts = settings.prompts();
    SessionMemory memory = memoryRepository.load(sessionId);
    String relevantPastStory = relevantPastStory(sessionId, pastMessageIndexes);
    boolean graphEnabled = settings.graphEnabled(config.graphEnabled());
    ReadOnlyKnowledgeGraphService graph = graphGrounding(settings.knowledgeGraph(), graphEnabled);
    var recentMessages = repository.loadRecentMessages(sessionId, config.maxRecentTurns() * 2);
    List<Message> messages = new ArrayList<>(storyPromptBuilder.build(new StoryChatPromptInput(
      userInput, memory.canonicalState(), memory.summary(), memory.recentSummary(),
      relevantFacts(graph, userInput), relevantPastStory, recentMessages, ""
    ), prompts.systemPrompt(), prompts.fixedProtagonists()));
    if (image != null) {
      Message userMessage = messages.getLast();
      messages.set(messages.size() - 1, Message.withImage(
        userMessage.role(), userMessage.content(), image.dataUrl()
      ));
    }
    java.util.Map<String, Object> options = config.chatOptions();
    Double sessionTemperature = settings.temperature();
    if (sessionTemperature != null) {
      options = new java.util.HashMap<>(options);
      options.put("temperature", sessionTemperature);
    }
    boolean streaming = settings.streamingEnabled(config.streamingEnabled());
    boolean validation = settings.validationEnabled(config.validationEnabled());
    if (streaming && validation) {
      throw new IllegalArgumentException("Streaming and validation cannot both be enabled.");
    }
    if (onText != null && !streaming) throw new IllegalArgumentException("Streaming is disabled for this session.");
    String draftResponse = onText == null
      ? chatClient.chat(messages, options, config.requestTimeoutSeconds())
      : chatClient.stream(messages, options, config.requestTimeoutSeconds(), onText);
    String response = validate(userInput, draftResponse, prompts, validation, graph);
    StoryTurnRecord stored = repository.appendTurn(sessionId, userInput, response, image, clock.instant());
    derivedStateService.requestUpdate(sessionId, graphEnabled);
    return new StoryTurnResult(stored.userMessageIndex(), stored.assistantMessageIndex(), response);
  }

  public int referenceBeforeMessageIndex(String sessionId) {
    int lastMessageIndex = repository.lastMessageIndex(sessionId);
    return Math.max(0, lastMessageIndex + 1 - config.maxRecentTurns() * 2);
  }

  public synchronized void undoLastTurn(String sessionId) {
    repository.undoLastTurn(sessionId, clock.instant());
  }

  private String validate(String userInput, String draftResponse, SessionPrompts prompts, boolean validationEnabled,
    ReadOnlyKnowledgeGraphService graph) throws InterruptedException {
    if (!validationEnabled) {
      return responseGuard.validate("", "", draftResponse, false);
    }
    String systemPrompt = validationPromptBuilder.buildSystemPrompt();
    String request = validationPromptBuilder.buildRequest(
      new ValidationPromptInput(userInput, draftResponse, relevantFacts(graph, userInput + "\n" + draftResponse)),
      prompts.rules(),
      prompts.fixedProtagonists()
    );
    return responseGuard.validate(systemPrompt, request, draftResponse, true);
  }

  /**
   * Builds the per-turn prompt grounding from the graph already stored for this session.
   * Returns null when the feature is disabled or the stored graph no longer validates; a broken graph
   * must never break a story turn, so grounding fails open and the turn simply runs without it.
   */
  private ReadOnlyKnowledgeGraphService graphGrounding(KnowledgeGraphDocument graph, boolean graphEnabled) {
    if (!config.graphEnabled() || !graphEnabled) {
      return null;
    }
    try {
      return new ReadOnlyKnowledgeGraphService(KnowledgeGraphSnapshot.from(graph, graphValidator), predicates);
    } catch (RuntimeException _) {
      return null;
    }
  }

  private static String relevantFacts(ReadOnlyKnowledgeGraphService graph, String text) {
    return graph == null ? "" : graph.relevantFacts(text);
  }

  private String normalizePrompt(String prompt) {
    return requiredMultiline(prompt, "Prompt", MAX_PROMPT_LENGTH);
  }

  public synchronized Optional<String> editAssistantResponse(String sessionId, int messageIndex, String content) {
    String normalizedContent = requiredMultiline(content, "Response", MAX_PROMPT_LENGTH);
    return repository.updateAssistantMessage(sessionId, messageIndex, normalizedContent, clock.instant())
      ? Optional.of(normalizedContent)
      : Optional.empty();
  }

  private String relevantPastStory(String sessionId, List<Integer> requestedIndexes) {
    List<Integer> messageIndexes = requestedIndexes == null
      ? List.of()
      : requestedIndexes.stream().distinct().toList();
    if (messageIndexes.size() > MAX_PAST_EXCHANGES) {
      throw new IllegalArgumentException("Select at most three past exchanges.");
    }
    int referenceBefore = referenceBeforeMessageIndex(sessionId);
    if (messageIndexes.stream().anyMatch(index -> index == null || index < 0 || index >= referenceBefore)) {
      throw new IllegalArgumentException("Only exchanges outside the recent story context can be referenced.");
    }
    List<PastStoryExchange> exchanges = repository.loadPastExchanges(sessionId, messageIndexes);
    if (exchanges.size() != messageIndexes.size()) {
      throw new IllegalArgumentException("One or more selected past exchanges do not exist in this session.");
    }
    if (exchanges.isEmpty()) {
      return "";
    }
    StringBuilder context = new StringBuilder("""
      RELEVANT PAST STORY EXCERPTS

      The following excerpts happened earlier in the story. The user selected them because they are relevant to the
      current turn. Treat them as past events, not as events happening in the current scene.
      """);
    for (PastStoryExchange exchange : exchanges) {
      context.append("\n\n[Earlier exchange, messages ")
        .append(exchange.messageIndex()).append('-').append(exchange.messageIndex() + 1).append("]\nUSER:\n")
        .append(exchange.prompt()).append("\n\nASSISTANT:\n").append(exchange.response());
    }
    return context.toString();
  }
}
