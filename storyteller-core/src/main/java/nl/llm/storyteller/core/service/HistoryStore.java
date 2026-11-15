package nl.llm.storyteller.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import nl.llm.storyteller.core.AtomicFileWriter;
import nl.llm.storyteller.core.JsonSupport;
import nl.llm.storyteller.core.model.HistoryState;
import nl.llm.storyteller.core.model.Message;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HistoryStore implements StoryHistory {
  private final Path path;
  private final Path legacyPath;

  public HistoryStore(Path path, Path legacyPath) {
    this.path = path;
    this.legacyPath = legacyPath;
  }

  public synchronized HistoryState load() {
    migrateLegacyHistoryIfNeeded();

    if (!Files.exists(path)) {
      return HistoryState.empty();
    }

    try {
      JsonNode data = JsonSupport.OBJECT_MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
      JsonNode messagesNode = data.get("messages");
      if (messagesNode == null || !messagesNode.isArray()) {
        throw new IllegalArgumentException("history.json does not contain a valid 'messages' array.");
      }

      List<Message> messages = new ArrayList<>();
      for (JsonNode node : messagesNode) {
        messages.add(new Message(node.path("role").asText(), node.path("content").asText()));
      }

      int summaryCursor = data.path("summary_cursor").asInt(0);
      int recentSummaryCursor = data.path("recent_summary_cursor").asInt(0);
      int canonicalStateCursor = data.path("canonical_state_cursor").asInt(0);
      int contextTargetIndex = data.path("context_target_index").asInt(0);
      return new HistoryState(messages, summaryCursor, recentSummaryCursor, canonicalStateCursor, contextTargetIndex);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Invalid JSON in " + path + ": " + ex.getOriginalMessage(), ex);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  public synchronized void save(HistoryState state) {
    Map<String, Object> data = new LinkedHashMap<>();
    List<Map<String, String>> messages = state.messages().stream()
      .map(message -> Map.of("role", message.role(), "content", message.content()))
      .toList();
    data.put("messages", messages);
    data.put("summary_cursor", state.summaryCursor());
    data.put("recent_summary_cursor", state.recentSummaryCursor());
    data.put("canonical_state_cursor", state.canonicalStateCursor());
    data.put("context_target_index", state.contextTargetIndex());

    try {
      AtomicFileWriter.write(path, JsonSupport.OBJECT_MAPPER.writeValueAsBytes(data));
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  public synchronized void appendTurn(String userInput, String assistantResponse) {
    appendTurn(userInput, assistantResponse, 0);
  }

  public synchronized void appendTurn(String userInput, String assistantResponse, int contextTarget) {
    HistoryState state = load();
    List<Message> messages = new ArrayList<>(state.messages());
    messages.add(new Message("user", userInput));
    messages.add(new Message("assistant", assistantResponse));
    // The frontier only grows on a turn and only shrinks on an undo; a non-positive target keeps it.
    int frontier = Math.max(state.contextTargetIndex(), contextTarget);
    save(new HistoryState(messages, state.summaryCursor(), state.recentSummaryCursor(),
      state.canonicalStateCursor(), frontier));
  }

  public synchronized String removeLastTurn() {
    HistoryState state = load();
    List<Message> messages = new ArrayList<>(state.messages());
    int lastUserIndex = -1;
    for (int index = messages.size() - 1; index >= 0; index--) {
      if ("user".equals(messages.get(index).role())) {
        lastUserIndex = index;
        break;
      }
    }

    if (lastUserIndex < 0) {
      return "";
    }

    String restoredUserInput = messages.get(lastUserIndex).content();
    messages.subList(lastUserIndex, messages.size()).clear();
    int newSize = messages.size();
    save(new HistoryState(
      messages,
      Math.min(state.summaryCursor(), newSize),
      Math.min(state.recentSummaryCursor(), newSize),
      Math.min(state.canonicalStateCursor(), newSize),
      Math.min(state.contextTargetIndex(), newSize)
    ));
    return restoredUserInput;
  }

  public synchronized LastTurn loadLastTurn() {
    List<Message> messages = load().messages();
    int lastUserIndex = -1;
    for (int index = messages.size() - 1; index >= 0; index--) {
      if ("user".equals(messages.get(index).role())) {
        lastUserIndex = index;
        break;
      }
    }

    if (lastUserIndex < 0) {
      return new LastTurn("", "");
    }

    String userInput = messages.get(lastUserIndex).content();
    String assistantResponse = "";
    for (int index = lastUserIndex + 1; index < messages.size(); index++) {
      if ("assistant".equals(messages.get(index).role())) {
        assistantResponse = messages.get(index).content();
        break;
      }
    }
    return new LastTurn(userInput, assistantResponse);
  }

  public synchronized List<Message> recentMessages(int limitTurns) {
    return sliceRecentCompleteTurns(load().messages(), limitTurns);
  }

  public synchronized List<Message> recentMessagesWindow(int totalTurns, int trailingTurnsToExclude) {
    List<Message> messages = load().messages();
    List<Message> upToTotalTurns = sliceRecentCompleteTurns(messages, totalTurns);
    if (trailingTurnsToExclude <= 0 || upToTotalTurns.isEmpty()) {
      return upToTotalTurns;
    }

    List<Message> trailingMessages = sliceRecentCompleteTurns(messages, trailingTurnsToExclude);
    int trailingCount = trailingMessages.size();
    if (trailingCount == 0) {
      return upToTotalTurns;
    }

    int endIndexExclusive = Math.max(0, upToTotalTurns.size() - trailingCount);
    return new ArrayList<>(upToTotalTurns.subList(0, endIndexExclusive));
  }

  public synchronized void markSummarized(int messagesCount) {
    HistoryState state = load();
    int safeCount = Math.min(messagesCount, state.messages().size());
    save(new HistoryState(new ArrayList<>(state.messages()), safeCount, state.recentSummaryCursor(),
      state.canonicalStateCursor(), state.contextTargetIndex()));
  }

  public synchronized void markRecentSummarized(int messagesCount) {
    HistoryState state = load();
    int safeCount = Math.min(messagesCount, state.messages().size());
    save(new HistoryState(new ArrayList<>(state.messages()), state.summaryCursor(), safeCount,
      state.canonicalStateCursor(), state.contextTargetIndex()));
  }

  public synchronized void markCanonicalStateUpdated(int messagesCount) {
    HistoryState state = load();
    int safeCount = Math.min(messagesCount, state.messages().size());
    save(new HistoryState(new ArrayList<>(state.messages()), state.summaryCursor(), state.recentSummaryCursor(),
      safeCount, state.contextTargetIndex()));
  }

  private List<Message> sliceRecentCompleteTurns(List<Message> messages, int limitTurns) {
    if (limitTurns <= 0 || messages.isEmpty()) {
      return List.of();
    }

    int userSeen = 0;
    int startIndex = messages.size();
    for (int index = messages.size() - 1; index >= 0; index--) {
      if ("user".equals(messages.get(index).role())) {
        userSeen++;
        startIndex = index;
        if (userSeen >= limitTurns) {
          break;
        }
      }
    }

    return new ArrayList<>(messages.subList(startIndex, messages.size()));
  }

  private void migrateLegacyHistoryIfNeeded() {
    if (Files.exists(path) || !Files.exists(legacyPath)) {
      return;
    }

    List<Message> messages = new ArrayList<>();
    String currentRole = null;
    List<String> buffer = new ArrayList<>();

    try {
      for (String line : Files.readAllLines(legacyPath, StandardCharsets.UTF_8)) {
        if (line.startsWith("USER: ")) {
          flushLegacyMessage(messages, currentRole, buffer);
          currentRole = "user";
          buffer = new ArrayList<>(List.of(line.substring("USER: ".length())));
        } else if (line.startsWith("ASSISTANT: ")) {
          flushLegacyMessage(messages, currentRole, buffer);
          currentRole = "assistant";
          buffer = new ArrayList<>(List.of(line.substring("ASSISTANT: ".length())));
        } else if (currentRole != null) {
          buffer.add(line);
        }
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }

    flushLegacyMessage(messages, currentRole, buffer);
    save(new HistoryState(messages, 0, 0, 0));
  }

  private void flushLegacyMessage(List<Message> messages, String role, List<String> buffer) {
    if (role == null) {
      return;
    }

    String content = String.join("\n", buffer).trim();
    if (!content.isEmpty()) {
      messages.add(new Message(role, content));
    }
  }

}
