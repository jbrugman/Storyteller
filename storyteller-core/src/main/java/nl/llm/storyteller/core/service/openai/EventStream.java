package nl.llm.storyteller.core.service.openai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Reads SSE frames with a deadline covering the response body as well as the headers. */
public final class EventStream {
  private EventStream() { }

  public static void read(InputStream input, int timeoutSeconds, Predicate<String> onData) throws IOException {
    try (var timer = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "model-stream-timeout");
      thread.setDaemon(true);
      return thread;
    })) {
      var deadline = timer.schedule(() -> {
        try { input.close(); } catch (IOException ignored) { }
      }, timeoutSeconds, TimeUnit.SECONDS);
      try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isEmpty()) {
            if (!data.isEmpty()) {
              if (!onData.test(data.toString())) return;
              data.setLength(0);
            }
          } else if (line.startsWith("data:")) {
            if (!data.isEmpty()) data.append('\n');
            String value = line.substring(5);
            data.append(value.startsWith(" ") ? value.substring(1) : value);
          }
        }
        if (!data.isEmpty()) onData.test(data.toString());
      } finally {
        deadline.cancel(false);
        timer.shutdownNow();
      }
    }
  }
}
