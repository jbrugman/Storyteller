package nl.llm.storyteller.api.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import nl.llm.storyteller.core.JsonSupport;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.config.SizeUnit;
import nl.llm.storyteller.api.input.StoryImageUpload;
import nl.llm.storyteller.api.story.StoryService;
import nl.llm.storyteller.db.StoryImage;
import nl.llm.storyteller.api.http.dto.EditAssistantMessageRequest;
import nl.llm.storyteller.api.http.dto.EditAssistantMessageResponse;
import nl.llm.storyteller.api.http.dto.ErrorResponse;
import nl.llm.storyteller.api.http.dto.StoryTurnRequest;
import nl.llm.storyteller.api.http.dto.StoryTurnResponse;
import nl.llm.storyteller.api.session.SessionService;
import nl.llm.storyteller.api.story.StoryTurnResult;
import nl.llm.storyteller.api.story.StoryTurnService;

import java.io.IOException;
import java.util.Optional;
import java.util.List;

public final class StoryController {
  private final SessionService sessionService;
  private final StoryTurnService storyTurnService;
  private final StoryService storyService;

  public StoryController(SessionService sessionService, StoryTurnService storyTurnService, StoryService storyService) {
    this.sessionService = sessionService;
    this.storyTurnService = storyTurnService;
    this.storyService = storyService;
  }

  public void register(JavalinConfig config) {
    config.routes.post("/v1/sessions/{sessionId}/turns", this::createTurn);
    config.routes.post("/v1/sessions/{sessionId}/turns/stream", this::createTurn);
    config.routes.post("/v1/sessions/{sessionId}/messages/{messageIndex}/edit", this::editAssistantMessage);
    config.routes.get("/v1/sessions/{sessionId}/images/{messageIndex}", this::image);
  }

  private void createTurn(Context context) throws IOException, InterruptedException {
    String sessionId = context.pathParam("sessionId");
    if (sessionService.findActive(sessionId).isEmpty()) {
      context.status(HttpStatus.NOT_FOUND)
        .json(new ErrorResponse("session_not_found", "Session was not found."));
      return;
    }

    StoryImage image = null;
    StoryTurnRequest request;
    if (context.isMultipart()) {
      context.multipartConfig().maxFileSize(StoryImageUpload.MAX_BYTES, SizeUnit.BYTES);
      context.multipartConfig().maxTotalRequestSize(StoryImageUpload.MAX_BYTES + 1024 * 1024, SizeUnit.BYTES);
      image = StoryImageUpload.read(ApiMultipart.file(context, "image"));
      List<String> indexes = context.formParams("pastExchange");
      if (indexes.size() > StoryTurnService.MAX_PAST_EXCHANGES) {
        throw new IllegalArgumentException("Select at most three past exchanges.");
      }
      try {
        request = new StoryTurnRequest(context.formParam("prompt"), indexes.stream().map(Integer::valueOf).toList());
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException("Past exchange indexes must be integers.", ex);
      }
    } else {
      try {
        request = JsonSupport.OBJECT_MAPPER.readerFor(StoryTurnRequest.class)
          .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(context.body());
      } catch (JsonProcessingException ex) {
        throw new IllegalArgumentException("Invalid story turn JSON.", ex);
      }
      if (request == null) {
        throw new IllegalArgumentException("Story turn must be a JSON object.");
      }
    }
    if (context.path().endsWith("/stream")) {
      if (!storyTurnService.streamingEnabled(sessionId)) {
        throw new IllegalArgumentException("Streaming is disabled for this session.");
      }
      StoryImage attached = image;
      StoryStream.write(context, onText -> storyTurnService.execute(
        sessionId, request.prompt(), attached, request.pastMessageIndexes(), onText));
      return;
    }
    StoryTurnResult result = storyTurnService.execute(sessionId, request.prompt(), image, request.pastMessageIndexes());
    context.json(new StoryTurnResponse(
      sessionId,
      result.userMessageIndex(),
      result.assistantMessageIndex(),
      result.response(),
      result.contextWarning()
    ));
  }

  private void editAssistantMessage(Context context) {
    String sessionId = context.pathParam("sessionId");
    if (sessionService.findActive(sessionId).isEmpty()) {
      context.status(HttpStatus.NOT_FOUND)
        .json(new ErrorResponse("session_not_found", "Session was not found."));
      return;
    }
    int messageIndex = parseMessageIndex(context.pathParam("messageIndex"));
    EditAssistantMessageRequest request = context.bodyAsClass(EditAssistantMessageRequest.class);
    Optional<String> updatedContent = storyTurnService.editAssistantResponse(sessionId, messageIndex, request.content());
    if (updatedContent.isEmpty()) {
      context.status(HttpStatus.NOT_FOUND)
        .json(new ErrorResponse("assistant_message_not_found", "Assistant message was not found."));
      return;
    }
    context.json(new EditAssistantMessageResponse(sessionId, messageIndex, updatedContent.get()));
  }

  private int parseMessageIndex(String value) {
    try {
      int messageIndex = Integer.parseInt(value);
      if (messageIndex < 0) {
        throw new NumberFormatException();
      }
      return messageIndex;
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("Message index must be zero or greater.", ex);
    }
  }

  private void image(Context context) {
    String sessionId = context.pathParam("sessionId");
    if (sessionService.findActive(sessionId).isEmpty()) {
      context.status(HttpStatus.NOT_FOUND).json(new ErrorResponse("session_not_found", "Session was not found."));
      return;
    }
    var image = storyService.loadImage(sessionId, parseMessageIndex(context.pathParam("messageIndex")));
    if (image.isEmpty()) {
      context.status(HttpStatus.NOT_FOUND).json(new ErrorResponse("image_not_found", "Image was not found."));
      return;
    }
    context.header("Cache-Control", "private, no-store");
    context.contentType(image.get().mediaType()).result(image.get().content());
  }
}
