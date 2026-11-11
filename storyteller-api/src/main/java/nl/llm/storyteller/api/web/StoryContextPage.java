package nl.llm.storyteller.api.web;

/**
 * Context controls of the settings form. {@code mode} and {@code window} hold what this session stores
 * itself, empty when it inherits; the server values show what an empty field means right now, so
 * inheriting stays visible instead of quietly locking a value into the session.
 */
public record StoryContextPage(
  String mode,
  String window,
  String serverMode,
  int serverWindow
) {
  /** Whether the select should preselect this mode; an empty stored mode inherits instead. */
  public boolean isMode(String candidate) {
    return mode.equalsIgnoreCase(candidate);
  }
}
