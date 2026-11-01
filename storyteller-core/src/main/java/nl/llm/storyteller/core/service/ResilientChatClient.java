package nl.llm.storyteller.core.service;

import nl.llm.storyteller.core.model.Message;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ResilientChatClient implements ChatClient {
  private final ChatClient delegate;
  private final LlmBackendGuard guard;

  public ResilientChatClient(ChatClient delegate, LlmBackendGuard guard) {
    this.delegate = Objects.requireNonNull(delegate);
    this.guard = Objects.requireNonNull(guard);
  }

  @Override
  public String chat(List<Message> messages, Map<String, Object> options, int timeoutSeconds)
    throws IOException, InterruptedException {
    return guard.execute(() -> delegate.chat(messages, options, timeoutSeconds));
  }

  @Override
  public String stream(List<Message> messages, Map<String, Object> options, int timeoutSeconds,
    java.util.function.Consumer<String> onText) throws IOException, InterruptedException {
    return guard.execute(() -> delegate.stream(messages, options, timeoutSeconds, onText));
  }

  @Override
  public String chat(String purpose, List<Message> messages, Map<String, Object> options, int timeoutSeconds)
    throws IOException, InterruptedException {
    return guard.execute(() -> delegate.chat(purpose, messages, options, timeoutSeconds));
  }
}
