package nl.llm.storyteller.api.http;

import io.javalin.http.Context;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import nl.llm.storyteller.api.story.StoryTurnResult;
import nl.llm.storyteller.core.JsonSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

public final class StoryStream {
  private static final ObjectWriter EVENTS = JsonSupport.OBJECT_MAPPER.writer()
    .without(SerializationFeature.INDENT_OUTPUT);
  private StoryStream() { }

  @FunctionalInterface
  public interface Generate {
    StoryTurnResult run(Consumer<String> onText) throws IOException, InterruptedException;
  }

  public static void write(Context context, Generate generate) throws IOException {
    context.contentType("text/event-stream");
    context.header("Cache-Control", "no-cache, no-store");
    context.header("X-Accel-Buffering", "no");
    try {
      send(context, "status", Map.of("message", "Generating…"));
      StoryTurnResult result = generate.run(text -> {
        try { send(context, "text", Map.of("text", text)); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
      });
      send(context, "done", Map.of(
        "userMessageIndex", result.userMessageIndex(),
        "assistantMessageIndex", result.assistantMessageIndex(),
        "response", result.response()
      ));
    } catch (InterruptedException ex) {
      send(context, "error", Map.of("message", "Generation was interrupted. The response is incomplete."));
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException ex) {
      send(context, "error", Map.of("message", "Generation failed. The response is incomplete; reload the story before retrying."));
    }
  }

  private static void send(Context context, String event, Object data) throws IOException {
    var output = context.res().getOutputStream();
    String frame = "event: " + event + "\ndata: " + EVENTS.writeValueAsString(data) + "\n\n";
    output.write(frame.getBytes(StandardCharsets.UTF_8));
    output.flush();
  }
}
