package nl.llm.storyteller.core.service;

import nl.llm.storyteller.core.model.Message;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface ChatClient {
  String chat(List<Message> messages, Map<String, Object> options, int timeoutSeconds)
    throws IOException, InterruptedException;

  /** Emits the accumulated visible text. The returned text is authoritative on completion. */
  default String stream(List<Message> messages, Map<String, Object> options, int timeoutSeconds,
    java.util.function.Consumer<String> onText) throws IOException, InterruptedException {
    String result = chat(messages, options, timeoutSeconds);
    onText.accept(result);
    return result;
  }

  default String chat(String purpose, List<Message> messages, Map<String, Object> options, int timeoutSeconds)
    throws IOException, InterruptedException {
    return chat(messages, options, timeoutSeconds);
  }
}
