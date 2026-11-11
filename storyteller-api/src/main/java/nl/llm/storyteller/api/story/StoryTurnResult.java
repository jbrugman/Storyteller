package nl.llm.storyteller.api.story;

/**
 * A finished turn. {@code contextWarning} is empty unless the configured context window no longer holds
 * what this session needs; it informs the user and never fails the turn that already succeeded.
 */
public record StoryTurnResult(int userMessageIndex, int assistantMessageIndex, String response, String contextWarning) {
}
