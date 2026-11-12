package nl.llm.storyteller.api.web;

/**
 * Context controls of the settings form. The form always offers the two concrete modes, so an empty choice
 * falls back to the mode the server starts a session on instead of presenting a third mode to pick. The
 * recommended size is what the form offers when a session wants rolling without knowing its model.
 */
public record StoryContextPage(
  String mode,
  String window,
  String serverMode,
  int serverWindow,
  int recommendedWindow
) {
  /** The mode the form shows: the session's own choice, or the mode the server starts a session on. */
  public String selectedMode() {
    return mode.isBlank() ? serverMode : mode;
  }

  /** Whether a mode of the form should be preselected. */
  public boolean isMode(String candidate) {
    return candidate.equalsIgnoreCase(selectedMode());
  }

  /** Whether the mode in effect plans against a window, which is the only mode that reads the size. */
  public boolean usesRolling() {
    return isMode("rolling");
  }

  /** The size a save ends up with: the field's own value, or the server's while the field stays empty. */
  public int windowInEffect() {
    if (window.isBlank()) {
      return serverWindow;
    }
    try {
      return Integer.parseInt(window.trim());
    } catch (NumberFormatException _) {
      return serverWindow;
    }
  }

  /** Whether rolling is in effect without a known size, which is the state the form has to complete. */
  public boolean needsWindow() {
    return usesRolling() && windowInEffect() <= 0;
  }

  /**
   * The value to show in the size field. A rolling session that has no size yet starts on the recommended
   * one, so completing the form is a save rather than a search for a number; anything already stored or typed
   * shows as it is, including a rejected save.
   */
  public String displayedWindow() {
    if (needsWindow() && window.isBlank()) {
      return String.valueOf(recommendedWindow);
    }
    return window;
  }
}
