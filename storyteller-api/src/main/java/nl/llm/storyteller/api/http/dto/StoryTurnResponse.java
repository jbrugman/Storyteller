package nl.llm.storyteller.api.http.dto;

public record StoryTurnResponse(
  String sessionId,
  int userMessageIndex,
  int assistantMessageIndex,
  String response,
  String contextWarning
) {
}
