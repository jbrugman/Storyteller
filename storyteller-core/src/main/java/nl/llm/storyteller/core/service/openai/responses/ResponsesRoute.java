package nl.llm.storyteller.core.service.openai.responses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import nl.llm.storyteller.core.JsonSupport;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.service.StructuredOutputNotSupportedException;
import nl.llm.storyteller.core.service.openai.OpenAiRoute;
import nl.llm.storyteller.core.service.openai.OpenAiRouteResult;
import nl.llm.storyteller.core.service.openai.UnsupportedResponsesEndpointException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ResponsesRoute implements OpenAiRoute {
  private final URI uri;
  private final String model;
  private final String apiKey;
  private final boolean disableReasoning;
  private final HttpClient httpClient;

  public ResponsesRoute(String chatCompletionsUrl, String model, String apiKey, boolean disableReasoning) {
    this.uri = responsesUri(chatCompletionsUrl);
    this.model = Objects.requireNonNull(model);
    this.apiKey = Objects.requireNonNull(apiKey);
    this.disableReasoning = disableReasoning;
    this.httpClient = HttpClient.newHttpClient();
  }

  @Override
  public OpenAiRouteResult execute(List<Message> messages, Map<String, Object> options, int timeoutSeconds)
    throws IOException, InterruptedException {
    HttpResponse<InputStream> response = httpClient.send(
      buildRequest(buildPayload(messages, options), timeoutSeconds), HttpResponse.BodyHandlers.ofInputStream()
    );
    String body;
    try (InputStream input = response.body()) {
      body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (response.statusCode() == 404 || response.statusCode() == 405 || response.statusCode() == 501) {
      throw new UnsupportedResponsesEndpointException(
        "OpenAI-compatible Responses endpoint is unavailable: HTTP " + response.statusCode()
      );
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      if (options.containsKey("response_format")
        && (response.statusCode() == 400 || response.statusCode() == 422)) {
        throw new StructuredOutputNotSupportedException(
          "OpenAI-compatible backend does not accept the requested structured output format: HTTP "
            + response.statusCode() + ": " + body
        );
      }
      throw new IOException("OpenAI-compatible Responses backend returned HTTP " + response.statusCode() + ": " + body);
    }
    return parseResponse(body);
  }

  @Override
  public OpenAiRouteResult stream(List<Message> messages, Map<String, Object> options, int timeoutSeconds,
    java.util.function.Consumer<String> onDelta) throws IOException, InterruptedException {
    Map<String, Object> payload = buildPayload(messages, options);
    payload.put("stream", true);
    HttpResponse<InputStream> response = httpClient.send(
      buildRequest(payload, timeoutSeconds), HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() == 404 || response.statusCode() == 405 || response.statusCode() == 501) {
      response.body().close();
      throw new UnsupportedResponsesEndpointException("Responses endpoint is unavailable: HTTP " + response.statusCode());
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      try (InputStream input = response.body()) {
        throw new IOException("Model streaming backend returned HTTP " + response.statusCode());
      }
    }
    StringBuilder text = new StringBuilder();
    OpenAiRouteResult[] completed = {null};
    nl.llm.storyteller.core.service.openai.EventStream.read(response.body(), timeoutSeconds, data -> {
      if ("[DONE]".equals(data)) return false;
      JsonNode event = parse(data);
      switch (event.path("type").asText()) {
        case "response.output_text.delta" -> {
          String delta = event.path("delta").asText();
          text.append(delta);
          onDelta.accept(delta);
        }
        case "response.completed" -> {
          JsonNode result = event.path("response");
          if (!"completed".equals(result.path("status").asText())) {
            throw new IllegalArgumentException("Model stream did not complete successfully.");
          }
          completed[0] = new OpenAiRouteResult(text.toString(),
            result.path("usage").path("output_tokens").asLong(-1),
            result.path("usage").path("output_tokens_details").path("reasoning_tokens").asLong(-1));
        }
        case "error", "response.failed", "response.incomplete" ->
          throw new IllegalArgumentException("Model stream failed or was incomplete.");
        default -> { }
      }
      return completed[0] == null;
    });
    if (completed[0] == null) throw new IOException("Model stream ended before response.completed.");
    return completed[0];
  }

  OpenAiRouteResult parseResponse(String body) {
    JsonNode data = parse(body);
    JsonNode usage = data.path("usage");
    return new OpenAiRouteResult(
      outputText(data),
      usage.path("output_tokens").asLong(-1),
      usage.path("output_tokens_details").path("reasoning_tokens").asLong(-1)
    );
  }

  public Map<String, Object> buildPayload(List<Message> messages, Map<String, Object> options) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (!model.isBlank()) {
      payload.put("model", model);
    }
    payload.put("input", messages.stream().map(this::inputMessage).toList());
    options.forEach((key, value) -> copyOption(payload, key, value));
    if (disableReasoning) {
      payload.put("reasoning", Map.of("effort", "none"));
    }
    return payload;
  }

  public URI uri() {
    return uri;
  }

  private Map<String, Object> inputMessage(Message message) {
    if (message.imageDataUrl() == null || message.imageDataUrl().isBlank()) {
      return Map.of("role", message.role(), "content", message.content());
    }
    return Map.of(
      "role", message.role(),
      "content", List.of(
        Map.of("type", "input_text", "text", message.content()),
        Map.of("type", "input_image", "image_url", message.imageDataUrl())
      )
    );
  }

  private void copyOption(Map<String, Object> payload, String key, Object value) {
    switch (key) {
      case "max_tokens" -> payload.put("max_output_tokens", value);
      case "reasoning_effort" -> payload.put("reasoning", Map.of("effort", value));
      case "response_format" -> payload.put("text", responseTextFormat(value));
      default -> payload.put(key, value);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> responseTextFormat(Object responseFormat) {
    if (!(responseFormat instanceof Map<?, ?> format) || !"json_schema".equals(format.get("type"))) {
      return Map.of("format", responseFormat);
    }
    Object schemaValue = format.get("json_schema");
    if (!(schemaValue instanceof Map<?, ?> schema)) {
      return Map.of("format", responseFormat);
    }
    Map<String, Object> flattened = new LinkedHashMap<>((Map<String, Object>) schema);
    flattened.put("type", "json_schema");
    return Map.of("format", flattened);
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

  private String outputText(JsonNode data) {
    if (data.path("output_text").isTextual()) {
      return data.path("output_text").asText();
    }
    List<String> parts = new ArrayList<>();
    data.path("output").forEach(item -> item.path("content").forEach(content -> {
      if ("output_text".equals(content.path("type").asText()) && content.path("text").isTextual()) {
        parts.add(content.path("text").asText());
      }
    }));
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("OpenAI-compatible Responses result contains no output text.");
    }
    return String.join("", parts);
  }

  private JsonNode parse(String body) {
    try {
      return JsonSupport.OBJECT_MAPPER.readTree(body);
    } catch (JsonProcessingException ex) {
      String snippet = body.substring(0, Math.min(body.length(), 500)).replace("\n", "\\n");
      throw new IllegalArgumentException(
        "OpenAI-compatible Responses backend returned invalid JSON: " + ex.getOriginalMessage()
          + ". Response started with: " + snippet, ex
      );
    }
  }

  private static URI responsesUri(String chatCompletionsUrl) {
    URI source = URI.create(chatCompletionsUrl);
    String path = source.getPath();
    String suffix = "/chat/completions";
    String responsesPath = path.endsWith(suffix)
      ? path.substring(0, path.length() - suffix.length()) + "/responses"
      : path.replaceFirst("/+$", "") + "/responses";
    try {
      return new URI(source.getScheme(), source.getAuthority(), responsesPath, source.getQuery(), source.getFragment());
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("Could not derive Responses endpoint from " + chatCompletionsUrl, ex);
    }
  }
}
