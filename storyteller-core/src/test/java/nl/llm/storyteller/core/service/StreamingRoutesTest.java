package nl.llm.storyteller.core.service;

import com.sun.net.httpserver.HttpServer;
import nl.llm.storyteller.core.JsonSupport;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.service.openai.*;
import nl.llm.storyteller.core.service.openai.chatcompletions.ChatCompletionsRoute;
import nl.llm.storyteller.core.service.openai.responses.ResponsesRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StreamingRoutesTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("""
    Given a model backend streaming Chat Completions or Responses,
    When the first text arrives before the backend finishes,
    Then the caller receives it immediately and final usage is preserved
    """)
  void shouldEmitBeforeCompletion(boolean responses) throws Exception {
    CountDownLatch firstReceived = new CountDownLatch(1);
    AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1", exchange -> {
      try {
        var payload = JsonSupport.OBJECT_MAPPER.readTree(exchange.getRequestBody());
        assertTrue(payload.path("stream").asBoolean());
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (var output = exchange.getResponseBody()) {
          String first = responses ? "{\"type\":\"response.output_text.delta\",\"delta\":\"Hé\"}"
            : "{\"choices\":[{\"delta\":{\"content\":\"Hé\"}}]}";
          output.write(("data: " + first + "\n\n").getBytes(StandardCharsets.UTF_8));
          output.flush();
          assertTrue(firstReceived.await(3, TimeUnit.SECONDS), "Caller must receive the first delta before completion");
          String rest = responses
            ? "data: {\"type\":\"response.output_text.delta\",\"delta\":\"llo\"}\n\n"
              + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"output_tokens\":7}}}\n\n"
            : "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"},\"finish_reason\":\"stop\"}],\"usage\":{\"completion_tokens\":7}}\n\ndata: [DONE]\n\n";
          output.write(rest.getBytes(StandardCharsets.UTF_8));
        }
      } catch (Throwable ex) { serverFailure.set(ex); exchange.close(); }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
      OpenAiRoute route = responses ? new ResponsesRoute(url, "test", "", false)
        : new ChatCompletionsRoute(url, "test", "", false, false);
      List<String> chunks = new ArrayList<>();
      OpenAiRouteResult result = route.stream(List.of(new Message("user", "Hello")), Map.of(), 5, text -> {
        chunks.add(text); firstReceived.countDown();
      });
      assertEquals(List.of("Hé", "llo"), chunks);
      assertEquals("Héllo", result.content());
      assertEquals(7, result.outputTokens());
      assertNull(serverFailure.get());
    } finally { server.stop(0); }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("""
    Given a model connection that closes after a partial response,
    When no terminal event is received,
    Then streaming fails instead of accepting the partial response
    """)
  void shouldRejectTruncatedStream(boolean responses) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, 0);
      try (var output = exchange.getResponseBody()) {
        String data = responses ? "{\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}"
          : "{\"choices\":[{\"delta\":{\"content\":\"Partial\"}}]}";
        output.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
      }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
      OpenAiRoute route = responses ? new ResponsesRoute(url, "test", "", false)
        : new ChatCompletionsRoute(url, "test", "", false, false);
      assertThrows(IOException.class, () -> route.stream(List.of(), Map.of(), 5, _ -> {}));
    } finally { server.stop(0); }
  }

  @Test
  @DisplayName("""
    Given a backend that stalls after sending partial text,
    When the configured streaming deadline expires,
    Then the read fails promptly instead of waiting indefinitely
    """)
  void shouldEnforceBodyDeadline() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, 0);
      try (var output = exchange.getResponseBody()) {
        output.write("data: {\"choices\":[{\"delta\":{\"content\":\"Partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
        try { release.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
      }
    });
    server.start();
    try {
      var route = new ChatCompletionsRoute("http://127.0.0.1:" + server.getAddress().getPort()
        + "/v1/chat/completions", "test", "", false, false);
      assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
        assertThrows(IOException.class, () -> route.stream(List.of(), Map.of(), 1, _ -> {})));
    } finally { release.countDown(); server.stop(0); }
  }

  @Test
  @DisplayName("""
    Given reasoning markers split across model chunks,
    When streaming through the facade and resilience guard,
    Then hidden reasoning is never emitted and the visible text is returned
    """)
  void shouldKeepReasoningHiddenAcrossChunks() throws Exception {
    OpenAiRoute route = new OpenAiRoute() {
      @Override public OpenAiRouteResult execute(List<Message> messages, Map<String, Object> options, int timeout) {
        fail("Must use streaming"); return null;
      }
      @Override public OpenAiRouteResult stream(List<Message> messages, Map<String, Object> options, int timeout,
        java.util.function.Consumer<String> onDelta) {
        for (String part : List.of("<thi", "nk>", "secret", "</thi", "nk>", "Visible")) onDelta.accept(part);
        return new OpenAiRouteResult("<think>secret</think>Visible", 7);
      }
    };
    var facade = new OpenAiCompatibleClientFacade(true, ChatRequestMetrics.NONE, "chat", route, route);
    var client = new ResilientChatClient(facade, new LlmBackendGuard("test", 3, 5));
    List<String> emitted = new ArrayList<>();
    assertEquals("Visible", client.stream(List.of(), Map.of(), 5, emitted::add));
    assertTrue(emitted.stream().noneMatch(text -> text.contains("secret") || text.contains("<")));
    assertEquals("Visible", emitted.getLast());
  }

  @Test
  @DisplayName("""
    Given an unavailable Responses endpoint,
    When streaming is requested twice,
    Then fallback to Chat Completions is cached and both requests stream
    """)
  void shouldCacheStreamingFallback() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    OpenAiRoute unavailable = (_, _, _) -> { calls.incrementAndGet(); throw new UnsupportedResponsesEndpointException("404"); };
    OpenAiRoute fallback = (_, _, _) -> new OpenAiRouteResult("Visible", 1);
    var facade = new OpenAiCompatibleClientFacade(false, ChatRequestMetrics.NONE, "chat", unavailable, fallback);
    List<String> emitted = new ArrayList<>();
    facade.stream(List.of(), Map.of(), 5, emitted::add);
    facade.stream(List.of(), Map.of(), 5, emitted::add);
    assertEquals(1, calls.get());
    assertEquals(List.of("Visible", "Visible"), emitted);
  }
}
