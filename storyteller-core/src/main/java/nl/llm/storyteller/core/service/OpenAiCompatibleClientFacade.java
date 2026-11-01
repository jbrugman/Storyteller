package nl.llm.storyteller.core.service;

import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.service.openai.OpenAiRoute;
import nl.llm.storyteller.core.service.openai.OpenAiRouteResult;
import nl.llm.storyteller.core.service.openai.BackendIdentity;
import nl.llm.storyteller.core.service.openai.OpenAiBackendIdentity;
import nl.llm.storyteller.core.service.openai.UnsupportedResponsesEndpointException;
import nl.llm.storyteller.core.service.openai.chatcompletions.ChatCompletionsRoute;
import nl.llm.storyteller.core.service.openai.responses.ResponsesRoute;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class OpenAiCompatibleClientFacade implements ChatClient {
  private static final System.Logger LOGGER = System.getLogger(OpenAiCompatibleClientFacade.class.getName());
  private static final ResponsesCapabilityCache RESPONSES_CAPABILITIES = new ResponsesCapabilityCache();
  private static final Pattern REASONING_PATTERN = Pattern.compile(
    "<[^>]*(?:think|thinking|reasoning|channel)[^>]*>.*?"
      + "(?:<[^>]*(?:think|thinking|reasoning|channel)[^>]*>|\\z)",
    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
  );

  private final boolean hideReasoningBlocks;
  private final ChatRequestMetrics metrics;
  private final String metricsPurpose;
  private final OpenAiRoute responses;
  private final OpenAiRoute chatCompletions;
  private final boolean preferResponses;
  private final AtomicReference<ResponsesCapabilityCache.Support> responsesSupport;
  private final boolean disableReasoning;
  private final BackendIdentity backendIdentity;
  private final boolean logModelUsage;

  public OpenAiCompatibleClientFacade(
    String url, String model, boolean hideReasoningBlocks, String apiKey,
    ChatRequestMetrics metrics, String metricsPurpose, boolean googleBackend, boolean disableReasoning,
    boolean logModelUsage
  ) {
    this(
      hideReasoningBlocks, metrics, metricsPurpose,
      new ResponsesRoute(url, model, apiKey, disableReasoning),
      new ChatCompletionsRoute(url, model, apiKey, googleBackend, disableReasoning),
      RESPONSES_CAPABILITIES.forEndpoint(url),
      !model.isBlank(),
      disableReasoning,
      new OpenAiBackendIdentity(url, apiKey),
      logModelUsage
    );
  }

  OpenAiCompatibleClientFacade(
    boolean hideReasoningBlocks, ChatRequestMetrics metrics, String metricsPurpose,
    OpenAiRoute responses, OpenAiRoute chatCompletions
  ) {
    this(hideReasoningBlocks, metrics, metricsPurpose, responses, chatCompletions,
      new AtomicReference<>(ResponsesCapabilityCache.Support.UNKNOWN), true, false, () -> false, false);
  }

  OpenAiCompatibleClientFacade(
    boolean hideReasoningBlocks, ChatRequestMetrics metrics, String metricsPurpose,
    OpenAiRoute responses, OpenAiRoute chatCompletions,
    AtomicReference<ResponsesCapabilityCache.Support> responsesSupport, boolean preferResponses
  ) {
    this(
      hideReasoningBlocks, metrics, metricsPurpose, responses, chatCompletions,
      responsesSupport, preferResponses, false, () -> false, false
    );
  }

  OpenAiCompatibleClientFacade(
    boolean hideReasoningBlocks, ChatRequestMetrics metrics, String metricsPurpose,
    OpenAiRoute responses, OpenAiRoute chatCompletions,
    AtomicReference<ResponsesCapabilityCache.Support> responsesSupport, boolean preferResponses,
    boolean disableReasoning, BackendIdentity backendIdentity
  ) {
    this(hideReasoningBlocks, metrics, metricsPurpose, responses, chatCompletions, responsesSupport, preferResponses,
      disableReasoning, backendIdentity, false);
  }

  OpenAiCompatibleClientFacade(
    boolean hideReasoningBlocks, ChatRequestMetrics metrics, String metricsPurpose,
    OpenAiRoute responses, OpenAiRoute chatCompletions,
    AtomicReference<ResponsesCapabilityCache.Support> responsesSupport, boolean preferResponses,
    boolean disableReasoning, BackendIdentity backendIdentity, boolean logModelUsage
  ) {
    this.hideReasoningBlocks = hideReasoningBlocks;
    this.metrics = Objects.requireNonNull(metrics);
    this.metricsPurpose = Objects.requireNonNull(metricsPurpose);
    this.responses = Objects.requireNonNull(responses);
    this.chatCompletions = Objects.requireNonNull(chatCompletions);
    this.responsesSupport = Objects.requireNonNull(responsesSupport);
    this.preferResponses = preferResponses;
    this.disableReasoning = disableReasoning;
    this.backendIdentity = Objects.requireNonNull(backendIdentity);
    this.logModelUsage = logModelUsage;
  }

  @Override
  public String chat(List<Message> messages, Map<String, Object> options, int timeoutSeconds)
    throws IOException, InterruptedException {
    return chat(metricsPurpose, messages, options, timeoutSeconds);
  }

  @Override
  public String chat(String purpose, List<Message> messages, Map<String, Object> options, int timeoutSeconds)
    throws IOException, InterruptedException {
    long started = System.nanoTime();
    OpenAiRouteResult result = execute(messages, backendOptions(options), timeoutSeconds);
    metrics.recordRequest(purpose, result.outputTokens(), Duration.ofNanos(System.nanoTime() - started));
    if (logModelUsage) {
      LOGGER.log(System.Logger.Level.INFO, "Model usage: purpose=" + purpose
        + ", outputTokens=" + tokenCount(result.outputTokens())
        + ", thinkingTokens=" + tokenCount(result.reasoningTokens()));
    }
    return stripReasoningBlocks(result.content());
  }

  @Override
  public String stream(List<Message> messages, Map<String, Object> options, int timeoutSeconds,
    java.util.function.Consumer<String> onText) throws IOException, InterruptedException {
    long started = System.nanoTime();
    StringBuilder accumulated = new StringBuilder();
    java.util.function.Consumer<String> delta = part -> {
      accumulated.append(part);
      String visible = accumulated.toString();
      if (hideReasoningBlocks) {
        // Hold an unfinished tag so a reasoning marker split across chunks cannot leak.
        int open = visible.lastIndexOf('<');
        if (open > visible.lastIndexOf('>')) visible = visible.substring(0, open);
        visible = stripReasoningBlocks(visible);
      }
      onText.accept(visible);
    };
    Map<String, Object> backendOptions = backendOptions(options);
    OpenAiRouteResult result;
    if (!preferResponses || responsesSupport.get() == ResponsesCapabilityCache.Support.UNSUPPORTED) {
      result = chatCompletions.stream(messages, backendOptions, timeoutSeconds, delta);
    } else {
      try {
        result = responses.stream(messages, backendOptions, timeoutSeconds, delta);
        responsesSupport.set(ResponsesCapabilityCache.Support.SUPPORTED);
      } catch (UnsupportedResponsesEndpointException ex) {
        responsesSupport.set(ResponsesCapabilityCache.Support.UNSUPPORTED);
        result = chatCompletions.stream(messages, backendOptions, timeoutSeconds, delta);
      }
    }
    metrics.recordRequest(metricsPurpose, result.outputTokens(), Duration.ofNanos(System.nanoTime() - started));
    if (logModelUsage) {
      LOGGER.log(System.Logger.Level.INFO, "Model usage: purpose=" + metricsPurpose
        + ", outputTokens=" + tokenCount(result.outputTokens())
        + ", thinkingTokens=" + tokenCount(result.reasoningTokens()));
    }
    return stripReasoningBlocks(result.content());
  }

  private static String tokenCount(long tokens) {
    return tokens < 0 ? "unavailable" : Long.toString(tokens);
  }

  private Map<String, Object> backendOptions(Map<String, Object> options) throws InterruptedException {
    if (!disableReasoning) {
      return options;
    }
    try {
      if (!backendIdentity.isOmlx()) {
        return options;
      }
    } catch (IOException _) {
      // Backend identification is an optional optimization and must not block model traffic.
      return options;
    }
    Map<String, Object> compatible = new LinkedHashMap<>(options);
    Map<String, Object> templateArguments = new LinkedHashMap<>();
    if (options.get("chat_template_kwargs") instanceof Map<?, ?> configuredArguments) {
      configuredArguments.forEach((key, value) -> templateArguments.put(String.valueOf(key), value));
    }
    templateArguments.put("enable_thinking", false);
    compatible.put("chat_template_kwargs", Map.copyOf(templateArguments));
    return Map.copyOf(compatible);
  }

  private OpenAiRouteResult execute(List<Message> messages, Map<String, Object> options, int timeoutSeconds)
    throws IOException, InterruptedException {
    if (!preferResponses || responsesSupport.get() == ResponsesCapabilityCache.Support.UNSUPPORTED) {
      return chatCompletions.execute(messages, options, timeoutSeconds);
    }
    try {
      OpenAiRouteResult result = responses.execute(messages, options, timeoutSeconds);
      responsesSupport.set(ResponsesCapabilityCache.Support.SUPPORTED);
      return result;
    } catch (UnsupportedResponsesEndpointException _) {
      responsesSupport.set(ResponsesCapabilityCache.Support.UNSUPPORTED);
      return chatCompletions.execute(messages, options, timeoutSeconds);
    }
  }

  String stripReasoningBlocks(String content) {
    if (!hideReasoningBlocks || content == null || content.isBlank()) {
      return content;
    }
    return REASONING_PATTERN.matcher(content).replaceAll("").trim();
  }

}
