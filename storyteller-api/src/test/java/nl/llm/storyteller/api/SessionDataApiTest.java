package nl.llm.storyteller.api;

import com.fasterxml.jackson.databind.JsonNode;
import nl.llm.storyteller.core.JsonSupport;
import nl.llm.storyteller.core.config.AppConfigLoader;
import nl.llm.storyteller.core.service.ChatClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionDataApiTest {
  @TempDir
  Path temporaryDirectory;

  private final HttpClient client = HttpClient.newHttpClient();
  private final AtomicReference<Map<String, Object>> chatOptions = new AtomicReference<>();
  // The prompt as the model receives it, so context assembly can be asserted without a real model.
  private final AtomicReference<List<nl.llm.storyteller.core.model.Message>> chatMessages = new AtomicReference<>();
  private final AtomicInteger modelRequests = new AtomicInteger();
  private ApiServer server;
  private ApiConfig config;
  private String sessionId;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  @DisplayName("Given partial settings patches, when turns are submitted, then omitted fields and effective temperature are preserved")
  void shouldApplyPartialConfigurationToNextTurnAndRestoreDefaultTemperature() throws Exception {
    startSession();
    JsonNode original = json(request("GET", "/config", null));
    assertEquals(200, request("PATCH", "/config", "{\"temperature\":0,\"systemPrompt\":\"New system\"}").statusCode());
    assertEquals(200, request("PATCH", "/config", "{\"rules\":\"New rules\"}").statusCode());
    JsonNode updated = json(request("GET", "/config", null));
    assertEquals("New system", updated.path("systemPrompt").asText());
    assertEquals("New rules", updated.path("rules").asText());
    assertEquals(original.path("fixedProtagonists"), updated.path("fixedProtagonists"));
    assertEquals(0.0, updated.path("temperature").asDouble());
    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"Hello\"}").statusCode());
    assertEquals(0.0, chatOptions.get().get("temperature"));

    JsonNode restored = json(request("PATCH", "/config", "{\"temperature\":null}"));
    assertTrue(restored.path("temperature").isNull());
    assertEquals(0.85, restored.path("effectiveTemperature").asDouble());
    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"Continue\"}").statusCode());
    assertEquals(0.85, chatOptions.get().get("temperature"));
  }

  @Test
  @DisplayName("""
    Given a server response limit and a per-session override,
    When turns are submitted,
    Then the session cap, the explicit limit-free choice and the inherited server limit reach the model
    """)
  void shouldApplyPerSessionMaxTokensToNextTurn() throws Exception {
    startSession("chat.maxTokens=256\n");
    JsonNode original = json(request("GET", "/config", null));
    assertTrue(original.path("maxTokens").isNull(), "A fresh session inherits the server limit");
    assertEquals(256, original.path("effectiveMaxTokens").asInt());
    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"Hello\"}").statusCode());
    assertEquals(256, chatOptions.get().get("max_tokens"), "The server default caps the turn");

    assertEquals(200, request("PATCH", "/config", "{\"maxTokens\":64}").statusCode());
    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"Continue\"}").statusCode());
    assertEquals(64, chatOptions.get().get("max_tokens"), "The session cap overrides the server default");

    JsonNode unlimited = json(request("PATCH", "/config", "{\"maxTokens\":0}"));
    assertEquals(0, unlimited.path("maxTokens").asInt(), "Zero persists as an explicit choice");
    assertEquals(0, unlimited.path("effectiveMaxTokens").asInt(), "Zero resolves to no limit");
    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"Finish\"}").statusCode());
    assertFalse(chatOptions.get().containsKey("max_tokens"),
      "Zero sends no limit at all, even when the server config caps responses");

    JsonNode restored = json(request("PATCH", "/config", "{\"maxTokens\":null}"));
    assertTrue(restored.path("maxTokens").isNull());
    assertEquals(256, restored.path("effectiveMaxTokens").asInt(), "Null returns to the server limit");
    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"Once more\"}").statusCode());
    assertEquals(256, chatOptions.get().get("max_tokens"), "The inherited server limit caps the turn again");
  }

  @Test
  @DisplayName("""
    Given a server context default and a session that overrides it,
    When configuration is read and patched,
    Then the raw and effective context choices and the context report stay in step
    """)
  void shouldExposeAndPatchContextSettings() throws Exception {
    startSession("chat.contextWindow=8192\n");
    JsonNode original = json(request("GET", "/config", null));
    assertTrue(original.path("contextMode").isNull(), "A fresh session inherits the server's context mode");
    assertEquals("turns", original.path("effectiveContextMode").asText());
    assertTrue(original.path("contextWindow").isNull(), "A fresh session inherits the server's context window");
    assertEquals(8192, original.path("effectiveContextWindow").asInt(), "The server window is the effective one");
    assertEquals(ApiVersion.read(), original.path("appVersion").asText(),
      "The configuration names the build that answers, so an interface can be matched to its artefact");

    JsonNode rolling = json(request("PATCH", "/config", "{\"contextMode\":\"rolling\",\"contextWindow\":200000}"));
    assertEquals("rolling", rolling.path("contextMode").asText(), "The session choice persists as its own value");
    assertEquals("rolling", rolling.path("effectiveContextMode").asText());
    assertEquals(200000, rolling.path("effectiveContextWindow").asInt());

    JsonNode context = json(request("GET", "/state", null)).path("context");
    assertEquals("rolling", context.path("mode").asText());
    assertEquals(200000, context.path("contextWindow").asInt());
    assertTrue(context.path("requiredTokens").asInt() > 0, "Prompts and reserved reply space are measured");
    assertEquals(0, context.path("totalMessages").asInt(), "Nothing is persisted yet");
    assertEquals(0, context.path("verbatimMessages").asInt());
    assertEquals(0, context.path("summarizedMessages").asInt());
    assertEquals("", context.path("warning").asText(), "A window this large leaves nothing to warn about");

    JsonNode inherited = json(request("PATCH", "/config", "{\"contextMode\":null,\"contextWindow\":null}"));
    assertTrue(inherited.path("contextMode").isNull(), "Null returns the mode to the server default");
    assertEquals("turns", inherited.path("effectiveContextMode").asText());
    assertEquals(8192, inherited.path("effectiveContextWindow").asInt(), "Null returns the window to the server default");
  }

  @Test
  @DisplayName("""
    Given a session that chose rolling mode and its own window,
    When a patch submits only one of the two context settings,
    Then only that setting changes and the other keeps the session's own choice
    """)
  void shouldPatchOneContextSettingWithoutTouchingTheOther() throws Exception {
    startSession("chat.contextWindow=8192\n");
    assertEquals(200, request("PATCH", "/config",
      "{\"contextMode\":\"rolling\",\"contextWindow\":200000}").statusCode());

    JsonNode windowOnly = json(request("PATCH", "/config", "{\"contextWindow\":16384}"));
    assertEquals("rolling", windowOnly.path("contextMode").asText(),
      "A patch carrying only the window must keep the mode this session chose");
    assertEquals(16384, windowOnly.path("effectiveContextWindow").asInt(), "The submitted window takes effect");

    JsonNode modeOnly = json(request("PATCH", "/config", "{\"contextMode\":\"turns\"}"));
    assertEquals("turns", modeOnly.path("contextMode").asText(), "The submitted mode takes effect");
    assertEquals(16384, modeOnly.path("effectiveContextWindow").asInt(),
      "A patch carrying only the mode must keep the window this session chose");

    JsonNode inherited = json(request("PATCH", "/config", "{\"contextWindow\":null}"));
    assertTrue(inherited.path("contextWindow").isNull(), "An explicit null still inherits the server window");
    assertEquals("turns", inherited.path("contextMode").asText(), "The mode remains the session's own choice");
  }

  @Test
  @DisplayName("""
    Given a server without a known context window,
    When a session claims rolling mode with and without a size,
    Then the sizeless request is refused and the sized one is accepted
    """)
  void shouldRequireAWindowSizeForAChosenRollingMode() throws Exception {
    startSession();

    HttpResponse<String> refused = request("PATCH", "/config", "{\"contextMode\":\"rolling\"}");
    assertEquals(400, refused.statusCode(), refused.body());
    assertEquals("invalid_request", json(refused).path("code").asText());
    assertTrue(json(refused).path("message").asText().toLowerCase().contains("context window"),
      "The error has to name the missing setting: " + refused.body());
    assertTrue(json(request("GET", "/config", null)).path("contextMode").isNull(),
      "A refused patch must leave the session inheriting");

    JsonNode accepted = json(request("PATCH", "/config",
      "{\"contextMode\":\"rolling\",\"contextWindow\":32768}"));
    assertEquals("rolling", accepted.path("contextMode").asText());
    assertEquals(32768, accepted.path("effectiveContextWindow").asInt());

    assertEquals(400, request("PATCH", "/config", "{\"contextWindow\":0}").statusCode(),
      "A rolling session cannot drop back to an unknown window");
    assertEquals(32768, json(request("GET", "/config", null)).path("effectiveContextWindow").asInt(),
      "The refused patch leaves the chosen window standing");
  }

  @Test
  @DisplayName("""
    Given rolling mode with a window too small for the context that always goes out,
    When turns are submitted and a fitting window is chosen,
    Then the answer warns, no history is sent verbatim, and the recent story returns with a larger window
    """)
  void shouldWarnAndDropHistoryWhenTheRollingWindowIsTooSmall() throws Exception {
    startSession("chat.contextMode=rolling\nchat.contextWindow=1024\n");
    HttpResponse<String> first = request("POST", "/turns", "{\"prompt\":\"First\"}");
    assertEquals(200, first.statusCode(), "A too small window warns instead of failing the turn");
    String warning = json(first).path("contextWarning").asText();
    assertTrue(warning.contains("context window"), "The turn answer carries the actionable warning: " + warning);
    assertEquals(2, chatMessages.get().size(), "Only the system message and the new prompt go out");

    assertEquals(200, request("PATCH", "/config", "{\"contextWindow\":200000}").statusCode());
    HttpResponse<String> second = request("POST", "/turns", "{\"prompt\":\"Second\"}");
    assertEquals(200, second.statusCode());
    assertEquals("", json(second).path("contextWarning").asText(), "A fitting window has nothing to warn about");
    assertEquals(4, chatMessages.get().size(), "The previous exchange goes out verbatim again");

    JsonNode context = json(request("GET", "/state", null)).path("context");
    assertEquals(4, context.path("totalMessages").asInt());
    assertEquals(4, context.path("verbatimMessages").asInt(), "Rolling mode fills the window rather than counting turns");
    assertEquals(0, context.path("summarizedMessages").asInt());
    assertEquals("", context.path("warning").asText());
  }

  @Test
  @DisplayName("""
    Given rolling mode without a known context window,
    When turns are submitted,
    Then the recent turns of turns mode still go out and the report explains the missing window
    """)
  void shouldFallBackToTurnsAndExplainAnUnknownWindow() throws Exception {
    startSession("chat.contextMode=rolling\n");
    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"First\"}").statusCode());

    JsonNode context = json(request("GET", "/state", null)).path("context");
    assertEquals("rolling", context.path("mode").asText());
    assertEquals(0, context.path("contextWindow").asInt(), "Zero means the window size is unknown");
    assertTrue(context.path("warning").asText().contains("needs a context window"),
      "The report says why rolling mode cannot measure: " + context.path("warning").asText());

    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"Second\"}").statusCode());
    assertEquals(4, chatMessages.get().size(),
      "An unknown window falls back to the recent turns of turns mode instead of dropping the story");
  }

  @Test
  @DisplayName("Given persisted turns, when history is read and undo is requested, then the latest exchange is removed without a model call")
  void shouldReadHistoryAndUndoWithoutModelInvocation() throws Exception {
    startSession();
    assertEquals(0, json(request("GET", "/history", null)).path("messages").size());
    assertEquals(204, request("POST", "/undo", "").statusCode());
    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"First\"}").statusCode());
    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"Second\"}").statusCode());
    int requestsBeforeRead = modelRequests.get();
    JsonNode messages = json(request("GET", "/history", null)).path("messages");
    assertEquals(4, messages.size());
    assertEquals(0, messages.get(0).path("messageIndex").asInt());
    assertEquals("user", messages.get(0).path("role").asText());
    assertEquals("First", messages.get(0).path("content").asText());
    assertEquals("assistant", messages.get(1).path("role").asText());
    assertEquals("Story response", messages.get(1).path("content").asText());
    assertEquals(false, messages.get(1).path("hasImage").asBoolean());
    assertEquals("Second", messages.get(2).path("content").asText());

    HttpResponse<String> undone = request("POST", "/undo", "");
    assertEquals(204, undone.statusCode());
    assertEquals("", undone.body());
    JsonNode remaining = json(request("GET", "/history", null)).path("messages");
    assertEquals(2, remaining.size());
    assertEquals("First", remaining.get(0).path("content").asText());
    assertEquals(requestsBeforeRead, modelRequests.get());
  }

  @Test
  @DisplayName("Given exchanges outside the recent window, when history is read and a turn references them, then only eligible exchanges are accepted")
  void shouldExposeReferenceWindowAndGuardReferencedPastExchanges() throws Exception {
    // A recent window of one turn leaves the first exchange (messageIndex 0) outside it, so it becomes referenceable.
    startSession("chat.maxRecentTurns=1\n");
    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"First\"}").statusCode());
    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"Second\"}").statusCode());

    JsonNode history = json(request("GET", "/history", null));
    assertEquals(4, history.path("messages").size());
    // referenceBefore is an exclusive cutoff: lastMessageIndex 3 + 1 - maxRecentTurns(1) * 2 == 2.
    assertEquals(2, history.path("referenceBeforeMessageIndex").asInt());

    // Guard checks run before a successful reference so the accepted turn cannot shift the window out from under them.
    // A message inside the recent window (messageIndex 2 >= referenceBefore) must be rejected.
    HttpResponse<String> insideWindow = request("POST", "/turns", "{\"prompt\":\"Third\",\"pastMessageIndexes\":[2]}");
    assertEquals(400, insideWindow.statusCode());
    assertEquals("invalid_request", json(insideWindow).path("code").asText());
    assertTrue(json(insideWindow).path("message").asText().contains("Only exchanges outside the recent story context"));
    // Selecting more than three past exchanges must be rejected before any other validation.
    HttpResponse<String> tooMany = request("POST", "/turns", "{\"prompt\":\"Fourth\",\"pastMessageIndexes\":[0,1,2,3]}");
    assertEquals(400, tooMany.statusCode());
    assertTrue(json(tooMany).path("message").asText().contains("Select at most three past exchanges"));
    // The first exchange (messageIndex 0) is outside the recent window and may be referenced.
    assertEquals(200, request("POST", "/turns", "{\"prompt\":\"Fifth\",\"pastMessageIndexes\":[0]}").statusCode());
  }

  @Test
  @DisplayName("Given more persisted turns than one page, when history is read with a limit and an older cursor, then it pages and reports whether older history remains")
  void shouldPageOlderHistoryAndReportHasOlder() throws Exception {
    startSession();
    for (String prompt : List.of("First", "Second", "Third", "Fourth")) {
      assertEquals(200, request("POST", "/turns", "{\"prompt\":\"" + prompt + "\"}").statusCode());
    }
    // Eight messages exist; the most recent page of four still leaves older messages behind.
    JsonNode recent = json(request("GET", "/history?limit=4", null));
    assertEquals(4, recent.path("messages").size());
    assertEquals(4, recent.path("messages").get(0).path("messageIndex").asInt(), "The read returns the newest window oldest-first");
    assertTrue(recent.path("hasOlder").asBoolean(), "Messages before the window still exist");
    // Paging before the oldest loaded message reaches the first message, so older history then ends.
    JsonNode oldest = json(request("GET", "/history?limit=4&before=4", null));
    assertEquals(4, oldest.path("messages").size());
    assertEquals(0, oldest.path("messages").get(0).path("messageIndex").asInt());
    assertFalse(oldest.path("hasOlder").asBoolean(), "The first message is loaded, so no older history remains");
    // A full read without a limit always reports having everything.
    assertFalse(json(request("GET", "/history", null)).path("hasOlder").asBoolean());
  }

  @Test
  @DisplayName("Given a non-positive history page parameter, when history is read, then the request is rejected as invalid")
  void shouldRejectInvalidHistoryPageParameters() throws Exception {
    startSession();
    assertEquals(400, request("GET", "/history?before=0", null).statusCode());
    assertEquals(400, request("GET", "/history?limit=-1", null).statusCode());
  }

  @Test
  @DisplayName("Given persisted memory and a graph, when configuration is patched, then state remains available and unchanged")
  void shouldExposePersistedMemoryAndGraphAndPreserveThemDuringConfigurationPatch() throws Exception {
    startSession();
    try (var connection = DriverManager.getConnection(config.databaseUrl(), "sa", "");
         var statement = connection.prepareStatement(
           "UPDATE session_memory SET summary_content = ?, recent_summary_content = ?, canonical_state_content = ? WHERE session_id = ?"
         )) {
      statement.setString(1, "Long-term memory");
      statement.setString(2, "Recent memory");
      statement.setString(3, "Canonical state");
      statement.setString(4, sessionId);
      statement.executeUpdate();
      try (var graph = connection.prepareStatement("UPDATE knowledge_graph SET revision = 7 WHERE session_id = ?")) {
        graph.setString(1, sessionId);
        graph.executeUpdate();
      }
    }
    JsonNode before = json(request("GET", "/state", null));
    assertEquals("Long-term memory", before.path("summary").asText());
    assertEquals("Recent memory", before.path("recentSummary").asText());
    assertEquals("Canonical state", before.path("canonicalState").asText());
    assertEquals(0, before.path("summaryCursor").asInt());
    assertEquals(0, before.path("recentSummaryCursor").asInt());
    assertEquals(0, before.path("canonicalStateCursor").asInt());
    assertEquals(7, before.path("knowledgeGraph").path("revision").asInt());
    assertEquals(200, request("PATCH", "/config", "{\"temperature\":1.3}").statusCode());
    String protagonists = "fixed_protagonists:\n  narrator:\n    name: Narrator\n";
    HttpResponse<String> patched = request("PATCH", "/config", JsonSupport.OBJECT_MAPPER.writeValueAsString(
      Map.of("fixedProtagonists", protagonists)
    ));
    assertEquals(200, patched.statusCode());
    assertEquals(protagonists.strip(), json(patched).path("fixedProtagonists").asText());
    assertEquals(1.3, json(patched).path("temperature").asDouble());
    assertEquals(before, json(request("GET", "/state", null)));
  }

  @Test
  @DisplayName("Given invalid configuration patches, when submitted, then JSON errors are returned and settings remain unchanged")
  void shouldRejectInvalidPatchesWithoutChangingConfiguration() throws Exception {
    startSession();
    JsonNode original = json(request("GET", "/config", null));
    for (String patch : List.of(
      "", "null", "[]", "{", "{} {}", "{\"unknown\":true}",
      "{\"temperature\":-0.1}", "{\"temperature\":2.1}", "{\"temperature\":\"0.5\"}",
      "{\"temperature\":true}", "{\"rules\":null}", "{\"systemPrompt\":0}",
      "{\"maxTokens\":-1}", "{\"maxTokens\":1000001}", "{\"maxTokens\":\"512\"}",
      "{\"maxTokens\":true}", "{\"maxTokens\":1.5}",
      "{\"contextMode\":\"bogus\"}", "{\"contextMode\":5}", "{\"contextWindow\":-1}",
      "{\"contextWindow\":100000001}", "{\"contextWindow\":1.5}", "{\"contextWindow\":\"4096\"}",
      "{\"rules\":\"  \"}", "{\"systemPrompt\":\"Changed\",\"fixedProtagonists\":\"invalid yaml\"}"
    )) {
      HttpResponse<String> response = request("PATCH", "/config", patch);
      assertEquals(400, response.statusCode(), patch);
      assertEquals("invalid_request", json(response).path("code").asText(), patch);
      assertEquals(original, json(request("GET", "/config", null)), patch);
    }
    assertEquals(original, json(request("PATCH", "/config", "{}")));
  }

  @Test
  @DisplayName("Given unknown or expired sessions, when any data endpoint is called, then a JSON not-found error is returned")
  void shouldRejectUnknownAndExpiredSessionsOnEveryNewEndpoint() throws Exception {
    startSession();
    String activeId = sessionId;
    sessionId = "missing";
    assertSessionNotFound();
    sessionId = activeId;
    try (var connection = DriverManager.getConnection(config.databaseUrl(), "sa", "");
         var statement = connection.prepareStatement("UPDATE story_session SET expires_at = ? WHERE session_id = ?")) {
      statement.setTimestamp(1, java.sql.Timestamp.from(java.time.Instant.EPOCH));
      statement.setString(2, sessionId);
      statement.executeUpdate();
    }
    assertSessionNotFound();
  }

  private void assertSessionNotFound() throws Exception {
    for (String suffix : List.of("/history", "/config", "/state", "/undo")) {
      HttpResponse<String> response = request(suffix.equals("/undo") ? "POST" : "GET", suffix, "");
      assertEquals(404, response.statusCode(), suffix);
      assertEquals("session_not_found", json(response).path("code").asText());
    }
    HttpResponse<String> patch = request("PATCH", "/config", "{}");
    assertEquals(404, patch.statusCode());
    assertEquals("session_not_found", json(patch).path("code").asText());
  }

  private void startSession() throws Exception {
    startSession("");
  }

  private void startSession(String extraCoreConfig) throws Exception {
    Path coreOverride = temporaryDirectory.resolve("core.config");
    Files.writeString(coreOverride,
      "validation.enabled=false\ngraph.enabled=false\nchat.temperature=0.85\n" + extraCoreConfig);
    config = new ApiConfig("127.0.0.1", 0, temporaryDirectory.resolve("database/api"), "sa", "", Duration.ofMinutes(60));
    ChatClient model = (messages, options, timeout) -> {
      modelRequests.incrementAndGet();
      chatOptions.set(Map.copyOf(options));
      chatMessages.set(List.copyOf(messages));
      return "Story response";
    };
    server = ApiServer.create(config, AppConfigLoader.load(temporaryDirectory, coreOverride), model, model);
    server.start();
    HttpResponse<String> created = client.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/sessions"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}"))
        .build(), HttpResponse.BodyHandlers.ofString()
    );
    assertEquals(201, created.statusCode());
    sessionId = json(created).path("sessionId").asText();
  }

  private HttpResponse<String> request(String method, String suffix, String body) throws Exception {
    return client.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/sessions/" + sessionId + suffix))
        .header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        .build(), HttpResponse.BodyHandlers.ofString()
    );
  }

  private JsonNode json(HttpResponse<String> response) throws Exception {
    assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), response.body());
    return JsonSupport.OBJECT_MAPPER.readTree(response.body());
  }
}
