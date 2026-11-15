package nl.llm.storyteller.core.model;

import java.util.List;

public record StoryChatPromptInput(
  String userInput,
  String canonicalState,
  String summary,
  String recentSummary,
  String knowledgeGraphFacts,
  String relevantPastStory,
  List<Message> recentMessages,
  String extraSystemInstruction
) {
  /**
   * The same context with different verbatim history. A caller measures the frame it always sends with
   * an empty history and then refills it with the history that fits, without rebuilding every section.
   */
  public StoryChatPromptInput withRecentMessages(List<Message> messages) {
    return new StoryChatPromptInput(userInput, canonicalState, summary, recentSummary, knowledgeGraphFacts,
      relevantPastStory, messages, extraSystemInstruction);
  }
}
