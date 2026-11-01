package nl.llm.storyteller.api;

import com.fasterxml.jackson.databind.JsonNode;
import nl.llm.storyteller.core.JsonSupport;
import nl.llm.storyteller.core.config.AppConfigLoader;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.service.ChatClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class StreamingApiTest {
  @TempDir Path directory;
  private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
  private final AtomicBoolean fail = new AtomicBoolean();
  private final AtomicBoolean holdCompletion = new AtomicBoolean();
  private final java.util.concurrent.CountDownLatch complete = new java.util.concurrent.CountDownLatch(1);
  private final AtomicInteger validationCalls = new AtomicInteger();
  private final AtomicInteger streamCalls = new AtomicInteger();
  private ApiServer server;
  private ApiConfig apiConfig;
  private Path coreOverride;
  private String sessionId;

  private final ChatClient model = new ChatClient() {
    @Override public String chat(List<Message> messages, Map<String, Object> options, int timeout) {
      return "Original response";
    }
    @Override public String stream(List<Message> messages, Map<String, Object> options, int timeout,
      Consumer<String> onText) throws IOException {
      streamCalls.incrementAndGet();
      onText.accept("First");
      if (holdCompletion.get()) {
        try {
          if (!complete.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Client did not receive text");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
      }
      if (fail.get()) throw new IOException("Connection lost");
      onText.accept("First second");
      return "First second";
    }
  };
  private final ChatClient validator = (_, _, _) -> {
    validationCalls.incrementAndGet();
    return "{\"decision\":\"REPLACE\",\"response\":\"Validated response\"}";
  };

  @AfterEach void close() { if (server != null) server.close(); }

  @Test
  @DisplayName("""
    Given session streaming enabled and validation disabled in the database,
    When a turn streams successfully,
    Then progressive text and completion are sent and the complete turn is persisted without validation
    """)
  void shouldStreamAndPersistCompletedTurn() throws Exception {
    start(true);
    assertEquals(400, request("POST", "/turns/stream", "{\"prompt\":\"Hello\"}").statusCode());
    patch("{\"streamingEnabled\":true,\"validationEnabled\":false}");
    var response = request("POST", "/turns/stream", "{\"prompt\":\"Hello\"}");
    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("text/event-stream"));
    assertTrue(response.body().contains("event: text\ndata: {\"text\":\"First\"}"));
    assertTrue(response.body().contains("event: text\ndata: {\"text\":\"First second\"}"));
    assertTrue(response.body().contains("event: done"));
    assertFalse(response.body().contains("event: error"));
    var messages = json(request("GET", "/history", null)).path("messages");
    assertEquals(2, messages.size());
    assertEquals("First second", messages.get(1).path("content").asText());
    assertEquals(0, validationCalls.get());
    assertEquals(1, streamCalls.get());
  }

  @Test
  @DisplayName("""
    Given a streaming turn that fails after its first text,
    When the browser receives an error event,
    Then no partial turn is persisted and a retry stores exactly one exchange
    """)
  void shouldNotStoreInterruptedTurn() throws Exception {
    start(false);
    patch("{\"streamingEnabled\":true,\"validationEnabled\":false}");
    fail.set(true);
    var failed = request("POST", "/turns/stream", "{\"prompt\":\"Hello\"}");
    assertTrue(failed.body().contains("event: text"));
    assertTrue(failed.body().contains("event: error"));
    assertFalse(failed.body().contains("event: done"));
    assertEquals(0, json(request("GET", "/history", null)).path("messages").size());
    fail.set(false);
    assertTrue(request("POST", "/turns/stream", "{\"prompt\":\"Hello\"}").body().contains("event: done"));
    assertEquals(2, json(request("GET", "/history", null)).path("messages").size());
  }

  @Test
  @DisplayName("""
    Given conflicting generation settings or an inherited validation default,
    When an API patch would enable both options,
    Then it is rejected atomically and the previous settings remain unchanged
    """)
  void shouldRejectConflictingEffectiveSettings() throws Exception {
    start(true);
    String originalRules = json(request("GET", "/config", null)).path("rules").asText();
    assertEquals(400, request("PATCH", "/config", "{\"streamingEnabled\":true,\"rules\":\"Changed\"}").statusCode());
    assertEquals(originalRules, json(request("GET", "/config", null)).path("rules").asText());
    assertEquals(400, request("PATCH", "/config", "{\"streamingEnabled\":true,\"validationEnabled\":true}").statusCode());
    patch("{\"streamingEnabled\":true,\"validationEnabled\":false}");
    assertEquals(400, request("PATCH", "/config", "{\"validationEnabled\":null}").statusCode());
    assertEquals(400, request("PATCH", "/config", "{\"streamingEnabled\":\"true\"}").statusCode());
    var settings = json(request("GET", "/config", null));
    assertTrue(settings.path("effectiveStreamingEnabled").asBoolean());
    assertFalse(settings.path("effectiveValidationEnabled").asBoolean());
  }

  @Test
  @DisplayName("""
    Given validation globally disabled,
    When session settings enable validation or disable both options,
    Then subsequent turns use the saved session choice rather than the global default
    """)
  void shouldUseSessionValidationOverride() throws Exception {
    start(false);
    patch("{\"streamingEnabled\":false,\"validationEnabled\":true}");
    assertEquals("Validated response", json(request("POST", "/turns", "{\"prompt\":\"Hello\"}")).path("response").asText());
    assertEquals(1, validationCalls.get());
    patch("{\"streamingEnabled\":false,\"validationEnabled\":false}");
    assertEquals("Original response", json(request("POST", "/turns", "{\"prompt\":\"Again\"}")).path("response").asText());
    assertEquals(1, validationCalls.get());
  }

  @Test
  @DisplayName("""
    Given generation settings saved through the web Settings form,
    When the API restarts and the session is loaded again,
    Then the database choices survive and both Settings and the story reflect streaming
    """)
  void shouldPersistWebSettingsAcrossRestart() throws Exception {
    start(true);
    var settings = json(request("GET", "/config", null));
    String graph = json(request("GET", "/state", null)).path("knowledgeGraph").toString();
    String form = "generationSettings=true&streamingEnabled=on&systemPrompt=" + encode(settings.path("systemPrompt").asText())
      + "&fixedProtagonists=" + encode(settings.path("fixedProtagonists").asText())
      + "&rules=" + encode(settings.path("rules").asText()) + "&knowledgeGraph=" + encode(graph);
    assertEquals(303, send("POST", "/story/settings", form, "application/x-www-form-urlencoded").statusCode());
    try (var connection = java.sql.DriverManager.getConnection(apiConfig.databaseUrl(), "sa", "");
         var statement = connection.prepareStatement("SELECT streaming_enabled, validation_enabled FROM session_configuration WHERE session_id = ?")) {
      statement.setString(1, sessionId);
      try (var rows = statement.executeQuery()) {
        assertTrue(rows.next()); assertTrue(rows.getBoolean(1)); assertFalse(rows.getBoolean(2));
      }
    }
    server.close();
    openServer();
    var reloaded = json(request("GET", "/config", null));
    assertTrue(reloaded.path("streamingEnabled").asBoolean());
    assertFalse(reloaded.path("validationEnabled").asBoolean());
    assertTrue(send("GET", "/story", null, "application/json").body().contains("/turns/stream"));
    String html = send("GET", "/story/settings", null, "application/json").body();
    assertTrue(html.contains("name=\"streamingEnabled\" checked"));
    assertFalse(html.contains("name=\"validationEnabled\" checked"));
    patch("{\"rules\":\"New rules\"}");
    assertTrue(json(request("GET", "/config", null)).path("streamingEnabled").asBoolean());
  }

  @Test
  @DisplayName("""
    Given a model that waits before completing its response,
    When a browser starts reading the streaming HTTP response,
    Then the first text event arrives before generation and persistence finish
    """)
  void shouldFlushTextBeforeGenerationCompletes() throws Exception {
    start(false);
    patch("{\"streamingEnabled\":true,\"validationEnabled\":false}");
    holdCompletion.set(true);
    try {
      var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
        + "/v1/sessions/" + sessionId + "/turns/stream"))
        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"Hello\"}")).build(), HttpResponse.BodyHandlers.ofInputStream());
      try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String line;
        boolean first = false;
        while ((line = reader.readLine()) != null) {
          if (line.equals("data: {\"text\":\"First\"}")) { first = true; break; }
        }
        assertTrue(first);
        assertEquals(0, json(request("GET", "/history", null)).path("messages").size());
        complete.countDown();
        assertTrue(reader.lines().anyMatch(value -> value.equals("event: done")));
      }
    } finally { complete.countDown(); }
    assertEquals(2, json(request("GET", "/history", null)).path("messages").size());
  }

  private void start(boolean validation) throws Exception {
    coreOverride = directory.resolve("core.config");
    Files.writeString(coreOverride, "validation.enabled=" + validation + "\ngraph.enabled=false\n");
    apiConfig = new ApiConfig("127.0.0.1", 0, directory.resolve("database/api"), "sa", "", Duration.ofMinutes(60));
    openServer();
    var response = send("POST", "/v1/sessions", "{}", "application/json");
    assertEquals(201, response.statusCode());
    sessionId = json(response).path("sessionId").asText();
  }

  private void openServer() throws Exception {
    server = ApiServer.create(apiConfig, AppConfigLoader.load(directory, coreOverride), model, validator);
    server.start();
  }

  private void patch(String body) throws Exception { assertEquals(200, request("PATCH", "/config", body).statusCode()); }
  private JsonNode json(HttpResponse<String> response) throws Exception { return JsonSupport.OBJECT_MAPPER.readTree(response.body()); }
  private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
  private HttpResponse<String> request(String method, String suffix, String body) throws Exception {
    return send(method, "/v1/sessions/" + sessionId + suffix, body, "application/json");
  }
  private HttpResponse<String> send(String method, String path, String body, String contentType) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
      .timeout(Duration.ofSeconds(15)).header("Content-Type", contentType)
      .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
      .build(), HttpResponse.BodyHandlers.ofString());
  }
}
