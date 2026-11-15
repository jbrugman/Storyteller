package nl.llm.storyteller.api.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import nl.llm.storyteller.core.JsonSupport;
import nl.llm.storyteller.core.graph.KnowledgeGraphValidator;
import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;
import nl.llm.storyteller.core.config.AppConfig;
import nl.llm.storyteller.core.graph.persistence.KnowledgeGraphJsonCodec;
import nl.llm.storyteller.db.ConfigurationPatch;
import nl.llm.storyteller.db.SessionPrompts;
import nl.llm.storyteller.db.SessionSettings;
import nl.llm.storyteller.db.SessionSettingsRepository;

import java.util.LinkedHashMap;

import static nl.llm.storyteller.api.input.TextInputNormalizer.requiredMultiline;

public final class SessionSettingsService {
  private static final int MAX_PROMPT_LENGTH = 1_000_000;
  private static final int MAX_TOKENS_LIMIT = 1_000_000;
  private static final int MAX_CONTEXT_WINDOW_LIMIT = 100_000_000;
  /** What the interfaces offer as a starting point when a session chooses rolling without a known model. */
  public static final int RECOMMENDED_CONTEXT_WINDOW_TOKENS = 32_768;
  private final SessionSettingsRepository repository;
  private final KnowledgeGraphValidator graphValidator;
  private final boolean defaultStreaming;
  private final boolean defaultValidation;
  private final boolean defaultGraph;
  private final String defaultContextMode;
  private final int defaultContextWindow;
  private final int defaultResponseReserveTokens;
  private final KnowledgeGraphJsonCodec graphCodec = new KnowledgeGraphJsonCodec();

  public SessionSettingsService(SessionSettingsRepository repository, KnowledgeGraphValidator graphValidator) {
    this(repository, graphValidator, false, true, true,
      AppConfig.CONTEXT_MODE_TURNS, 0, 0);
  }

  public SessionSettingsService(SessionSettingsRepository repository, KnowledgeGraphValidator graphValidator,
    boolean defaultStreaming, boolean defaultValidation, boolean defaultGraph) {
    this(repository, graphValidator, defaultStreaming, defaultValidation, defaultGraph,
      AppConfig.CONTEXT_MODE_TURNS, 0, 0);
  }

  public SessionSettingsService(SessionSettingsRepository repository, KnowledgeGraphValidator graphValidator,
    boolean defaultStreaming, boolean defaultValidation, boolean defaultGraph, String defaultContextMode,
    int defaultContextWindow, int defaultResponseReserveTokens) {
    this.defaultContextMode = defaultContextMode;
    this.defaultContextWindow = defaultContextWindow;
    this.defaultResponseReserveTokens = defaultResponseReserveTokens;
    this.defaultStreaming = defaultStreaming;
    this.defaultValidation = defaultValidation;
    this.defaultGraph = defaultGraph;
    this.repository = repository;
    this.graphValidator = graphValidator;
  }

  public SessionSettings load(String sessionId) {
    return repository.load(sessionId);
  }

  public void save(
    String sessionId,
    String systemPrompt,
    String fixedProtagonists,
    String rules,
    String knowledgeGraph,
    Double temperature
  ) {
    SessionPrompts prompts = new SessionPrompts(
      requiredMultiline(systemPrompt, "System prompt", MAX_PROMPT_LENGTH),
      requiredMultiline(fixedProtagonists, "Fixed protagonists", MAX_PROMPT_LENGTH),
      requiredMultiline(rules, "Rules", MAX_PROMPT_LENGTH)
    );
    FixedProtagonistsValidator.validate(prompts.fixedProtagonists());
    SessionSettings current = repository.load(sessionId);
    repository.save(sessionId, new SessionSettings(prompts, parseKnowledgeGraph(knowledgeGraph), temperature,
      current.streamingEnabled(), current.validationEnabled(), current.graphEnabled(), current.maxTokens(),
      current.contextMode(), current.contextWindow()));
  }

  public boolean streamingEnabled(SessionSettings settings) {
    return settings.streamingEnabled(defaultStreaming);
  }

  public boolean validationEnabled(SessionSettings settings) {
    return settings.validationEnabled(defaultValidation);
  }

  public boolean graphEnabled(SessionSettings settings) {
    return settings.graphEnabled(defaultGraph);
  }

  public String contextMode(SessionSettings settings) {
    return settings.contextMode(defaultContextMode);
  }

  public int contextWindow(SessionSettings settings) {
    return settings.contextWindow(defaultContextWindow);
  }

  /** The server's mode, shown by the settings form as what an empty session choice means. */
  public String defaultContextMode() {
    return defaultContextMode;
  }

  /** The server's context window, shown by the settings form as what an empty session window means. */
  public int defaultContextWindow() {
    return defaultContextWindow;
  }

  /** Space the server keeps free for a reply while a rolling context is assembled. */
  public int responseReserveTokens() {
    return defaultResponseReserveTokens;
  }

  public void validateGeneration(Boolean streaming, Boolean validation) {
    if ((streaming != null ? streaming : defaultStreaming)
      && (validation != null ? validation : defaultValidation)) {
      throw new IllegalArgumentException("Streaming and validation cannot both be enabled.");
    }
  }

  public void save(String sessionId, String systemPrompt, String fixedProtagonists, String rules,
    String knowledgeGraph, Double temperature, Boolean streaming, Boolean validation, Boolean graph,
    Integer maxTokens) {
    save(sessionId, systemPrompt, fixedProtagonists, rules, knowledgeGraph, temperature, streaming, validation,
      graph, maxTokens, null, null);
  }

  public void save(String sessionId, String systemPrompt, String fixedProtagonists, String rules,
    String knowledgeGraph, Double temperature, Boolean streaming, Boolean validation, Boolean graph,
    Integer maxTokens, String contextMode, Integer contextWindow) {
    validateGeneration(streaming, validation);
    validateMaxTokens(maxTokens);
    validateContextMode(contextMode);
    validateContextWindow(contextWindow);
    validateChosenRollingWindow(blankToNull(contextMode),
      contextWindow != null ? contextWindow : defaultContextWindow);
    SessionPrompts prompts = new SessionPrompts(
      requiredMultiline(systemPrompt, "System prompt", MAX_PROMPT_LENGTH),
      requiredMultiline(fixedProtagonists, "Fixed protagonists", MAX_PROMPT_LENGTH),
      requiredMultiline(rules, "Rules", MAX_PROMPT_LENGTH));
    FixedProtagonistsValidator.validate(prompts.fixedProtagonists());
    repository.save(sessionId, new SessionSettings(prompts, parseKnowledgeGraph(knowledgeGraph), temperature,
      streaming, validation, graph, maxTokens, blankToNull(contextMode), contextWindow));
  }

  public String formatKnowledgeGraph(SessionSettings settings) {
    try {
      return JsonSupport.OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
        .writeValueAsString(graphCodec.toJson(settings.knowledgeGraph()));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Could not render the knowledge graph.", ex);
    }
  }

  public void patchConfiguration(
    String sessionId,
    String systemPrompt,
    String fixedProtagonists,
    String rules,
    boolean updateTemperature,
    Double temperature
  ) {
    patchConfiguration(sessionId, systemPrompt, fixedProtagonists, rules, updateTemperature, temperature,
      false, null, false, null, false, null, false, null);
  }

  public synchronized void patchConfiguration(String sessionId, String systemPrompt, String fixedProtagonists,
    String rules, boolean updateTemperature, Double temperature, boolean updateStreaming, Boolean streaming,
    boolean updateValidation, Boolean validation, boolean updateGraph, Boolean graph,
    boolean updateMaxTokens, Integer maxTokens) {
    patchConfiguration(sessionId, systemPrompt, fixedProtagonists, rules, updateTemperature, temperature,
      updateStreaming, streaming, updateValidation, validation, updateGraph, graph, updateMaxTokens, maxTokens,
      false, null, false, null);
  }

  public synchronized void patchConfiguration(String sessionId, String systemPrompt, String fixedProtagonists,
    String rules, boolean updateTemperature, Double temperature, boolean updateStreaming, Boolean streaming,
    boolean updateValidation, Boolean validation, boolean updateGraph, Boolean graph,
    boolean updateMaxTokens, Integer maxTokens, boolean updateContextMode, String contextMode,
    boolean updateContextWindow, Integer contextWindow) {
    SessionSettings current = repository.load(sessionId);
    Boolean effectiveStreaming = updateStreaming ? streaming : current.streamingEnabled();
    Boolean effectiveValidation = updateValidation ? validation : current.validationEnabled();
    Boolean effectiveGraph = updateGraph ? graph : current.graphEnabled();
    validateGeneration(effectiveStreaming, effectiveValidation);
    var prompts = new LinkedHashMap<String, String>();
    if (systemPrompt != null) {
      prompts.put(SessionPrompts.SYSTEM_PROMPT_NAME, requiredMultiline(systemPrompt, "System prompt", MAX_PROMPT_LENGTH));
    }
    if (fixedProtagonists != null) {
      String normalized = requiredMultiline(fixedProtagonists, "Fixed protagonists", MAX_PROMPT_LENGTH);
      FixedProtagonistsValidator.validate(normalized);
      prompts.put(SessionPrompts.FIXED_PROTAGONISTS_NAME, normalized);
    }
    if (rules != null) {
      prompts.put(SessionPrompts.RULES_NAME, requiredMultiline(rules, "Rules", MAX_PROMPT_LENGTH));
    }
    if (updateTemperature && temperature != null
      && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)) {
      throw new IllegalArgumentException("temperature must be a number between 0 and 2, or null.");
    }
    if (updateMaxTokens) {
      validateMaxTokens(maxTokens);
    }
    if (updateContextMode) {
      validateContextMode(contextMode);
    }
    if (updateContextWindow) {
      validateContextWindow(contextWindow);
    }
    Integer windowInEffect = updateContextWindow ? contextWindow : current.contextWindow();
    String chosenMode = updateContextMode ? blankToNull(contextMode) : current.contextMode();
    validateChosenRollingWindow(chosenMode, windowInEffect != null ? windowInEffect : defaultContextWindow);
    repository.patchConfiguration(sessionId, new ConfigurationPatch(prompts, updateTemperature, temperature,
      updateStreaming || updateValidation || updateGraph, effectiveStreaming, effectiveValidation,
      updateGraph, effectiveGraph, updateMaxTokens, maxTokens, updateContextMode, blankToNull(contextMode),
      updateContextWindow, contextWindow));
  }

  /**
   * A session may choose its own context mode and window, and each is validated on its own so a patch
   * carrying one of them cannot reject or overwrite the other. An empty mode means inherit and a window
   * of zero means the model's size is unknown.
   */
  private static void validateContextMode(String contextMode) {
    if (contextMode != null && !contextMode.isBlank()
      && !AppConfig.CONTEXT_MODE_TURNS.equalsIgnoreCase(contextMode)
      && !AppConfig.CONTEXT_MODE_ROLLING.equalsIgnoreCase(contextMode)) {
      throw new IllegalArgumentException("contextMode must be turns, rolling, or null to inherit.");
    }
  }

  /**
   * A session that names rolling itself has to name the window it wants to fill, because a rolling plan
   * without a size silently sends turn counts instead. Inheriting the server's mode stays outside the
   * rule: a server running rolling without a size is its operator's choice and keeps explaining itself
   * through the context report, which is also what a session in turns mode still does.
   */
  private static void validateChosenRollingWindow(String sessionMode, Integer effectiveWindow) {
    if (AppConfig.CONTEXT_MODE_ROLLING.equalsIgnoreCase(sessionMode == null ? "" : sessionMode)
      && (effectiveWindow == null || effectiveWindow <= 0)) {
      throw new InvalidContextSettingException(
        "Rolling context needs the model's context window before it can be saved. Give it the model's size in"
          + " tokens, or " + RECOMMENDED_CONTEXT_WINDOW_TOKENS + " to start with, and a model that reserves room"
          + " for its reasoning counted in that size too.");
    }
  }

  private static void validateContextWindow(Integer contextWindow) {
    if (contextWindow != null && (contextWindow < 0 || contextWindow > MAX_CONTEXT_WINDOW_LIMIT)) {
      throw new IllegalArgumentException("contextWindow must be null, 0 for an unknown window, or a number up to "
        + MAX_CONTEXT_WINDOW_LIMIT + ".");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static void validateMaxTokens(Integer maxTokens) {
    if (maxTokens != null && (maxTokens < 0 || maxTokens > MAX_TOKENS_LIMIT)) {
      throw new IllegalArgumentException("maxTokens must be null, 0 for no limit, or a number up to "
        + MAX_TOKENS_LIMIT + ".");
    }
  }

  private KnowledgeGraphDocument parseKnowledgeGraph(String knowledgeGraph) {
    String normalized = requiredMultiline(knowledgeGraph, "Knowledge graph", MAX_PROMPT_LENGTH);
    try {
      KnowledgeGraphDocument document = graphCodec.fromJson(normalized);
      graphValidator.validate(document);
      return document;
    } catch (JsonProcessingException | IllegalArgumentException ex) {
      throw new InvalidKnowledgeGraphException("Invalid knowledge graph: " + ex.getMessage(), ex);
    }
  }
}
