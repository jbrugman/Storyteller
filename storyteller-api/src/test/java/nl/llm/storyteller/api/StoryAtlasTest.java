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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryAtlasTest {
  private static final String GRAPH = """
    {"schemaVersion":1,"revision":0,"entities":{
      "nora":{"type":"CHARACTER","name":"Nora","aliases":[]},
      "linde":{"type":"CHARACTER","name":"Linde <script>","aliases":["Cartographer"]},
      "harbour":{"type":"LOCATION","name":"Old harbour","aliases":[]},
      "map":{"type":"ITEM","name":"Sea chart","aliases":[]},
      "sailing":{"type":"SKILL","name":"Sailing","aliases":[]}
    },"facts":[
      {"id":"distrust","subject":"linde","predicate":"TRUSTS","object":"nora","polarity":"NEGATIVE","status":"ACTIVE","source":"MANUAL","sourceTurn":null,"hard":false},
      {"id":"home","subject":"linde","predicate":"LIVES","object":"harbour","polarity":"POSITIVE","status":"ACTIVE","source":"FIXED_PROTAGONIST","sourceTurn":null,"hard":true},
      {"id":"proposal","subject":"nora","predicate":"LOVES","object":"linde","polarity":"POSITIVE","status":"PROPOSED","source":"TURNBASED","sourceTurn":2,"hard":false},
      {"id":"past","subject":"linde","predicate":"POSSESSES","object":"map","polarity":"POSITIVE","status":"RETRACTED","source":"MANUAL","sourceTurn":null,"hard":false},
      {"id":"replaced","subject":"linde","predicate":"CAN_PERFORM","object":"sailing","polarity":"POSITIVE","status":"SUPERSEDED","source":"MANUAL","sourceTurn":null,"hard":false}
    ]}
    """;

  @TempDir Path temporaryDirectory;
  private final HttpClient client = HttpClient.newHttpClient();
  private final AtomicInteger modelRequests = new AtomicInteger();
  private ApiServer server;
  private ApiConfig config;
  private String sessionId;
  private String cookie;

  @AfterEach void closeServer() {
    if (server != null) server.close();
  }

  @Test
  @DisplayName("""
    Given a stored graph with directed, negative, and non-active facts,
    When the atlas is read through the API and rendered by the web interface,
    Then fact semantics and stored data are preserved and rendered names are escaped
    """)
  void shouldShareStoredFactsAndPreserveDirectionPolarityAndStatuses() throws Exception {
    // Given
    start(true);
    // When
    var atlas = json(get("/v1/sessions/" + sessionId + "/atlas", false));
    // Then
    assertEquals(sessionId, atlas.path("sessionId").asText());
    assertEquals(1, atlas.path("revision").asLong());
    assertEquals(5, atlas.path("entries").size());
    var linde = entry(atlas, "linde");
    assertEquals("Linde <script>", atlas.path("entries").get(0).path("name").asText());
    assertEquals("Cartographer", linde.path("aliases").get(0).asText());
    assertEquals(5, linde.path("relations").size());
    var distrust = relation(linde, "distrust");
    assertEquals("Linde <script> does not trust Nora", distrust.path("text").asText());
    assertEquals("linde", distrust.path("subjectId").asText());
    assertEquals("nora", distrust.path("objectId").asText());
    assertEquals(distrust, relation(entry(atlas, "nora"), "distrust"));
    assertEquals("PROPOSED", relation(linde, "proposal").path("status").asText());
    assertEquals("RETRACTED", relation(linde, "past").path("status").asText());
    assertEquals("SUPERSEDED", relation(linde, "replaced").path("status").asText());

    var web = get("/story/atlas", true);
    assertEquals(200, web.statusCode());
    assertTrue(web.body().contains("Linde &lt;script&gt; does not trust Nora"));
    assertFalse(web.body().contains("Linde <script>"));
    assertTrue(web.body().contains("Proposed and previous facts (3)"));
    assertTrue(web.body().contains("href=\"/story/atlas?type=LOCATION&amp;entity=harbour\""));
    assertTrue(get("/story", true).body().contains("href=\"/story/atlas\""));
    assertEquals(0, modelRequests.get());
    var graph = json(get("/v1/sessions/" + sessionId + "/state", false)).path("knowledgeGraph");
    assertEquals(1, graph.path("revision").asLong());
    assertEquals(5, graph.path("facts").size());
  }

  @Test
  @DisplayName("""
    Given an atlas containing characters, a location, and aliases,
    When searches and category-specific pages are requested,
    Then only matching entries and correctly directed relations are shown
    """)
  void shouldSearchAliasesFollowIncomingRelationsAndHandleEmptyCategories() throws Exception {
    // Given
    start(true);
    // When
    String search = get("/story/atlas?q=cArToGrApHeR", true).body();
    // Then
    assertTrue(search.contains("Linde &lt;script&gt;"));
    assertFalse(search.contains("class=\"atlas-entry\" href=\"/story/atlas?type=CHARACTER&amp;q=cArToGrApHeR&amp;entity=nora\""));
    String location = get("/story/atlas?type=LOCATION&entity=harbour", true).body();
    assertTrue(location.contains("Linde &lt;script&gt; lives at Old harbour"));
    assertTrue(location.contains("type=CHARACTER&amp;entity=linde"));
    assertTrue(get("/story/atlas?type=SKILL&entity=sailing", true).body()
      .contains("No active facts have been recorded"));
    assertTrue(get("/story/atlas?q=missing", true).body().contains("No entries match"));
    assertTrue(get("/story/atlas?entity=missing&type=invalid", true).body().contains("Recorded facts"));
    assertEquals(0, modelRequests.get());
  }

  @Test
  @DisplayName("""
    Given an empty atlas and missing or expired sessions,
    When the API and web atlas routes are requested,
    Then empty content is handled and inactive sessions cannot access the atlas
    """)
  void shouldHandleEmptyGraphsAndRejectInactiveSessions() throws Exception {
    // Given
    start(false);
    // When
    var atlas = json(get("/v1/sessions/" + sessionId + "/atlas", false));
    // Then
    assertTrue(atlas.path("entries").isEmpty());
    assertTrue(get("/story/atlas", true).body().contains("No atlas entries yet"));
    assertEquals(303, get("/story/atlas", false).statusCode());
    assertEquals(404, get("/v1/sessions/missing/atlas", false).statusCode());
    try (var connection = DriverManager.getConnection(config.databaseUrl(), "sa", "");
         var statement = connection.prepareStatement("UPDATE story_session SET expires_at = ? WHERE session_id = ?")) {
      statement.setTimestamp(1, java.sql.Timestamp.from(java.time.Instant.EPOCH));
      statement.setString(2, sessionId);
      statement.executeUpdate();
    }
    assertEquals(404, get("/v1/sessions/" + sessionId + "/atlas", false).statusCode());
    assertEquals(303, get("/story/atlas", true).statusCode());
    assertEquals(0, modelRequests.get());
  }

  @Test
  @DisplayName("""
    Given saved flat and nested profiles with lists and graph facts,
    When the atlas is read and refreshed after a profile change,
    Then profile values and list formatting are visible without changing graph facts
    """)
  void shouldShowFlatAndNestedProfilesAlongsideGraphFactsAndRefreshSavedChanges() throws Exception {
    // Given
    start(true);
    String yaml = """
      fixed_protagonists:
        linde:
          full_name: Linde Thorne
          age: 28
          occupation: Cartographer
          residence: Inland villa
          appearance:
            eyes: '<b>Blue-green</b>'
            hair: Dark brown
          personality:
            traits: [Loyal, Playful]
          active: true
          score: 0
          values: [0, false, null]
          equipment:
            - name: Compass
              quantity: 1
            - name: Lantern
              quantity: 2
        Nora:
          profile:
            age: 27
            occupation: [Scientist, Physicist]
          fears: [Rejection]
        Mike:
          age: 29
          occupation: Pianist
      """;
    saveProfiles(yaml);
    var graphBefore = json(get("/v1/sessions/" + sessionId + "/state", false)).path("knowledgeGraph");
    // When
    var atlas = json(get("/v1/sessions/" + sessionId + "/atlas", false));
    // Then
    assertEquals(6, atlas.path("entries").size());
    assertEquals("", atlas.path("profileWarning").asText());
    var linde = entry(atlas, "linde");
    assertEquals("28", field(linde.path("profile"), "Age").path("value").asText());
    assertEquals("Inland villa", field(linde.path("profile"), "Residence").path("value").asText());
    assertEquals("<b>Blue-green</b>", field(field(linde.path("profile"), "Appearance").path("children"), "Eyes").path("value").asText());
    assertEquals("true", field(linde.path("profile"), "Active").path("value").asText());
    assertEquals("0", field(linde.path("profile"), "Score").path("value").asText());
    var traits = field(field(linde.path("profile"), "Personality").path("children"), "Traits");
    assertEquals("Loyal, Playful", traits.path("value").asText());
    assertTrue(traits.path("children").isEmpty());
    assertEquals("0, false, Not specified", field(linde.path("profile"), "Values").path("value").asText());
    var equipment = field(linde.path("profile"), "Equipment").path("children");
    assertEquals(2, equipment.size());
    assertEquals("Compass", field(equipment.get(0).path("children"), "Name").path("value").asText());
    assertEquals("2", field(equipment.get(1).path("children"), "Quantity").path("value").asText());
    assertEquals(5, linde.path("relations").size());
    var nested = field(entry(atlas, "nora").path("profile"), "Profile").path("children");
    assertEquals("27", field(nested, "Age").path("value").asText());
    assertEquals("Scientist, Physicist", field(nested, "Occupation").path("value").asText());
    assertTrue(field(nested, "Occupation").path("children").isEmpty());
    assertEquals("29", field(entry(atlas, "profile:Mike").path("profile"), "Age").path("value").asText());

    String web = get("/story/atlas?entity=linde", true).body();
    assertTrue(web.contains("Profile — Fixed protagonists"));
    assertTrue(web.contains("Recorded facts — Knowledge graph"));
    assertTrue(web.contains("Inland villa"));
    assertTrue(web.contains("Loyal, Playful"));
    assertTrue(web.contains("Compass"));
    assertTrue(get("/story/atlas?entity=nora", true).body().contains("Scientist, Physicist"));
    assertTrue(web.contains("Linde &lt;script&gt; lives at Old harbour"));
    assertTrue(web.contains("&lt;b&gt;Blue-green&lt;/b&gt;"));
    assertFalse(web.contains("<b>Blue-green</b>"));
    assertTrue(web.contains("src=\"/story-atlas.js\""));
    assertTrue(get("/story/atlas?q=Linde%20Thorne", true).body().contains("Inland villa"));
    saveProfiles(yaml.replace("age: 28", "age: 31"));
    var refreshed = json(get("/v1/sessions/" + sessionId + "/atlas", false));
    assertEquals("31", field(entry(refreshed, "linde").path("profile"), "Age").path("value").asText());
    assertEquals(graphBefore, json(get("/v1/sessions/" + sessionId + "/state", false)).path("knowledgeGraph"));
    assertEquals(yaml.replace("age: 28", "age: 31").strip(),
      json(get("/v1/sessions/" + sessionId + "/config", false)).path("fixedProtagonists").asText());
    assertEquals(0, modelRequests.get());
  }

  @Test
  @DisplayName("""
    Given a saved protagonist profile without graph entries,
    When the atlas is searched and a different category is requested,
    Then the profile remains searchable and does not appear in unrelated categories
    """)
  void shouldDisplayProfilesWithoutGraphAndKeepEmptyCategoriesEmpty() throws Exception {
    // Given
    start(false);
    saveProfiles("""
      fixed_protagonists:
        Valerie:
          profile:
            full_name: Valerie Vermeer
            age: 36
            occupation: [Scientist, Physicist]
      """);
    // When
    var atlas = json(get("/v1/sessions/" + sessionId + "/atlas", false));
    // Then
    assertEquals(1, atlas.path("entries").size());
    assertEquals(0, atlas.path("revision").asLong());
    assertTrue(entry(atlas, "profile:Valerie").path("relations").isEmpty());
    assertTrue(get("/story/atlas", true).body().contains("Scientist"));
    assertTrue(get("/story/atlas?type=LOCATION", true).body().contains("No entry selected"));
    assertFalse(get("/story/atlas?type=LOCATION", true).body().contains("Scientist"));
    assertTrue(get("/story/atlas?type=CHARACTER&q=Vermeer", true).body().contains("Scientist"));
    assertEquals(0, modelRequests.get());
  }

  @Test
  @DisplayName("""
    Given profiles with competing names and a name matching a non-character entry,
    When the atlas combines profiles and graph entries,
    Then ambiguous profiles stay separate and non-character entries gain no profile
    """)
  void shouldNeverMergeAmbiguousProfilesOrAttachThemToNonCharacters() throws Exception {
    // Given
    start(true);
    saveProfiles("""
      fixed_protagonists:
        First:
          name: Nora
          age: 20
        Second:
          full_name: Nora
          age: 30
        nora:
          aliases: [Cartographer]
          occupation: Conflicting match
        harbour:
          name: Old harbour
          occupation: Harbourmaster
      """);
    // When
    var atlas = json(get("/v1/sessions/" + sessionId + "/atlas", false));
    // Then
    assertEquals(9, atlas.path("entries").size());
    assertTrue(entry(atlas, "nora").path("profile").isEmpty());
    assertTrue(entry(atlas, "linde").path("profile").isEmpty());
    assertTrue(entry(atlas, "harbour").path("profile").isEmpty());
    for (String id : new String[]{"profile:First", "profile:Second", "profile:nora"}) {
      assertTrue(entry(atlas, id).path("profileNotice").asText().contains("No unique graph match"));
    }
    assertEquals("CHARACTER", entry(atlas, "profile:harbour").path("type").asText());
    assertEquals(0, modelRequests.get());
  }

  private void saveProfiles(String yaml) throws Exception {
    var response = client.send(HttpRequest.newBuilder(uri("/v1/sessions/" + sessionId + "/config"))
      .header("Content-Type", "application/json")
      .method("PATCH", HttpRequest.BodyPublishers.ofString(JsonSupport.OBJECT_MAPPER.writeValueAsString(
        java.util.Map.of("fixedProtagonists", yaml)))).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
  }

  private void start(boolean populated) throws Exception {
    Path override = temporaryDirectory.resolve("core.config");
    Files.writeString(override, "validation.enabled=false\ngraph.enabled=false\n");
    config = new ApiConfig("127.0.0.1", 0, temporaryDirectory.resolve("database/api"), "sa", "", Duration.ofMinutes(60));
    ChatClient model = (_, _, _) -> { modelRequests.incrementAndGet(); return "Story"; };
    server = ApiServer.create(config, AppConfigLoader.load(temporaryDirectory, override), model, model);
    server.start();
    var created = client.send(HttpRequest.newBuilder(uri("/v1/sessions"))
      .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
      HttpResponse.BodyHandlers.ofString());
    assertEquals(201, created.statusCode());
    sessionId = json(created).path("sessionId").asText();
    cookie = created.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    // Graph-only fixtures must not inherit the application's bundled character profiles.
    saveProfiles("fixed_protagonists: {}");
    if (populated) {
      var updated = client.send(HttpRequest.newBuilder(uri("/v1/sessions/" + sessionId + "/graph"))
        .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(GRAPH)).build(),
        HttpResponse.BodyHandlers.ofString());
      assertEquals(200, updated.statusCode(), updated.body());
    }
  }

  private HttpResponse<String> get(String path, boolean withCookie) throws Exception {
    var request = HttpRequest.newBuilder(uri(path));
    if (withCookie) request.header("Cookie", cookie);
    return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) { return URI.create("http://127.0.0.1:" + server.port() + path); }

  private JsonNode json(HttpResponse<String> response) throws Exception {
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
    return JsonSupport.OBJECT_MAPPER.readTree(response.body());
  }

  private JsonNode relation(JsonNode entry, String id) {
    for (var relation : entry.path("relations")) if (relation.path("id").asText().equals(id)) return relation;
    throw new AssertionError("Missing relation: " + id);
  }

  private JsonNode field(JsonNode fields, String label) {
    for (var field : fields) if (field.path("label").asText().equals(label)) return field;
    throw new AssertionError("Missing profile field: " + label);
  }

  private JsonNode entry(JsonNode atlas, String id) {
    for (var entry : atlas.path("entries")) if (entry.path("id").asText().equals(id)) return entry;
    throw new AssertionError("Missing entry: " + id);
  }
}
