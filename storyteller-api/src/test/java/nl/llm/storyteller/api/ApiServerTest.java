package nl.llm.storyteller.api;

import com.fasterxml.jackson.databind.JsonNode;
import nl.llm.storyteller.core.JsonSupport;
import nl.llm.storyteller.core.config.AppConfigLoader;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.service.ChatClient;
import nl.llm.storyteller.db.Database;
import nl.llm.storyteller.db.JdbcSessionMemoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerTest {
  private static final String EMPTY_GRAPH = """
    {"schemaVersion":1,"revision":0,"entities":{},"facts":[]}
    """;

  private static final String AUTHORITATIVE_GRAPH = """
    {
      "schemaVersion": 1,
      "revision": 0,
      "entities": {
        "character.alice": { "type": "CHARACTER", "name": "Alice", "aliases": [], "source": "FIXED_PROTAGONIST" },
        "item.compass": { "type": "ITEM", "name": "Compass", "aliases": [], "source": "FIXED_PROTAGONIST" }
      },
      "facts": [{
        "id": "fact.alice_possesses_compass",
        "subject": "character.alice",
        "predicate": "POSSESSES",
        "object": "item.compass",
        "polarity": "POSITIVE",
        "status": "ACTIVE",
        "source": "FIXED_PROTAGONIST",
        "sourceTurn": 0,
        "hard": true
      }]
    }
    """;

  private static final String GRAPH_FACTS_HEADER =
    "Knowledge graph facts (authoritative; do not transfer traits between characters):";
  private static final String COMPASS_FACT = "- Alice possesses Compass.";

  @TempDir
  Path temporaryDirectory;

  private ApiServer server;

  @AfterEach
  void stopApplication() {
    if (server != null) {
      server.close();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"http://localhost:3000", "http://localhost:3001", "http://127.0.0.1:5173"})
  void shouldAllowStaticFrontendPreflightsAndSessionCookies(String origin) throws Exception {
    server = ApiServer.create(config());
    server.start();
    HttpClient client = HttpClient.newHttpClient();
    for (String method : List.of("POST", "PATCH", "PUT")) {
      HttpResponse<String> preflight = client.send(
        HttpRequest.newBuilder(uri("/v1/sessions"))
          .header("Origin", origin)
          .header("Access-Control-Request-Method", method)
          .header("Access-Control-Request-Headers", "content-type")
          .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
      assertEquals(200, preflight.statusCode());
      assertEquals(origin, preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
      assertEquals("true", preflight.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
      assertEquals(method, preflight.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
      assertEquals("content-type", preflight.headers().firstValue("Access-Control-Allow-Headers").orElseThrow());
    }
    HttpResponse<String> created = client.send(
      HttpRequest.newBuilder(uri("/v1/sessions"))
        .header("Origin", origin)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
      HttpResponse.BodyHandlers.ofString());
    assertEquals(201, created.statusCode());
    assertEquals(origin, created.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    String cookie = created.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    HttpResponse<String> restored = client.send(
      HttpRequest.newBuilder(uri("/v1/session"))
        .header("Origin", origin).header("Cookie", cookie).GET().build(),
      HttpResponse.BodyHandlers.ofString());
    assertEquals(200, restored.statusCode());
    assertEquals("true", restored.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
    assertEquals(JsonSupport.OBJECT_MAPPER.readTree(created.body()).path("sessionId"),
      JsonSupport.OBJECT_MAPPER.readTree(restored.body()).path("sessionId"));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"http://untrusted.example", "http://localhost.evil.example:3000", "http://127.0.0.1.evil.example:3000"})
  void shouldPreserveSameOriginRequestsAndRejectOtherOrigins(String rejectedOrigin) throws Exception {
    server = ApiServer.create(config());
    server.start();
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> sameOrigin = client.send(
      HttpRequest.newBuilder(uri("/v1/sessions"))
        .header("Origin", uri("").toString()).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(201, sameOrigin.statusCode());
    HttpResponse<String> rejected = client.send(
      HttpRequest.newBuilder(uri("/v1/sessions"))
        .header("Origin", rejectedOrigin).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(403, rejected.statusCode());
    assertTrue(rejected.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
  }

  @Test
  @DisplayName("""
    Given a running Storyteller API,
    When a client creates a session and retrieves it through the returned cookie,
    Then it should return the same active session and renew the cookie
    """)
  void shouldCreateAndRetrieveCurrentSession() throws Exception {
    server = ApiServer.create(config());
    server.start();
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest createRequest = HttpRequest.newBuilder(uri("/v1/sessions"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"  My story  \"}"))
      .build();

    HttpResponse<String> created = client.send(createRequest, HttpResponse.BodyHandlers.ofString());
    String cookie = created.headers().firstValue("Set-Cookie").orElseThrow();
    String cookiePair = cookie.substring(0, cookie.indexOf(';'));
    HttpRequest currentRequest = HttpRequest.newBuilder(uri("/v1/session"))
      .header("Cookie", cookiePair)
      .GET()
      .build();
    HttpResponse<String> current = client.send(currentRequest, HttpResponse.BodyHandlers.ofString());

    JsonNode createdBody = JsonSupport.OBJECT_MAPPER.readTree(created.body());
    JsonNode currentBody = JsonSupport.OBJECT_MAPPER.readTree(current.body());
    assertEquals(201, created.statusCode());
    assertEquals(200, current.statusCode());
    assertEquals("My story", createdBody.path("title").asText());
    assertEquals(createdBody.path("sessionId").asText(), currentBody.path("sessionId").asText());
    assertTrue(current.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly"));
  }

  @Test
  @DisplayName("""
    Given a request without an active-session cookie,
    When the current session endpoint is called,
    Then it should return a structured not-found response
    """)
  void shouldReturnNotFoundWithoutSessionCookie() throws Exception {
    server = ApiServer.create(config());
    server.start();
    HttpRequest request = HttpRequest.newBuilder(uri("/v1/session")).GET().build();

    HttpResponse<String> response = HttpClient.newHttpClient()
      .send(request, HttpResponse.BodyHandlers.ofString());

    JsonNode body = JsonSupport.OBJECT_MAPPER.readTree(response.body());
    assertEquals(404, response.statusCode());
    assertEquals("session_not_found", body.path("code").asText());
  }

  @Test
  @DisplayName("""
    Given an active API session and a responding model backend,
    When two story turns are submitted,
    Then it should return and persist each response while supplying the first turn as context for the second
    """)
  void shouldSubmitStoryTurnsWithSessionHistory() throws Exception {
    Path coreOverride = temporaryDirectory.resolve("core.config");
    Files.writeString(coreOverride, "validation.enabled=false\n");
    RecordingChatClient chatClient = new RecordingChatClient(List.of("First response", "Second response"));
    server = ApiServer.create(
      config(),
      AppConfigLoader.load(temporaryDirectory, coreOverride),
      chatClient,
      chatClient
    );
    server.start();
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> created = client.send(
      HttpRequest.newBuilder(uri("/v1/sessions"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    String sessionId = JsonSupport.OBJECT_MAPPER.readTree(created.body()).path("sessionId").asText();

    HttpResponse<String> first = submitTurn(client, sessionId, "First prompt");
    HttpResponse<String> second = submitTurn(client, sessionId, "Second prompt");

    JsonNode firstBody = JsonSupport.OBJECT_MAPPER.readTree(first.body());
    JsonNode secondBody = JsonSupport.OBJECT_MAPPER.readTree(second.body());
    assertEquals(200, first.statusCode());
    assertEquals("First response", firstBody.path("response").asText());
    assertEquals(0, firstBody.path("userMessageIndex").asInt());
    assertEquals(1, firstBody.path("assistantMessageIndex").asInt());
    assertEquals("Second response", secondBody.path("response").asText());
    assertEquals(2, secondBody.path("userMessageIndex").asInt());
    assertEquals(3, secondBody.path("assistantMessageIndex").asInt());
    assertEquals(List.of("system", "user", "assistant", "user"),
      chatClient.requests().get(1).stream().map(Message::role).toList());
  }

  @Test
  @DisplayName("""
    Given an active API session holding an authoritative knowledge graph fact,
    When two story turns run with validation enabled, one without and one with a mention of the known character,
    Then each story prompt should be grounded in the facts mentioned by its prompt while validation is also grounded in the facts mentioned by the draft
    """)
  void shouldGroundStoryAndValidationPromptsInKnowledgeGraphFacts() throws Exception {
    Path coreOverride = temporaryDirectory.resolve("graph-grounding-core.config");
    Files.writeString(coreOverride, "validation.enabled=true\n");
    RecordingChatClient storyClient = new RecordingChatClient(List.of(
      "Alice hides the Compass.", "The Compass stays hidden."
    ));
    RecordingChatClient validationClient = new RecordingChatClient(List.of(
      "{\"decision\":\"ALLOW\"}", "{\"decision\":\"ALLOW\"}"
    ));
    server = ApiServer.create(
      config(), AppConfigLoader.load(temporaryDirectory, coreOverride), storyClient, validationClient
    );
    server.start();
    HttpClient client = HttpClient.newHttpClient();
    String sessionId = createSession(client);
    HttpResponse<String> stored = replaceGraph(client, sessionId, AUTHORITATIVE_GRAPH);

    HttpResponse<String> unmentioned = submitTurn(client, sessionId, "What happens next?");
    HttpResponse<String> mentioned = submitTurn(client, sessionId, "What does Alice carry?");

    assertEquals(200, stored.statusCode());
    assertEquals(200, unmentioned.statusCode());
    assertEquals("Alice hides the Compass.",
      JsonSupport.OBJECT_MAPPER.readTree(unmentioned.body()).path("response").asText());
    assertEquals(200, mentioned.statusCode());
    String unmentionedSystemPrompt = storyClient.requests().get(0).getFirst().content();
    String mentionedSystemPrompt = storyClient.requests().get(1).getFirst().content();
    String validationRequest = validationClient.requests().get(0).get(1).content();
    assertFalse(unmentionedSystemPrompt.contains(GRAPH_FACTS_HEADER));
    assertTrue(validationRequest.contains(GRAPH_FACTS_HEADER));
    assertTrue(validationRequest.contains(COMPASS_FACT));
    assertTrue(mentionedSystemPrompt.contains(GRAPH_FACTS_HEADER));
    assertTrue(mentionedSystemPrompt.contains(COMPASS_FACT));
  }

  @Test
  @DisplayName("""
    Given an active API session holding an authoritative knowledge graph fact while graph.enabled is false,
    When a story turn mentions the known character,
    Then the story prompt should not contain any knowledge graph grounding
    """)
  void shouldNotGroundStoryPromptWhenKnowledgeGraphIsDisabled() throws Exception {
    Path coreOverride = temporaryDirectory.resolve("graph-disabled-core.config");
    Files.writeString(coreOverride, "graph.enabled=false\nvalidation.enabled=false\n");
    RecordingChatClient chatClient = new RecordingChatClient(List.of("The night goes on."));
    server = ApiServer.create(
      config(), AppConfigLoader.load(temporaryDirectory, coreOverride), chatClient, chatClient
    );
    server.start();
    HttpClient client = HttpClient.newHttpClient();
    String sessionId = createSession(client);
    HttpResponse<String> stored = replaceGraph(client, sessionId, AUTHORITATIVE_GRAPH);

    HttpResponse<String> turn = submitTurn(client, sessionId, "What does Alice carry?");

    assertEquals(200, stored.statusCode());
    assertEquals(200, turn.statusCode());
    assertFalse(chatClient.requests().getFirst().getFirst().content().contains(GRAPH_FACTS_HEADER));
  }

  @Test
  @DisplayName("""
    Given an active API session holding an authoritative knowledge graph fact while the core graph switch stays on,
    When the per-session knowledge graph toggle is turned off and a story turn mentions the known character, then turned back on,
    Then the story prompt should drop the graph grounding while off and regain it once re-enabled
    """)
  void shouldHonourPerSessionKnowledgeGraphToggle() throws Exception {
    Path coreOverride = temporaryDirectory.resolve("graph-per-session-toggle.config");
    Files.writeString(coreOverride, "validation.enabled=false\n");
    RecordingChatClient storyClient = new RecordingChatClient(List.of(
      "The Compass stays hidden.", "Alice carries the Compass."
    ));
    server = ApiServer.create(
      config(), AppConfigLoader.load(temporaryDirectory, coreOverride), storyClient, storyClient
    );
    server.start();
    HttpClient client = HttpClient.newHttpClient();
    String sessionId = createSession(client);
    HttpResponse<String> stored = replaceGraph(client, sessionId, AUTHORITATIVE_GRAPH);

    HttpResponse<String> off = patchConfig(client, sessionId, "{\"graphEnabled\":false}");
    HttpResponse<String> offTurn = submitTurn(client, sessionId, "What does Alice carry?");
    HttpResponse<String> on = patchConfig(client, sessionId, "{\"graphEnabled\":true}");
    HttpResponse<String> onTurn = submitTurn(client, sessionId, "What does Alice carry?");

    assertEquals(200, stored.statusCode());
    assertEquals(200, offTurn.statusCode());
    assertEquals(200, onTurn.statusCode());
    assertFalse(JsonSupport.OBJECT_MAPPER.readTree(off.body()).path("effectiveGraphEnabled").asBoolean(),
      "An explicit per-session off must be reported effective while the core switch stays on");
    assertTrue(JsonSupport.OBJECT_MAPPER.readTree(on.body()).path("effectiveGraphEnabled").asBoolean(),
      "Re-enabling per session must be reported effective again");
    assertFalse(storyClient.requests().get(0).getFirst().content().contains(GRAPH_FACTS_HEADER),
      "A per-session off choice must not ground the story prompt even with an authoritative graph stored");
    assertTrue(storyClient.requests().get(1).getFirst().content().contains(GRAPH_FACTS_HEADER),
      "Turning the toggle back on grounds the story prompt again");
  }

  @Test
  @DisplayName("""
    Given the server-rendered web interface,
    When a visitor creates a session and submits a prompt,
    Then it should render the prompt and response in the responsive story page
    """)
  void shouldRenderAndUseStoryPage() throws Exception {
    Path coreOverride = temporaryDirectory.resolve("web-core.config");
    Files.writeString(coreOverride, "validation.enabled=true\n");
    RecordingChatClient storyClient = new RecordingChatClient(List.of("A door opens in the old library."));
    RecordingChatClient validationClient = new RecordingChatClient(List.of("{\"decision\":\"ALLOW\"}"));
    server = ApiServer.create(
      config(),
      AppConfigLoader.load(temporaryDirectory, coreOverride),
      storyClient,
      validationClient
    );
    server.start();
    HttpClient client = HttpClient.newHttpClient();

    HttpResponse<String> start = client.send(
      HttpRequest.newBuilder(uri("/")).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<String> created = client.send(
      HttpRequest.newBuilder(uri("/web/sessions"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString("title=The+Library"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    String cookie = created.headers().firstValue("Set-Cookie").orElseThrow();
    String cookiePair = cookie.substring(0, cookie.indexOf(';'));
    HttpResponse<String> settings = client.send(
      HttpRequest.newBuilder(uri("/story/settings"))
        .header("Cookie", cookiePair)
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<String> resetTurnBasedFacts = client.send(
      HttpRequest.newBuilder(uri("/story/settings/graph/reset-turn-based"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Cookie", cookiePair)
        .POST(HttpRequest.BodyPublishers.ofString(settingsForm(
          "System before reset",
          "fixed_protagonists:\n  Valerie:\n    role: Protagonist",
          "Rules before reset",
          EMPTY_GRAPH
        )))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<String> memory = client.send(
      HttpRequest.newBuilder(uri("/story/memory"))
        .header("Cookie", cookiePair)
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<String> savedSettings = client.send(
      HttpRequest.newBuilder(uri("/story/settings"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Cookie", cookiePair)
        .POST(HttpRequest.BodyPublishers.ofString(settingsForm(
          "Edited system",
          "fixed_protagonists:\n  Valerie:\n    role: Edited protagonists",
          "Edited rules",
          EMPTY_GRAPH
        )))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<String> rejectedSettings = client.send(
      HttpRequest.newBuilder(uri("/story/settings"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Cookie", cookiePair)
        .POST(HttpRequest.BodyPublishers.ofString(settingsForm(
          "Should not be saved",
          "fixed_protagonists:\n  Valerie:\n    role: [broken",
          "Should not be saved",
          EMPTY_GRAPH
        )))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    byte[] png = java.util.Base64.getDecoder().decode(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jVj8AAAAASUVORK5CYII=");
    String boundary = "storyteller-test-boundary";
    HttpResponse<String> submitted = client.send(
      HttpRequest.newBuilder(uri("/story/turns"))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header("Cookie", cookiePair)
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartTurn(boundary, "Open the door", png)))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<String> edited = client.send(
      HttpRequest.newBuilder(uri("/v1/sessions/" + cookiePair.substring(cookiePair.indexOf('=') + 1)
        + "/messages/1/edit"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"Edited library response\"}"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<String> story = client.send(
      HttpRequest.newBuilder(uri("/story")).header("Cookie", cookiePair).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<byte[]> storedImage = client.send(
      HttpRequest.newBuilder(uri("/story/images/0")).header("Cookie", cookiePair).GET().build(),
      HttpResponse.BodyHandlers.ofByteArray()
    );
    HttpResponse<byte[]> exported = client.send(
      HttpRequest.newBuilder(uri("/export")).header("Cookie", cookiePair).GET().build(),
      HttpResponse.BodyHandlers.ofByteArray()
    );

    assertEquals(200, start.statusCode());
    assertTrue(start.body().contains("Start a story"));
    assertEquals(303, created.statusCode());
    assertEquals(200, settings.statusCode());
    assertTrue(settings.body().contains("Story settings"));
    assertTrue(settings.body().contains("Knowledge graph (JSON)"));
    assertTrue(settings.body().contains("id=\"graphEnabled\""));
    assertTrue(java.util.regex.Pattern.compile("<input[^>]*id=\"graphEnabled\"[^>]*checked").matcher(settings.body()).find(),
      "A fresh session should render the knowledge graph toggle checked because it inherits the enabled core default");
    assertTrue(settings.body().contains("formaction=\"/story/settings/graph/generate-empty\""));
    assertTrue(settings.body().contains("Generate empty"));
    assertTrue(settings.body().contains("formaction=\"/story/settings/graph/fill-fixed-protagonists\""));
    assertTrue(settings.body().contains("Fill from protagonists"));
    assertTrue(settings.body().contains("formaction=\"/story/settings/graph/reset-turn-based\""));
    assertTrue(settings.body().contains("Reset turn-based"));
    assertTrue(settings.body().contains("Replace it with a minimal empty graph?"));
    assertTrue(settings.body().contains("Replace it with facts generated from Fixed protagonists?"));
    assertTrue(settings.body().contains("Remove all turn-based facts and entities?"));
    assertFalse(settings.body().contains(">Cancel</a>"));
    assertEquals(303, resetTurnBasedFacts.statusCode());
    assertTrue(resetTurnBasedFacts.headers().firstValue("Location").orElseThrow()
      .contains("notificationTitle=Turn-based+facts+reset"));
    assertFalse(settings.body().contains("Canonical state"));
    assertEquals(200, memory.statusCode());
    assertTrue(memory.body().contains("Mid-term history"));
    assertTrue(memory.body().contains("Long-term history"));
    assertTrue(memory.body().contains("Canonical state"));
    assertEquals(303, savedSettings.statusCode());
    assertEquals(200, rejectedSettings.statusCode());
    assertTrue(rejectedSettings.body().contains("Invalid YAML at line"));
    assertTrue(rejectedSettings.body().contains("Should not be saved"));
    assertTrue(rejectedSettings.body().contains("setSelectionRange"));
    assertEquals(303, submitted.statusCode());
    assertEquals(200, edited.statusCode());
    assertEquals(200, story.statusCode());
    assertTrue(story.body().contains("The Library"));
    assertTrue(story.body().contains("Open the door"));
    assertFalse(story.body().contains("A door opens in the old library."));
    assertTrue(story.body().contains("Edited library response"));
    assertTrue(story.body().contains("action=\"/story/messages/1/edit\""));
    assertTrue(story.body().contains("Save response"));
    assertEquals(1, storyClient.requests().size());
    assertTrue(story.body().contains("class=\"prompt-thumbnail\""));
    assertTrue(story.body().contains("href=\"/story/images/0\""));
    assertTrue(story.body().contains("id=\"image-lightbox\""));
    assertTrue(story.body().contains("const closeImageLightbox"));
    assertFalse(story.body().contains("target=\"_blank\""));
    assertEquals(200, storedImage.statusCode());
    assertEquals("image/png", storedImage.headers().firstValue("Content-Type").orElseThrow());
    assertTrue(java.util.Arrays.equals(png, storedImage.body()));
    assertTrue(java.util.Arrays.equals(png, archiveEntry(exported.body(), "memory/images/000.png")));
    assertTrue(story.body().contains("class=\"exchange\""));
    assertTrue(story.body().contains("conversation.scrollTop = latestTop"));
    assertTrue(story.body().contains("response-width-toggle"));
    assertTrue(story.body().contains("response-layout-toggle"));
    assertTrue(story.body().contains("response-maximized"));
    assertTrue(story.body().contains("single-column"));
    assertTrue(story.body().contains("event.shiftKey"));
    assertTrue(story.body().contains("const actionButtons"));
    assertTrue(story.body().contains("actionButtons.forEach(button => button.disabled = true)"));
    assertTrue(story.body().contains("actionLinks.forEach(link => link.setAttribute(\"aria-disabled\", \"true\"))"));
    assertTrue(story.body().contains("formaction=\"/story/undo\""));
    assertTrue(story.body().contains("Stop story"));
    assertTrue(story.body().contains("permanently deleted"));
    assertTrue(story.body().contains("Infinite"));
    assertTrue(story.body().contains("Settings"));
    assertTrue(story.body().contains("History"));
    assertTrue(story.body().contains("prompt.addEventListener(\"paste\""));
    assertTrue(story.body().contains("name=\"image\""));
    assertFalse(story.body().contains("imageInput.disabled = true"));
    String modelSystemPrompt = storyClient.requests().getFirst().getFirst().content();
    assertTrue(modelSystemPrompt.contains("Edited system"));
    assertTrue(modelSystemPrompt.contains("Edited protagonists"));
    assertTrue(storyClient.requests().getFirst().getLast().imageDataUrl().startsWith("data:image/png;base64,"));
    String validationRequest = validationClient.requests().getFirst().getLast().content();
    assertTrue(validationRequest.contains("Edited rules"));
    assertTrue(validationRequest.contains("Edited protagonists"));

    HttpResponse<String> undone = client.send(
      HttpRequest.newBuilder(uri("/story/undo"))
        .header("Cookie", cookiePair)
        .POST(HttpRequest.BodyPublishers.noBody())
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<String> undoneStory = client.send(
      HttpRequest.newBuilder(uri("/story")).header("Cookie", cookiePair).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    );

    assertEquals(303, undone.statusCode());
    assertFalse(undoneStory.body().contains("Edited library response"));
    assertTrue(undoneStory.body().contains("data-undo-available=\"false\""));

    HttpResponse<String> infinite = client.send(
      HttpRequest.newBuilder(uri("/story/infinite"))
        .header("Cookie", cookiePair)
        .POST(HttpRequest.BodyPublishers.noBody())
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<String> infiniteStory = client.send(
      HttpRequest.newBuilder(uri("/story")).header("Cookie", cookiePair).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    );

    assertEquals(303, infinite.statusCode());
    assertTrue(infinite.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=2147483647"));
    assertTrue(infiniteStory.body().contains("Use timeout"));
    assertFalse(infiniteStory.body().contains("all stored story data will be permanently deleted"));
    String sessionId = cookiePair.substring(cookiePair.indexOf('=') + 1);
    assertTrue(infiniteStory.body().contains("Resume ID: <code>" + sessionId + "</code>"));

    HttpResponse<String> resumed = client.send(
      HttpRequest.newBuilder(uri("/web/sessions/resume"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString("sessionId=" + sessionId))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );

    assertEquals(303, resumed.statusCode());
    assertTrue(resumed.headers().firstValue("Set-Cookie").orElseThrow().contains(sessionId));

    HttpResponse<String> stopped = client.send(
      HttpRequest.newBuilder(uri("/story/stop"))
        .header("Cookie", cookiePair)
        .POST(HttpRequest.BodyPublishers.noBody())
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<String> retainedInfiniteStory = client.send(
      HttpRequest.newBuilder(uri("/story")).header("Cookie", cookiePair).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    );

    assertEquals(303, stopped.statusCode());
    assertTrue(stopped.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
    assertEquals(200, retainedInfiniteStory.statusCode());
    assertTrue(retainedInfiniteStory.body().contains("Resume ID: <code>" + sessionId + "</code>"));
  }

  @Test
  @DisplayName("Given a temporary model failure, when a web turn is retried, then the prompt is preserved but not duplicated")
  void shouldPreserveUnpersistedPromptAfterTemporaryModelFailure() throws Exception {
    Path coreOverride = temporaryDirectory.resolve("retry-core.config");
    Files.writeString(coreOverride, "validation.enabled=false\n");
    int[] attempts = {0};
    ChatClient chatClient = (_, _, _) -> {
      if (attempts[0]++ == 0) {
        throw new java.io.IOException("OpenAI-compatible backend returned HTTP 503: model unavailable");
      }
      return "Recovered response";
    };
    server = ApiServer.create(
      config(), AppConfigLoader.load(temporaryDirectory, coreOverride), chatClient, chatClient
    );
    server.start();
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> created = client.send(
      HttpRequest.newBuilder(uri("/web/sessions"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString("title=Retry+story"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    String cookie = created.headers().firstValue("Set-Cookie").orElseThrow();
    String cookiePair = cookie.substring(0, cookie.indexOf(';'));
    byte[] turn = multipartPastTurn("retry-boundary", "Keep this prompt", null);

    HttpResponse<String> failed = client.send(
      HttpRequest.newBuilder(uri("/story/turns"))
        .header("Content-Type", "multipart/form-data; boundary=retry-boundary")
        .header("Cookie", cookiePair)
        .POST(HttpRequest.BodyPublishers.ofByteArray(turn))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<String> retried = client.send(
      HttpRequest.newBuilder(uri("/story/turns"))
        .header("Content-Type", "multipart/form-data; boundary=retry-boundary")
        .header("Cookie", cookiePair)
        .POST(HttpRequest.BodyPublishers.ofByteArray(turn))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    HttpResponse<String> story = client.send(
      HttpRequest.newBuilder(uri("/story")).header("Cookie", cookiePair).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    );

    assertEquals(503, failed.statusCode());
    assertTrue(failed.body().contains("The model is temporarily unavailable"));
    assertTrue(failed.body().contains("id=\"model-backend-notification\""));
    assertTrue(failed.body().contains("role=\"alertdialog\""));
    assertTrue(failed.body().contains("window.setTimeout(closeBackendNotification, 8000)"));
    assertTrue(failed.body().contains(">Keep this prompt</textarea>"));
    assertFalse(failed.body().contains("class=\"exchange\""));
    assertEquals(303, retried.statusCode());
    assertTrue(story.body().contains("Keep this prompt"));
    assertTrue(story.body().contains("Recovered response"));
  }

  @Test
  @DisplayName("""
    Given an exchange older than the configured recent context,
    When it is selected while submitting a new web turn,
    Then its complete prompt and response should be sent once as clearly marked past context
    """)
  void shouldUseSelectedOldExchangeAsPastContext() throws Exception {
    Path coreOverride = temporaryDirectory.resolve("past-context.config");
    Files.writeString(coreOverride, "validation.enabled=false\n");
    RecordingChatClient storyClient = new RecordingChatClient(List.of(
      "Response zero", "Response one", "Response two", "Response three"
    ));
    server = ApiServer.create(
      config(), AppConfigLoader.load(temporaryDirectory, coreOverride), storyClient, storyClient
    );
    server.start();
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> created = client.send(
      HttpRequest.newBuilder(uri("/web/sessions"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString("title=Past+context"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    String cookie = created.headers().firstValue("Set-Cookie").orElseThrow();
    String cookiePair = cookie.substring(0, cookie.indexOf(';'));
    for (int turn = 0; turn < 3; turn++) {
      client.send(
        HttpRequest.newBuilder(uri("/story/turns"))
          .header("Content-Type", "multipart/form-data; boundary=past-boundary")
          .header("Cookie", cookiePair)
          .POST(HttpRequest.BodyPublishers.ofByteArray(multipartPastTurn(
            "past-boundary", "Prompt " + turn, null
          )))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      );
    }
    HttpResponse<String> story = client.send(
      HttpRequest.newBuilder(uri("/story")).header("Cookie", cookiePair).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    );
    client.send(
      HttpRequest.newBuilder(uri("/story/turns"))
        .header("Content-Type", "multipart/form-data; boundary=past-boundary")
        .header("Cookie", cookiePair)
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartPastTurn(
          "past-boundary", "Prompt 3", 0
        )))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );

    String systemMessage = storyClient.requests().getLast().getFirst().content();
    assertTrue(story.body().contains("name=\"pastExchange\" value=\"0\""));
    assertTrue(story.body().contains("0/3 past exchanges"));
    assertTrue(systemMessage.contains("RELEVANT PAST STORY EXCERPTS"));
    assertTrue(systemMessage.contains("[Earlier exchange, messages 0-1]"));
    assertTrue(systemMessage.contains("Prompt 0"));
    assertTrue(systemMessage.contains("Response zero"));
  }

  @Test
  @DisplayName("""
    Given a session in rolling context mode,
    When repeated turns no longer fit in the configured context window,
    Then every turn persists the absolute boundary of the history it still sent verbatim
    """)
  void shouldPersistAnAbsoluteRollingRequestFrontier() throws Exception {
    int turns = 8;
    Path coreOverride = temporaryDirectory.resolve("rolling-frontier.config");
    Files.writeString(coreOverride, """
      validation.enabled=false
      chat.contextMode=rolling
      chat.contextWindow=4096
      chat.responseReserveTokens=0
      """);
    List<String> responses = new ArrayList<>();
    for (int turn = 1; turn <= turns; turn++) {
      responses.add(rollingResponse(turn));
    }
    RecordingChatClient storyClient = new RecordingChatClient(responses);
    server = ApiServer.create(
      config(), AppConfigLoader.load(temporaryDirectory, coreOverride), storyClient, storyClient
    );
    server.start();
    HttpClient client = HttpClient.newHttpClient();
    String sessionId = createSession(client);

    int storedFrontier = 0;
    for (int turn = 0; turn < turns; turn++) {
      HttpResponse<String> submitted = submitTurn(client, sessionId, "Rolling prompt " + turn);
      assertEquals(200, submitted.statusCode());
      // The request is the system frame, the verbatim history and the current prompt.
      int verbatim = storyClient.requests().get(turn).size() - 2;
      int expectedFrontier = turn * 2 - verbatim;
      assertEquals(0, expectedFrontier % 2,
        "a frontier must sit on a turn boundary, so trimming never separates a reply from its prompt");
      assertTrue(expectedFrontier >= 0 && expectedFrontier <= turn * 2,
        "a frontier must stay inside the history this session has: " + expectedFrontier);
      int persistedFrontier = requestFrontier(sessionId);
      assertEquals(Math.max(storedFrontier, expectedFrontier), persistedFrontier,
        "turn " + turn + " must raise the frontier to the first message it still sent verbatim");
      storedFrontier = persistedFrontier;
    }

    assertTrue(storedFrontier > 0, "a story larger than the window must record that older turns rolled off");
    assertTrue(storedFrontier < (turns - 1) * 2,
      "the newest turn must still go out verbatim instead of only living in derived memory");
    List<Message> finalRequest = storyClient.requests().getLast();
    assertEquals("system", finalRequest.getFirst().role());
    assertEquals("Rolling prompt " + (turns - 1), finalRequest.getLast().content());
    assertFalse(finalRequest.stream().anyMatch(message -> message.content().contains("Rolling prompt 0")),
      "the oldest turn must have rolled out of the request once the window filled");
  }

  @Test
  @DisplayName("""
    Given a rolling session that already recorded a request frontier,
    When the model backend fails a turn,
    Then the failed turn leaves the frontier where it was
    """)
  void shouldLeaveTheRequestFrontierUnchangedWhenGenerationFails() throws Exception {
    int successfulTurns = 4;
    Path coreOverride = temporaryDirectory.resolve("rolling-failure.config");
    Files.writeString(coreOverride, """
      validation.enabled=false
      chat.contextMode=rolling
      chat.contextWindow=4096
      chat.responseReserveTokens=0
      """);
    int[] attempts = {0};
    ChatClient chatClient = (_, _, _) -> {
      if (attempts[0]++ < successfulTurns) {
        return rollingResponse(attempts[0]);
      }
      throw new java.io.IOException("OpenAI-compatible backend returned HTTP 503: model unavailable");
    };
    server = ApiServer.create(
      config(), AppConfigLoader.load(temporaryDirectory, coreOverride), chatClient, chatClient
    );
    server.start();
    HttpClient client = HttpClient.newHttpClient();
    String sessionId = createSession(client);
    for (int turn = 0; turn < successfulTurns; turn++) {
      assertEquals(200, submitTurn(client, sessionId, "Rolling prompt " + turn).statusCode());
    }

    int frontierBeforeFailure = requestFrontier(sessionId);
    assertTrue(frontierBeforeFailure > 0, "a frontier has to exist before a failure can leave it alone");

    HttpResponse<String> failed = submitTurn(client, sessionId, "Prompt after the failure");

    assertEquals(502, failed.statusCode());
    assertTrue(failed.body().contains("model_backend_error"));
    assertEquals(frontierBeforeFailure, requestFrontier(sessionId),
      "a turn that never reached the model must not move the frontier, because it dropped no history");
  }

  @ParameterizedTest
  @CsvSource({
    "'', , 0.6",
    "chat.temperature=0.85, , 0.85",
    "chat.temperature=0.85, 0.0, 0.0",
    "chat.temperature=0.85, 1.3, 1.3"
  })
  @DisplayName("Settings show the saved temperature or the effective configuration when unset")
  void shouldRenderEffectiveTemperature(String override, String savedTemperature, double expected) throws Exception {
    Path coreOverride = temporaryDirectory.resolve("temperature.config");
    Files.writeString(coreOverride, override);
    ChatClient chatClient = (_, _, _) -> "Unused";
    server = ApiServer.create(
      config(), AppConfigLoader.load(temporaryDirectory, coreOverride), chatClient, chatClient
    );
    server.start();
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> created = client.send(
      HttpRequest.newBuilder(uri("/web/sessions"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString("title=Temperature"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    assertEquals(303, created.statusCode());
    String cookie = created.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    String temperatureField = savedTemperature == null ? "" : "&temperature=" + savedTemperature;
    String protagonists = "fixed_protagonists:\n  Valerie:\n    role: Protagonist";
    HttpResponse<String> saved = client.send(
      HttpRequest.newBuilder(uri("/story/settings"))
        .header("Cookie", cookie)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(
          settingsForm("System", protagonists, "Rules", EMPTY_GRAPH) + temperatureField
        ))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    assertEquals(303, saved.statusCode());
    HttpResponse<String> settings = client.send(
      HttpRequest.newBuilder(uri("/story/settings")).header("Cookie", cookie).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    );
    assertTemperature(settings, expected);

    for (String invalidForm : List.of(
      settingsForm("System", "fixed_protagonists: [broken", "Rules", EMPTY_GRAPH),
      settingsForm("System", protagonists, "Rules", "{broken")
    )) {
      HttpResponse<String> rejected = client.send(
        HttpRequest.newBuilder(uri("/story/settings"))
          .header("Cookie", cookie)
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(invalidForm + temperatureField))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      );
      assertTemperature(rejected, expected);
    }
  }

  private void assertTemperature(HttpResponse<String> response, double expected) {
    assertEquals(200, response.statusCode());
    var slider = java.util.regex.Pattern.compile("<input[^>]*id=\"temperature\"[^>]*>")
      .matcher(response.body());
    assertTrue(slider.find(), "Temperature slider must be rendered");
    assertTrue(slider.group().contains("value=\"" + expected + "\""), slider.group());
    var output = java.util.regex.Pattern.compile("<output[^>]*id=\"temperatureValue\"[^>]*>(.*?)</output>",
      java.util.regex.Pattern.DOTALL).matcher(response.body());
    assertTrue(output.find(), "Temperature value must be rendered");
    assertEquals(String.format("%.2f", expected), output.group(1).trim());
  }

  private String settingsForm(
    String systemPrompt,
    String fixedProtagonists,
    String rules,
    String knowledgeGraph
  ) {
    return "systemPrompt=" + encode(systemPrompt)
      + "&fixedProtagonists=" + encode(fixedProtagonists)
      + "&rules=" + encode(rules)
      + "&knowledgeGraph=" + encode(knowledgeGraph);
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private byte[] multipartTurn(String boundary, String prompt, byte[] image) throws Exception {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(("--" + boundary + "\r\n"
      + "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n"
      + prompt + "\r\n"
      + "--" + boundary + "\r\n"
      + "Content-Disposition: form-data; name=\"image\"; filename=\"clipboard.png\"\r\n"
      + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    body.write(image);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return body.toByteArray();
  }

  private byte[] multipartPastTurn(String boundary, String prompt, Integer pastMessageIndex) throws Exception {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(("--" + boundary + "\r\n"
      + "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n"
      + prompt + "\r\n").getBytes(StandardCharsets.UTF_8));
    if (pastMessageIndex != null) {
      body.write(("--" + boundary + "\r\n"
        + "Content-Disposition: form-data; name=\"pastExchange\"\r\n\r\n"
        + pastMessageIndex + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return body.toByteArray();
  }

  private byte[] archiveEntry(byte[] archive, String expectedName) throws Exception {
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (expectedName.equals(entry.getName())) {
          return zip.readAllBytes();
        }
      }
    }
    return new byte[0];
  }

  private HttpResponse<String> submitTurn(HttpClient client, String sessionId, String prompt) throws Exception {
    return client.send(
      HttpRequest.newBuilder(uri("/v1/sessions/" + sessionId + "/turns"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"" + prompt + "\"}"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  private String createSession(HttpClient client) throws Exception {
    HttpResponse<String> created = client.send(
      HttpRequest.newBuilder(uri("/v1/sessions"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
    assertEquals(201, created.statusCode());
    return JsonSupport.OBJECT_MAPPER.readTree(created.body()).path("sessionId").asText();
  }

  private HttpResponse<String> replaceGraph(HttpClient client, String sessionId, String graph) throws Exception {
    return client.send(
      HttpRequest.newBuilder(uri("/v1/sessions/" + sessionId + "/graph"))
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(graph))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  private HttpResponse<String> patchConfig(HttpClient client, String sessionId, String json) throws Exception {
    return client.send(
      HttpRequest.newBuilder(uri("/v1/sessions/" + sessionId + "/config"))
        .header("Content-Type", "application/json")
        .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  private ApiConfig config() {
    return new ApiConfig(
      "127.0.0.1",
      0,
      temporaryDirectory.resolve("database/api"),
      "sa",
      "",
      Duration.ofMinutes(60)
    );
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  /** A reply long enough that the small rolling window used by the frontier tests fills after a couple of turns. */
  private static String rollingResponse(int turn) {
    return "Rolling response " + turn + ": " + "x".repeat(4000);
  }

  /** The durable request frontier of a session, read straight from the database the server writes to. */
  private int requestFrontier(String sessionId) {
    return new JdbcSessionMemoryRepository(
      new Database(config().databaseUrl(), "sa", "")
    ).load(sessionId).contextTargetIndex();
  }

  private static final class RecordingChatClient implements ChatClient {

    private final List<String> responses;
    private final List<List<Message>> requests = new ArrayList<>();

    private RecordingChatClient(List<String> responses) {
      this.responses = responses;
    }

    @Override
    public String chat(List<Message> messages, Map<String, Object> options, int timeoutSeconds) {
      requests.add(List.copyOf(messages));
      return responses.get(requests.size() - 1);
    }

    private List<List<Message>> requests() {
      return List.copyOf(requests);
    }
  }
}
