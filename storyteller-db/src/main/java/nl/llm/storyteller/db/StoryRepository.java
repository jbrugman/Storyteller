package nl.llm.storyteller.db;

import nl.llm.storyteller.core.model.Message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StoryRepository {
  List<Message> loadRecentMessages(String sessionId, int maximumMessages);

  List<StoryMessageRecord> loadMessagesBefore(String sessionId, int beforeMessageIndex, int maximumMessages);

  Optional<StoryImage> loadImage(String sessionId, int messageIndex);

  int lastMessageIndex(String sessionId);

  List<PastStoryExchange> loadPastExchanges(String sessionId, List<Integer> messageIndexes);

  default StoryTurnRecord appendTurn(
    String sessionId,
    String userInput,
    String assistantResponse,
    StoryImage image,
    Instant updatedAt
  ) {
    return appendTurn(sessionId, userInput, assistantResponse, image, updatedAt, 0);
  }

  /**
   * Appends a completed turn and, in the same transaction, raises the durable request frontier
   * ({@code context_target_index}) to {@code contextTarget} via {@code GREATEST}. A non-positive
   * {@code contextTarget} leaves the frontier untouched, so callers that do not plan a rolling
   * context can ignore it. The frontier is only written here, after a successful generation.
   */
  StoryTurnRecord appendTurn(
    String sessionId,
    String userInput,
    String assistantResponse,
    StoryImage image,
    Instant updatedAt,
    int contextTarget
  );

  boolean updateAssistantMessage(String sessionId, int messageIndex, String content, Instant updatedAt);

  boolean undoLastTurn(String sessionId, Instant updatedAt);
}
