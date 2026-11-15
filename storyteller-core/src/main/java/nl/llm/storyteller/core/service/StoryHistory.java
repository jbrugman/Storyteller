package nl.llm.storyteller.core.service;

import nl.llm.storyteller.core.model.HistoryState;
import nl.llm.storyteller.core.model.Message;

import java.util.List;

public interface StoryHistory {
  HistoryState load();

  void save(HistoryState state);

  default void appendTurn(String userInput, String assistantResponse) {
    appendTurn(userInput, assistantResponse, 0);
  }

  /**
   * Appends a completed turn and records the durable request frontier ({@code contextTargetIndex}) as
   * {@code max(existing, contextTarget)}, so derived memory can later condense toward the boundary the
   * request actually left behind. A non-positive {@code contextTarget} leaves the frontier untouched, so
   * turns-mode callers and older data are unaffected.
   */
  void appendTurn(String userInput, String assistantResponse, int contextTarget);

  String removeLastTurn();

  LastTurn loadLastTurn();

  List<Message> recentMessages(int limitTurns);

  List<Message> recentMessagesWindow(int totalTurns, int trailingTurnsToExclude);

  void markSummarized(int messagesCount);

  void markRecentSummarized(int messagesCount);

  void markCanonicalStateUpdated(int messagesCount);

  record LastTurn(String userInput, String assistantResponse) {
    public boolean isPresent() {
      return (userInput != null && !userInput.isBlank())
        || (assistantResponse != null && !assistantResponse.isBlank());
    }
  }
}
