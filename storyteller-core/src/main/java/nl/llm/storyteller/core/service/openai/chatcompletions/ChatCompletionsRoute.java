package nl.llm.storyteller.core.service.openai.chatcompletions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import nl.llm.storyteller.core.JsonSupport;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.service.StructuredOutputNotSupportedException;
import nl.llm.storyteller.core.service.openai.OpenAiRoute;
import nl.llm.storyteller.core.service.openai.OpenAiRouteResult;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ChatCompletionsRoute implements OpenAiRoute {
  private static final List<String> GEMINI_UNSUPPORTED_OPTIONS = List.of("top_k", "min_p", "repeat_penalty");

  private final URI uri;
  private final String model;
  private final String apiKey;
  private final boolean googleBackend;
  private final boolean disableReasoning;
  private final HttpClient httpClient;

  public ChatCompletionsRoute(
    String url, String model, String apiKey, boolean googleBackend, boolean disableReasoning
  ) {
    this.uri = URI.create(url);
    this.model = Objects.requireNonNull(model);
    this.apiKey = Objects.requireNonNull(apiKey);
    this.googleBackend = googleBackend;
    this.disableReasoning = disableReasoning;
    this.httpClient = HttpClient.newHttpClient();
  }

  @Override
  public OpenAiRouteResult execute(List<Message> messages, Map<String, Object> options, int timeoutSeconds)
    throws IOException, InterruptedException {
    Map<String, Object> payload = buildPayload(messages, options);
    HttpResponse<InputStream> response = httpClient.send(
      buildRequest(payload, timeoutSeconds), HttpResponse.BodyHandlers.ofInputStream()
    );
    String body;
    try (InputStream input = response.body()) {
      body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      if (payload.containsKey("response_format")
        && (response.statusCode() == 400 || response.statusCode() == 404 || response.statusCode() == 422)) {
        throw new StructuredOutputNotSupportedException(
          "OpenAI-compatible backend does not accept the requested structured output format: HTTP "
            + response.statusCode() + ": " + body
        );
      }
      throw new IOException("OpenAI-compatible backend returned HTTP " + response.statusCode() + ": " + body);
    }
    return parseResponse(body);
  }

  @Override
  public OpenAiRouteResult stream(List<Message> messages, Map<String, Object> options, int timeoutSeconds,
    java.util.function.Consumer<String> onDelta) throws IOException, InterruptedException {
    Map<String, Object> payload = buildPayload(messages, options);
    payload.put("stream", true);
    payload.put("stream_options", Map.of("include_usage", true));
    HttpResponse<InputStream> response = httpClient.send(
      buildRequest(payload, timeoutSeconds), HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      try (InputStream input = response.body()) {
        throw new IOException("Model streaming backend returned HTTP " + response.statusCode());
      }
    }
    StringBuilder text = new StringBuilder();
    long[] tokens = {-1, -1};
    boolean[] finished = {false};
    nl.llm.storyteller.core.service.openai.EventStream.read(response.body(), timeoutSeconds, data -> {
      if ("[DONE]".equals(data)) { finished[0] = true; return false; }
      JsonNode event = parse(data);
      if (event.has("error")) throw new IllegalArgumentException("Model stream returned an error.");
      JsonNode choice = event.path("choices").path(0);
      if (choice.path("finish_reason").isTextual()
        && !"stop".equals(choice.path("finish_reason").asText())) {
        throw new IllegalArgumentException("Model stream did not finish normally: " + choice.path("finish_reason").asText());
      }
      JsonNode delta = choice.path("delta").path("content");
      if (delta.isTextual()) { text.append(delta.asText()); onDelta.accept(delta.asText()); }
      if (event.path("usage").isObject()) {
        tokens[0] = event.path("usage").path("completion_tokens").asLong(-1);
        tokens[1] = event.path("usage").path("completion_tokens_details").path("reasoning_tokens").asLong(-1);
      }
      return true;
    });
    if (!finished[0]) throw new IOException("Model stream ended before [DONE].");
    return new OpenAiRouteResult(text.toString(), tokens[0], tokens[1]);
  }

  OpenAiRouteResult parseResponse(String body) {
    JsonNode data = parse(body);
    JsonNode content = data.path("choices").path(0).path("message").path("content");
    if (content.isMissingNode()) {
      throw new IllegalArgumentException("OpenAI-compatible response does not contain choices[0].message.content.");
    }
    JsonNode usage = data.path("usage");
    return new OpenAiRouteResult(
      content.asText(),
      usage.path("completion_tokens").asLong(-1),
      usage.path("completion_tokens_details").path("reasoning_tokens").asLong(-1)
    );
  }

  public HttpRequest buildRequest(List<Message> messages, Map<String, Object> options, int timeoutSeconds)
    throws JsonProcessingException {
    return buildRequest(buildPayload(messages, options), timeoutSeconds);
  }

  public Map<String, Object> buildPayload(List<Message> messages, Map<String, Object> options) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (!model.isBlank()) {
      payload.put("model", model);
    }
    payload.put("messages", messages.stream().map(Message::toMap).toList());
    payload.putAll(options);
    if (disableReasoning) {
      payload.put("reasoning_effort", "none");
    }
    if (googleBackend || usesGeminiEndpoint()) {
      GEMINI_UNSUPPORTED_OPTIONS.forEach(payload::remove);
    }
    return payload;
  }

  private HttpRequest buildRequest(Map<String, Object> payload, int timeoutSeconds) throws JsonProcessingException {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
      .header("Content-Type", "application/json")
      .timeout(Duration.ofSeconds(timeoutSeconds))
      .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.OBJECT_MAPPER.writeValueAsString(payload)));
    if (!apiKey.isBlank()) {
      request.header("Authorization", "Bearer " + apiKey);
    }
    return request.build();
  }

  private boolean usesGeminiEndpoint() {
    String host = uri.getHost();
    return host != null && (host.equals("generativelanguage.googleapis.com")
      || host.endsWith(".aiplatform.googleapis.com"));
  }

  private JsonNode parse(String body) {
    try {
      return JsonSupport.OBJECT_MAPPER.readTree(body);
    } catch (JsonProcessingException ex) {
      String snippet = body.substring(0, Math.min(body.length(), 500)).replace("\n", "\\n");
      throw new IllegalArgumentException(
        "OpenAI-compatible backend returned invalid JSON: " + ex.getOriginalMessage()
          + ". Response started with: " + snippet, ex
      );
    }
  }
}
