package nl.llm.storyteller.api.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import nl.llm.storyteller.api.http.dto.ErrorResponse;
import nl.llm.storyteller.api.http.dto.SessionConfigPatchRequest;
import nl.llm.storyteller.api.http.dto.SessionConfigResponse;
import nl.llm.storyteller.api.http.dto.SessionHistoryResponse;
import nl.llm.storyteller.api.http.dto.SessionStateResponse;
import nl.llm.storyteller.api.http.dto.SessionGraphResponse;
import nl.llm.storyteller.api.session.SessionService;
import nl.llm.storyteller.api.session.SessionSettingsService;
import nl.llm.storyteller.api.story.SessionMemoryService;
import nl.llm.storyteller.api.story.SessionAtlasService;
import nl.llm.storyteller.api.story.StoryService;
import nl.llm.storyteller.api.story.StoryTurnService;
import nl.llm.storyteller.api.story.SessionKnowledgeGraphService;
import nl.llm.storyteller.core.JsonSupport;
import nl.llm.storyteller.core.graph.persistence.KnowledgeGraphJsonCodec;
import nl.llm.storyteller.db.SessionSettings;
import nl.llm.storyteller.db.StoryMessageRecord;

import java.io.IOException;
import java.util.List;

public final class SessionDataController {
  private final SessionService sessionService;
  private final StoryService storyService;
  private final StoryTurnService storyTurnService;
  private final SessionSettingsService settingsService;
  private final SessionMemoryService memoryService;
  private final SessionAtlasService atlasService;
  private final SessionKnowledgeGraphService graphService;
  private final Double defaultTemperature;
  private final KnowledgeGraphJsonCodec graphCodec = new KnowledgeGraphJsonCodec();

  public SessionDataController(
    SessionService sessionService,
    StoryService storyService,
    StoryTurnService storyTurnService,
    SessionSettingsService settingsService,
    SessionMemoryService memoryService,
    SessionKnowledgeGraphService graphService,
    SessionAtlasService atlasService,
    Double defaultTemperature
  ) {
    this.sessionService = sessionService;
    this.storyService = storyService;
    this.storyTurnService = storyTurnService;
    this.settingsService = settingsService;
    this.memoryService = memoryService;
    this.atlasService = atlasService;
    this.graphService = graphService;
    this.defaultTemperature = defaultTemperature;
  }

  public void register(JavalinConfig config) {
    config.routes.get("/v1/sessions/{sessionId}/history", this::history);
    config.routes.get("/v1/sessions/{sessionId}/config", this::configuration);
    config.routes.patch("/v1/sessions/{sessionId}/config", this::patchConfiguration);
    config.routes.post("/v1/sessions/{sessionId}/undo", this::undo);
    config.routes.get("/v1/sessions/{sessionId}/state", this::state);
    config.routes.get("/v1/sessions/{sessionId}/atlas", this::atlas);
    config.routes.put("/v1/sessions/{sessionId}/graph", this::replaceGraph);
    config.routes.post("/v1/sessions/{sessionId}/graph/reset-turn-based", this::resetGraph);
    config.routes.post("/v1/sessions/{sessionId}/graph/fill-fixed-protagonists", this::fillGraph);
    config.routes.post("/v1/sessions/{sessionId}/graph/generate-empty", this::emptyGraph);
    config.routes.post("/v1/sessions/{sessionId}/memory/reset/mid-term", this::resetMidTerm);
    config.routes.post("/v1/sessions/{sessionId}/memory/reset/long-term", this::resetLongTerm);
    config.routes.post("/v1/sessions/{sessionId}/memory/reset/canonical-state", this::resetCanonicalState);
  }

  private void history(Context context) {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) {
      return;
    }
    Integer before = parseOptionalIndex(context.queryParam("before"), "before");
    Integer limit = parseOptionalIndex(context.queryParam("limit"), "limit");
    List<StoryMessageRecord> messages = storyService.loadMessagesBefore(
      sessionId, before == null ? Integer.MAX_VALUE : before, limit == null ? Integer.MAX_VALUE : limit);
    // Older pages exist when a limited window stops above the first message; a full read already holds everything.
    boolean hasOlder = limit != null && !messages.isEmpty() && messages.get(0).messageIndex() > 0;
    context.json(new SessionHistoryResponse(
      sessionId,
      messages,
      storyTurnService.referenceBeforeMessageIndex(sessionId),
      hasOlder
    ));
  }

  // Optional positive page parameter for windowed history; absent or blank keeps the default, an invalid value is rejected.
  private Integer parseOptionalIndex(String value, String name) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed < 1) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("Query parameter '" + name + "' must be a positive integer.", ex);
    }
  }

  private void configuration(Context context) {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) {
      return;
    }
    context.json(configurationResponse(sessionId, settingsService.load(sessionId)));
  }

  private void patchConfiguration(Context context) {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) {
      return;
    }
    SessionConfigPatchRequest patch = parsePatch(context.body());
    settingsService.patchConfiguration(
      sessionId,
      patchedText(patch.systemPrompt(), "systemPrompt"),
      patchedText(patch.fixedProtagonists(), "fixedProtagonists"),
      patchedText(patch.rules(), "rules"),
      patch.temperature() != null,
      patchedTemperature(patch.temperature()),
      patch.streamingEnabled() != null, patchedBoolean(patch.streamingEnabled(), "streamingEnabled"),
      patch.validationEnabled() != null, patchedBoolean(patch.validationEnabled(), "validationEnabled"),
      patch.graphEnabled() != null, patchedBoolean(patch.graphEnabled(), "graphEnabled")
    );
    context.json(configurationResponse(sessionId, settingsService.load(sessionId)));
  }

  private void undo(Context context) {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) {
      return;
    }
    storyTurnService.undoLastTurn(sessionId);
    context.status(HttpStatus.NO_CONTENT);
  }

  private void atlas(Context context) {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) return;
    context.json(atlasService.load(sessionId));
  }

  private void state(Context context) {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) {
      return;
    }
    var memory = memoryService.load(sessionId);
    context.json(new SessionStateResponse(
      sessionId, memory.summary(), memory.recentSummary(), memory.canonicalState(),
      memory.summaryCursor(), memory.recentSummaryCursor(), memory.canonicalStateCursor(),
      graphCodec.toJson(settingsService.load(sessionId).knowledgeGraph())
    ));
  }

  private boolean rejectInactiveSession(Context context, String sessionId) {
    if (sessionService.findActive(sessionId).isPresent()) {
      return false;
    }
    context.status(HttpStatus.NOT_FOUND)
      .json(new ErrorResponse("session_not_found", "Session was not found."));
    return true;
  }

  private void replaceGraph(Context context) throws InterruptedException {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) {
      return;
    }
    graphService.replace(sessionId, context.body());
    graphResponse(context, sessionId);
  }

  private void resetGraph(Context context) throws InterruptedException {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) {
      return;
    }
    graphService.resetTurnBasedItems(sessionId);
    graphResponse(context, sessionId);
  }

  private void fillGraph(Context context) throws IOException, InterruptedException {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) {
      return;
    }
    graphService.fillFromFixedProtagonists(sessionId);
    graphResponse(context, sessionId);
  }

  private void emptyGraph(Context context) throws InterruptedException {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) {
      return;
    }
    graphService.generateEmpty(sessionId);
    graphResponse(context, sessionId);
  }

  private void graphResponse(Context context, String sessionId) {
    context.json(new SessionGraphResponse(sessionId, graphCodec.toJson(settingsService.load(sessionId).knowledgeGraph())));
  }

  private void resetMidTerm(Context context) {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) {
      return;
    }
    memoryService.resetMidTermMemory(sessionId);
    context.status(HttpStatus.NO_CONTENT);
  }

  private void resetLongTerm(Context context) {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) {
      return;
    }
    memoryService.resetLongTermMemory(sessionId);
    context.status(HttpStatus.NO_CONTENT);
  }

  private void resetCanonicalState(Context context) {
    String sessionId = context.pathParam("sessionId");
    if (rejectInactiveSession(context, sessionId)) {
      return;
    }
    memoryService.resetCanonicalState(sessionId);
    context.status(HttpStatus.NO_CONTENT);
  }

  private SessionConfigResponse configurationResponse(String sessionId, SessionSettings settings) {
    return new SessionConfigResponse(
      sessionId, settings.prompts().systemPrompt(), settings.prompts().fixedProtagonists(),
      settings.prompts().rules(), settings.temperature(),
      settings.temperature() != null ? settings.temperature() : defaultTemperature,
      settings.streamingEnabled(), settings.validationEnabled(), settings.graphEnabled(),
      settingsService.streamingEnabled(settings), settingsService.validationEnabled(settings),
      settingsService.graphEnabled(settings)
    );
  }

  private Boolean patchedBoolean(com.fasterxml.jackson.databind.JsonNode value, String name) {
    if (value == null || value.isNull()) return null;
    if (!value.isBoolean()) throw new IllegalArgumentException(name + " must be a boolean or null.");
    return value.booleanValue();
  }

  private SessionConfigPatchRequest parsePatch(String body) {
    SessionConfigPatchRequest patch;
    try {
      patch = JsonSupport.OBJECT_MAPPER.readerFor(SessionConfigPatchRequest.class)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Configuration patch must be a JSON object.", ex);
    }
    if (patch == null) {
      throw new IllegalArgumentException("Configuration patch must be a JSON object.");
    }
    return patch;
  }

  private String patchedText(JsonNode value, String field) {
    if (value == null) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be a string.");
    }
    return value.textValue();
  }

  private Double patchedTemperature(JsonNode value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isNumber() || !Double.isFinite(value.doubleValue())
      || value.doubleValue() < 0 || value.doubleValue() > 2) {
      throw new IllegalArgumentException("temperature must be a number between 0 and 2, or null.");
    }
    return value.doubleValue();
  }
}
