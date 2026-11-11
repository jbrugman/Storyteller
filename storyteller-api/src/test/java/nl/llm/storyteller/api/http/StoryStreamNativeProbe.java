package nl.llm.storyteller.api.http;

import io.javalin.http.Context;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import nl.llm.storyteller.api.story.StoryTurnResult;
import nl.llm.storyteller.core.JsonSupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standalone entry point so the same completion checks can run as a native executable. */
public final class StoryStreamNativeProbe {
  private StoryStreamNativeProbe() { }

  public static void main(String[] args) throws Exception {
    verifyCompletion();
    verifyFailure();
    System.out.println("PASS: native SSE completion and failure");
  }

  static void verifyCompletion() throws Exception {
    String response = "Voltooid \"antwoord\"\nvolgende regel 🦉";
    String warning = "The context window leaves only 40 tokens for recent turns.";
    String body = capture(onText -> {
      onText.accept(response);
      return new StoryTurnResult(12, 13, response, warning);
    });
    if (body.contains("event: error")) throw new AssertionError(body);
    var completions = Arrays.stream(body.split("\n\n"))
      .filter(frame -> frame.startsWith("event: done\ndata: ")).toList();
    if (completions.size() != 1) throw new AssertionError("Expected exactly one completion event: " + body);
    var data = JsonSupport.OBJECT_MAPPER.readTree(completions.getFirst().substring("event: done\ndata: ".length()));
    if (data.size() != 4 || data.path("userMessageIndex").asInt() != 12
      || data.path("assistantMessageIndex").asInt() != 13 || !response.equals(data.path("response").asText())
      || !warning.equals(data.path("contextWarning").asText())) {
      throw new AssertionError("Completion payload changed: " + data);
    }
  }

  static void verifyFailure() throws Exception {
    String body = capture(onText -> {
      onText.accept("Partial");
      throw new IOException("Connection lost");
    });
    if (!body.contains("event: text") || !body.contains("event: error") || body.contains("event: done")) {
      throw new AssertionError("An interrupted generation must remain a failure: " + body);
    }
  }

  private static String capture(StoryStream.Generate generate) throws IOException {
    var body = new ByteArrayOutputStream();
    var output = new ServletOutputStream() {
      @Override public boolean isReady() { return true; }
      @Override public void setWriteListener(WriteListener listener) { }
      @Override public void write(int value) { body.write(value); }
    };
    var response = (HttpServletResponse) Proxy.newProxyInstance(
      HttpServletResponse.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
      (_, method, _) -> {
        if (method.getName().equals("getOutputStream")) return output;
        throw new AssertionError("Unexpected response operation: " + method.getName());
      });
    var context = (Context) Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[]{Context.class},
      (proxy, method, _) -> switch (method.getName()) {
        case "res" -> response;
        case "contentType", "header" -> proxy;
        default -> throw new AssertionError("Unexpected context operation: " + method.getName());
      });
    StoryStream.write(context, generate);
    return body.toString(StandardCharsets.UTF_8);
  }
}
