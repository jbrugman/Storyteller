package nl.llm.storyteller.core.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AppConfig {
  private static final String OPTION_TEMPERATURE = "temperature";
  private static final String OPTION_TOP_K = "top_k";
  private static final String OPTION_TOP_P = "top_p";
  private static final String OPTION_MIN_P = "min_p";
  private static final String OPTION_REPEAT_PENALTY = "repeat_penalty";
  private static final String OPTION_SEED = "seed";
  private static final String OPTION_MAX_TOKENS = "max_tokens";

  public static final String CONTEXT_MODE_TURNS = "turns";
  public static final String CONTEXT_MODE_ROLLING = "rolling";

  private final Path baseDir;
  private final ModelAccessConfig modelAccess;
  private final FilesConfig files;
  private final MysqlConfig mysql;
  private final String databaseEncryptionKey;
  private final ConversationConfig conversation;
  private final ExecutionConfig execution;
  private final RuntimeTextConfig runtimeText;
  private final OptionsConfig options;

  private AppConfig(
    Path baseDir,
    ModelAccessConfig modelAccess,
    FilesConfig files,
    MysqlConfig mysql,
    String databaseEncryptionKey,
    ConversationConfig conversation,
    ExecutionConfig execution,
    RuntimeTextConfig runtimeText,
    OptionsConfig options
  ) {
    this.baseDir = baseDir;
    this.modelAccess = modelAccess;
    this.files = files;
    this.mysql = mysql;
    this.databaseEncryptionKey = databaseEncryptionKey;
    this.conversation = conversation;
    this.execution = execution;
    this.runtimeText = runtimeText;
    this.options = options;
  }

  public static AppConfig load() {
    return AppConfigLoader.load();
  }

  private AppConfig validate() {
    validateBackendType();
    validateManagedMlxServer();
    validateManagedLlamaServer();
    validateConversation();
    validateValidationOutputMode();
    validateTurnPenalties();
    validateResilience();
    return this;
  }

  private void validateBackendType() {
    if (!"openai-compatible".equalsIgnoreCase(backendType())
      && !usesManagedLlamaServer()
      && !usesManagedMlxServer()) {
      throw new IllegalArgumentException(
        "backend.type must be openai-compatible, managed-llama-server, or managed-mlx-server."
      );
    }
  }

  private void validateManagedMlxServer() {
    if (!usesManagedMlxServer()) {
      return;
    }

    MlxServerConfig server = mlxServerConfig();
    if (server.command().isBlank() || server.modelPath() == null) {
      throw new IllegalArgumentException("Managed MLX server requires backend.mlx.command and backend.mlx.modelPath.");
    }
    if (server.port() < 0 || server.port() > 65_535) {
      throw new IllegalArgumentException("backend.mlx.port must be between 0 and 65535.");
    }
    if (server.startupTimeoutSeconds() < 1) {
      throw new IllegalArgumentException("backend.mlx.startupTimeoutSeconds must be at least 1.");
    }
  }

  private void validateManagedLlamaServer() {
    if (!usesManagedLlamaServer()) {
      return;
    }

    LlamaServerConfig server = llamaServerConfig();
    if (server.command().isBlank() || server.modelPath() == null) {
      throw new IllegalArgumentException("Managed llama-server requires backend.llama.command and backend.llama.modelPath.");
    }
    if (server.port() < 0 || server.port() > 65_535) {
      throw new IllegalArgumentException("backend.llama.port must be between 0 and 65535.");
    }
    if (server.startupTimeoutSeconds() < 1) {
      throw new IllegalArgumentException("backend.llama.startupTimeoutSeconds must be at least 1.");
    }
  }

  private void validateConversation() {
    if (maxRecentTurns() < 1) {
      throw new IllegalArgumentException("chat.maxRecentTurns must be at least 1.");
    }
    if (recentSummaryMaxTurns() < maxRecentTurns()) {
      throw new IllegalArgumentException("recentSummary.maxRecentTurns must be >= chat.maxRecentTurns.");
    }
    if (!CONTEXT_MODE_TURNS.equalsIgnoreCase(contextMode()) && !CONTEXT_MODE_ROLLING.equalsIgnoreCase(contextMode())) {
      throw new IllegalArgumentException("chat.contextMode must be turns or rolling.");
    }
    if (contextWindow() < 0) {
      throw new IllegalArgumentException("chat.contextWindow must be 0 for unknown, or a positive token count.");
    }
    if (responseReserveTokens() < 0) {
      throw new IllegalArgumentException("chat.responseReserveTokens must be 0 or a positive token count.");
    }
    if (summaryBatchMessages() < 1 || recentSummaryBatchMessages() < 1 || canonicalStateBatchMessages() < 1
      || graphTurnBasedBatchTurns() < 1) {
      throw new IllegalArgumentException("Batch sizes must all be at least 1.");
    }
  }

  private void validateValidationOutputMode() {
    if (streamingEnabled() && validationEnabled()) {
      throw new IllegalArgumentException("streaming.enabled requires validation.enabled=false.");
    }
    if (!"auto".equalsIgnoreCase(validationOutputMode())
      && !"text".equalsIgnoreCase(validationOutputMode())
      && !"json-schema".equalsIgnoreCase(validationOutputMode())) {
      throw new IllegalArgumentException("validation.outputMode must be auto, text, or json-schema.");
    }
  }

  private void validateTurnPenalties() {
    if (turnPenaltySingleLowHp() < 1 || turnPenaltySingleHighHp() < turnPenaltySingleLowHp()) {
      throw new IllegalArgumentException("Turn penalties must be positive, and the high value must be >= the low value.");
    }
  }

  private void validateResilience() {
    if (chatFailureThreshold() < 1 || validationFailureThreshold() < 1 || backgroundFailureThreshold() < 1) {
      throw new IllegalArgumentException("Resilience failure thresholds must all be at least 1.");
    }
    if (chatCooldownSeconds() < 1 || validationCooldownSeconds() < 1 || backgroundCooldownSeconds() < 1) {
      throw new IllegalArgumentException("Resilience cooldown values must all be at least 1 second.");
    }
  }

  public String backendType() {
    return modelAccess.backendType();
  }

  public boolean googleBackend() {
    return modelAccess.googleBackend();
  }

  public String openAiCompatibleUrl() {
    return modelAccess.openAiCompatibleUrl();
  }

  public String openAiCompatibleApiKey() {
    return modelAccess.openAiCompatibleApiKey();
  }

  public String memoryHttpUrl() {
    return modelAccess.memoryHttpUrl().isBlank() ? openAiCompatibleUrl() : modelAccess.memoryHttpUrl();
  }

  public String memoryHttpApiKey() {
    return modelAccess.memoryHttpApiKey().isBlank() ? openAiCompatibleApiKey() : modelAccess.memoryHttpApiKey();
  }

  public boolean hasMemoryHttpUrl() {
    return !modelAccess.memoryHttpUrl().isBlank();
  }

  public boolean usesManagedLlamaServer() {
    return "managed-llama-server".equalsIgnoreCase(backendType());
  }

  public boolean usesManagedMlxServer() {
    return "managed-mlx-server".equalsIgnoreCase(backendType());
  }

  public LlamaServerConfig llamaServerConfig() {
    return new LlamaServerConfig(
      modelAccess.llamaServerCommand(),
      modelAccess.llamaServerModelPath(),
      modelAccess.llamaServerPort(),
      modelAccess.llamaServerStartupTimeoutSeconds(),
      modelAccess.llamaServerArguments()
    );
  }

  public MlxServerConfig mlxServerConfig() {
    return new MlxServerConfig(
      modelAccess.mlxServerCommand(),
      modelAccess.mlxServerModelPath(),
      modelAccess.mlxServerPort(),
      modelAccess.mlxServerStartupTimeoutSeconds(),
      modelAccess.mlxServerArguments()
    );
  }

  public Path baseDir() {
    return baseDir;
  }

  public String chatModel() {
    return effectiveRequestModel(modelAccess.chatModel());
  }

  public String validatorModel() {
    return effectiveRequestModel(modelAccess.validatorModel());
  }

  public String memoryHttpModel() {
    String configuredModel = modelAccess.memoryHttpModel().isBlank()
      ? modelAccess.chatModel()
      : modelAccess.memoryHttpModel();
    return effectiveRequestModel(configuredModel);
  }

  private String effectiveRequestModel(String configuredModel) {
    if (!configuredModel.isBlank() || !usesManagedMlxServer()) {
      return configuredModel;
    }
    return mlxServerConfig().modelPath().toString();
  }

  public Path systemPromptFile() {
    return files.systemPromptFile();
  }

  public Path rulesFile() {
    return files.rulesFile();
  }

  public Path fixedProtagonistsFile() {
    return files.fixedProtagonistsFile();
  }

  public Path fixedProtagonistsContextFile() {
    return files.fixedProtagonistsContextFile();
  }

  public Path summaryFile() {
    return files.summaryFile();
  }

  public Path summaryContextFile() {
    return files.summaryContextFile();
  }

  public Path recentSummarySystemPromptFile() {
    return files.recentSummarySystemPromptFile();
  }

  public Path recentSummaryContextFile() {
    return files.recentSummaryContextFile();
  }

  public Path recentSummaryFile() {
    return files.recentSummaryFile();
  }

  public Path summarySystemPromptFile() {
    return files.summarySystemPromptFile();
  }

  public Path canonicalStateSystemPromptFile() {
    return files.canonicalStateSystemPromptFile();
  }

  public Path canonicalStateContextFile() {
    return files.canonicalStateContextFile();
  }

  public Path validationSystemPromptFile() {
    return files.validationSystemPromptFile();
  }

  public Path validationRequestTemplateFile() {
    return files.validationRequestTemplateFile();
  }

  public Path turnViolationSingleTemplateFile() {
    return files.turnViolationSingleTemplateFile();
  }

  public Path turnViolationPartyTemplateFile() {
    return files.turnViolationPartyTemplateFile();
  }

  public Path canonicalStateFile() {
    return files.canonicalStateFile();
  }

  public Path historyFile() {
    return files.historyFile();
  }

  public Path legacyHistoryFile() {
    return files.legacyHistoryFile();
  }

  public Path turnStateFile() {
    return files.turnStateFile();
  }

  public MysqlConfig mysql() {
    return mysql;
  }

  public String databaseEncryptionKey() {
    return databaseEncryptionKey;
  }

  public Path databasePath() {
    return files.databasePath();
  }

  public Path knowledgeGraphFile() {
    return files.knowledgeGraphFile();
  }

  public int maxRecentTurns() {
    return conversation.maxRecentTurns();
  }

  /** Response cap configured for chat requests, or 0 when the backend decides the response length. */
  public int chatMaxTokens() {
    return conversation.chatMaxTokens();
  }

  public String contextMode() {
    return conversation.contextMode();
  }

  public int contextWindow() {
    return conversation.contextWindow();
  }

  public int responseReserveTokens() {
    return conversation.responseReserveTokens();
  }

  public int summaryBatchMessages() {
    return conversation.summaryBatchMessages();
  }

  public int recentSummaryMaxTurns() {
    return conversation.recentSummaryMaxTurns();
  }

  public int recentSummaryBatchMessages() {
    return conversation.recentSummaryBatchMessages();
  }

  public int canonicalStateBatchMessages() {
    return conversation.canonicalStateBatchMessages();
  }

  public int graphTurnBasedBatchTurns() {
    return conversation.graphTurnBasedBatchTurns();
  }

  public boolean graphEnabled() {
    return conversation.graphEnabled();
  }

  public boolean turnBasedModeEnabled() {
    return conversation.turnBasedModeEnabled();
  }

  public int turnPenaltySingleLowHp() {
    return conversation.turnPenaltySingleLowHp();
  }

  public int turnPenaltySingleHighHp() {
    return conversation.turnPenaltySingleHighHp();
  }

  public int requestTimeoutSeconds() {
    return execution.timeouts().requestTimeoutSeconds();
  }

  public int summaryRequestTimeoutSeconds() {
    return execution.timeouts().summaryRequestTimeoutSeconds();
  }

  public int validationRequestTimeoutSeconds() {
    return execution.timeouts().validationRequestTimeoutSeconds();
  }

  public int chatFailureThreshold() {
    return execution.resilience().chatFailureThreshold();
  }

  public int chatCooldownSeconds() {
    return execution.resilience().chatCooldownSeconds();
  }

  public int validationFailureThreshold() {
    return execution.resilience().validationFailureThreshold();
  }

  public int validationCooldownSeconds() {
    return execution.resilience().validationCooldownSeconds();
  }

  public int backgroundFailureThreshold() {
    return execution.resilience().backgroundFailureThreshold();
  }

  public int backgroundCooldownSeconds() {
    return execution.resilience().backgroundCooldownSeconds();
  }

  public boolean hideReasoningBlocks() {
    return runtimeText.hideReasoningBlocks();
  }

  public boolean logModelUsage() {
    return runtimeText.logModelUsage();
  }

  public boolean streamingEnabled() {
    return runtimeText.streamingEnabled();
  }

  public boolean validationEnabled() {
    return runtimeText.validationEnabled();
  }

  public String validationOutputMode() {
    return runtimeText.validationOutputMode();
  }

  public String validationFailClosedMessage() {
    return runtimeText.validationFailClosedMessage();
  }

  public Map<String, Object> chatOptions() {
    return options.chatOptions();
  }

  public Map<String, Object> summaryOptions() {
    return options.summaryOptions();
  }

  public Map<String, Object> validationOptions() {
    return options.validationOptions();
  }

  public String continueStoryCommand() {
    return runtimeText.continueStoryCommand();
  }

  public String resetStoryCommand() {
    return runtimeText.resetStoryCommand();
  }

  public String bannerStartText() {
    return runtimeText.bannerStartText();
  }

  public String shortcutContinueHint() {
    return runtimeText.shortcutContinueHint();
  }

  public String shortcutResetHint() {
    return runtimeText.shortcutResetHint();
  }

  public String shortcutUndoHint() {
    return runtimeText.shortcutUndoHint();
  }

  public String shortcutLastTurnHint() {
    return runtimeText.shortcutLastTurnHint();
  }

  public String resetSentText() {
    return runtimeText.resetSentText();
  }

  public String undoSentText() {
    return runtimeText.undoSentText();
  }

  public String noStoryTurnToUndoText() {
    return runtimeText.noStoryTurnToUndoText();
  }

  public String undoRestoredText() {
    return runtimeText.undoRestoredText();
  }

  public String noLastTurnText() {
    return runtimeText.noLastTurnText();
  }

  public String lastTurnTemplate() {
    return runtimeText.lastTurnTemplate();
  }

  public String backendRequestErrorText() {
    return runtimeText.backendRequestErrorText();
  }

  public String processHistoryErrorText() {
    return runtimeText.processHistoryErrorText();
  }

  public String macHint() {
    return runtimeText.macHint();
  }

  public String commandHelpText() {
    return runtimeText.commandHelpText();
  }

  private static Map<String, Object> linkedMapOf(Object... entries) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) {
      values.put((String) entries[i], entries[i + 1]);
    }
    return values;
  }

  static AppConfig from(AppConfigSource source) {
    return new AppConfig(
      source.baseDir(),
      new ModelAccessConfig(
        source.requiredString("backend.type"),
        source.requiredBoolean("google.backend"),
        source.requiredString("backend.http.url"),
        source.optionalTrimmedString("backend.http.apiKey"),
        source.optionalTrimmedString("model.chat"),
        source.optionalTrimmedString("model.validator"),
        source.optionalTrimmedString("memory.http.model"),
        source.optionalTrimmedString("memory.http.url"),
        source.optionalTrimmedString("memory.http.apikey"),
        source.optionalTrimmedString("backend.llama.command"),
        source.optionalPath(),
        source.requiredInt("backend.llama.port"),
        source.requiredInt("backend.llama.startupTimeoutSeconds"),
        source.optionalTrimmedString("backend.llama.arguments"),
        source.optionalTrimmedString("backend.mlx.command"),
        source.optionalPath("backend.mlx.modelPath"),
        source.requiredInt("backend.mlx.port"),
        source.requiredInt("backend.mlx.startupTimeoutSeconds"),
        source.optionalTrimmedString("backend.mlx.arguments")
      ),
      new FilesConfig(
        source.requiredPath("file.systemPrompt"),
        source.requiredPath("file.rules"),
        source.requiredPath("file.fixedProtagonists"),
        source.requiredPath("file.fixedProtagonistsContext"),
        source.requiredPath("file.summarySystemPrompt"),
        source.requiredPath("file.summaryContext"),
        source.requiredPath("file.summary"),
        source.requiredPath("file.recentSummarySystemPrompt"),
        source.requiredPath("file.recentSummaryContext"),
        source.requiredPath("file.recentSummary"),
        source.requiredPath("file.canonicalStateSystemPrompt"),
        source.requiredPath("file.canonicalStateContext"),
        source.requiredPath("file.validationSystemPrompt"),
        source.requiredPath("file.validationRequestTemplate"),
        source.requiredPath("file.turnViolationSingleTemplate"),
        source.requiredPath("file.turnViolationPartyTemplate"),
        source.requiredPath("file.canonicalState"),
        source.requiredPath("file.history"),
        source.requiredPath("file.legacyHistory"),
        source.requiredPath("file.turnState"),
        source.requiredPath("file.knowledgeGraph"),
        source.requiredPath("database.path")
      ),
      source.mysqlConfig(),
      source.optionalTrimmedString("database.encryption.key"),
      new ConversationConfig(
        source.requiredInt("chat.maxRecentTurns"),
        source.optionalInt("chat.maxTokens", 0),
        source.requiredString("chat.contextMode"),
        source.requiredInt("chat.contextWindow"),
        source.requiredInt("chat.responseReserveTokens"),
        source.requiredInt("recentSummary.maxRecentTurns"),
        source.requiredInt("summary.batchMessages"),
        source.requiredInt("recentSummary.batchMessages"),
        source.requiredInt("canonicalState.batchMessages"),
        source.requiredInt("graph.turnBased.batchTurns"),
        source.requiredBoolean("graph.enabled"),
        source.requiredBoolean("game.turnBasedModeEnabled"),
        source.requiredInt("game.turnPenaltySingleLowHp"),
        source.requiredInt("game.turnPenaltySingleHighHp")
      ),
      new ExecutionConfig(
        new TimeoutConfig(
          source.requiredInt("timeout.chatSeconds"),
          source.requiredInt("timeout.summarySeconds"),
          source.requiredInt("timeout.validationSeconds")
        ),
        new ResilienceConfig(
          source.requiredInt("resilience.chat.failureThreshold"),
          source.requiredInt("resilience.chat.cooldownSeconds"),
          source.requiredInt("resilience.validation.failureThreshold"),
          source.requiredInt("resilience.validation.cooldownSeconds"),
          source.requiredInt("resilience.background.failureThreshold"),
          source.requiredInt("resilience.background.cooldownSeconds")
        )
      ),
      new RuntimeTextConfig(
        source.requiredBoolean("validation.enabled"),
        source.requiredBoolean("streaming.enabled"),
        source.requiredString("validation.outputMode"),
        source.requiredBoolean("response.hideReasoningBlocks"),
        source.requiredBoolean("logging.modelUsage"),
        source.requiredString("response.validationFailClosedMessage"),
        source.requiredString("command.continueStory"),
        source.requiredString("command.resetStory"),
        source.requiredString("ui.bannerStart"),
        source.requiredString("ui.commandHelp"),
        source.requiredString("ui.shortcutContinueHint"),
        source.requiredString("ui.shortcutResetHint"),
        source.requiredString("ui.shortcutUndoHint"),
        source.requiredString("ui.shortcutLastTurnHint"),
        source.requiredString("ui.resetSent"),
        source.requiredString("ui.undoSent"),
        source.requiredString("ui.noStoryTurnToUndo"),
        source.requiredString("ui.undoRestored"),
        source.requiredString("ui.noLastTurn"),
        source.requiredString("ui.lastTurnTemplate"),
        source.requiredString("ui.errorBackendRequest"),
        source.requiredString("ui.errorProcessHistory"),
        source.requiredString("ui.macHint")
      ),
      new OptionsConfig(
        chatOptions(source),
        deterministicOptions(linkedMapOf(
          OPTION_TEMPERATURE, source.requiredDouble("summary.temperature"),
          OPTION_TOP_P, source.requiredDouble("summary.topP")
        ), source, "summary"),
        deterministicOptions(linkedMapOf(
          OPTION_TEMPERATURE, source.requiredDouble("validation.temperature"),
          OPTION_TOP_P, source.requiredDouble("validation.topP")
        ), source, "validation")
      )
    ).validate();
  }

  private static Map<String, Object> chatOptions(AppConfigSource source) {
    Map<String, Object> options = linkedMapOf(
      OPTION_TEMPERATURE, source.requiredDouble("chat.temperature"),
      OPTION_TOP_K, source.requiredInt("chat.topK"),
      OPTION_TOP_P, source.requiredDouble("chat.topP"),
      OPTION_MIN_P, source.requiredDouble("chat.minP"),
      OPTION_REPEAT_PENALTY, source.requiredDouble("chat.repeatPenalty")
    );
    int seed = source.optionalInt("chat.seed", -1);
    int maxTokens = source.optionalInt("chat.maxTokens", 0);
    if (seed >= 0) {
      options.put(OPTION_SEED, seed);
    }
    if (maxTokens > 0) {
      options.put(OPTION_MAX_TOKENS, maxTokens);
    }
    return options;
  }

  private static Map<String, Object> deterministicOptions(
    Map<String, Object> options,
    AppConfigSource source,
    String prefix
  ) {
    int seed = source.optionalInt(prefix + ".seed", source.optionalInt("chat.seed", -1));
    int maxTokens = source.optionalInt(prefix + ".maxTokens", source.optionalInt("chat.maxTokens", 0));
    if (seed >= 0) {
      options.put(OPTION_SEED, seed);
    }
    if (maxTokens > 0) {
      options.put(OPTION_MAX_TOKENS, maxTokens);
    }
    return options;
  }

  private record ModelAccessConfig(
    String backendType,
    boolean googleBackend,
    String openAiCompatibleUrl,
    String openAiCompatibleApiKey,
    String chatModel,
    String validatorModel,
    String memoryHttpModel,
    String memoryHttpUrl,
    String memoryHttpApiKey,
    String llamaServerCommand,
    Path llamaServerModelPath,
    int llamaServerPort,
    int llamaServerStartupTimeoutSeconds,
    String llamaServerArguments,
    String mlxServerCommand,
    Path mlxServerModelPath,
    int mlxServerPort,
    int mlxServerStartupTimeoutSeconds,
    String mlxServerArguments
  ) {
  }

  private record FilesConfig(
    Path systemPromptFile,
    Path rulesFile,
    Path fixedProtagonistsFile,
    Path fixedProtagonistsContextFile,
    Path summarySystemPromptFile,
    Path summaryContextFile,
    Path summaryFile,
    Path recentSummarySystemPromptFile,
    Path recentSummaryContextFile,
    Path recentSummaryFile,
    Path canonicalStateSystemPromptFile,
    Path canonicalStateContextFile,
    Path validationSystemPromptFile,
    Path validationRequestTemplateFile,
    Path turnViolationSingleTemplateFile,
    Path turnViolationPartyTemplateFile,
    Path canonicalStateFile,
    Path historyFile,
    Path legacyHistoryFile,
    Path turnStateFile,
    Path knowledgeGraphFile,
    Path databasePath
  ) {
  }

  private record ConversationConfig(
    int maxRecentTurns,
    int chatMaxTokens,
    String contextMode,
    int contextWindow,
    int responseReserveTokens,
    int recentSummaryMaxTurns,
    int summaryBatchMessages,
    int recentSummaryBatchMessages,
    int canonicalStateBatchMessages,
    int graphTurnBasedBatchTurns,
    boolean graphEnabled,
    boolean turnBasedModeEnabled,
    int turnPenaltySingleLowHp,
    int turnPenaltySingleHighHp
  ) {
  }

  private record ExecutionConfig(
    TimeoutConfig timeouts,
    ResilienceConfig resilience
  ) {
  }

  private record TimeoutConfig(
    int requestTimeoutSeconds,
    int summaryRequestTimeoutSeconds,
    int validationRequestTimeoutSeconds
  ) {
  }

  private record ResilienceConfig(
    int chatFailureThreshold,
    int chatCooldownSeconds,
    int validationFailureThreshold,
    int validationCooldownSeconds,
    int backgroundFailureThreshold,
    int backgroundCooldownSeconds
  ) {
  }

  private record RuntimeTextConfig(
    boolean validationEnabled,
    boolean streamingEnabled,
    String validationOutputMode,
    boolean hideReasoningBlocks,
    boolean logModelUsage,
    String validationFailClosedMessage,
    String continueStoryCommand,
    String resetStoryCommand,
    String bannerStartText,
    String commandHelpText,
    String shortcutContinueHint,
    String shortcutResetHint,
    String shortcutUndoHint,
    String shortcutLastTurnHint,
    String resetSentText,
    String undoSentText,
    String noStoryTurnToUndoText,
    String undoRestoredText,
    String noLastTurnText,
    String lastTurnTemplate,
    String backendRequestErrorText,
    String processHistoryErrorText,
    String macHint) {
  }

  private record OptionsConfig(
    Map<String, Object> chatOptions,
    Map<String, Object> summaryOptions,
    Map<String, Object> validationOptions
  ) {
    private OptionsConfig {
      chatOptions = Map.copyOf(chatOptions);
      summaryOptions = Map.copyOf(summaryOptions);
      validationOptions = Map.copyOf(validationOptions);
    }
  }
}
