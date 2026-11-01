package nl.llm.storyteller.core.service.openai;

import nl.llm.storyteller.core.model.Message;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface OpenAiRoute {
  default OpenAiRouteResult stream(List<Message> messages, Map<String, Object> options, int timeoutSeconds,
    java.util.function.Consumer<String> onDelta) throws IOException, InterruptedException {
    OpenAiRouteResult result = execute(messages, options, timeoutSeconds);
    onDelta.accept(result.content());
    return result;
  }

  OpenAiRouteResult execute(List<Message> messages, Map<String, Object> options, int timeoutSeconds)
    throws IOException, InterruptedException;
}
