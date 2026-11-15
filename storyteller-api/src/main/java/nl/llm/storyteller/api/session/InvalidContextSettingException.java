package nl.llm.storyteller.api.session;

/**
 * A session asked for rolling context without naming the model window it wants to fill. It extends
 * {@link IllegalArgumentException} so the HTTP API keeps answering with the same 400 it uses for every
 * rejected setting, while the built-in interface catches it and marks the context fields instead.
 */
public final class InvalidContextSettingException extends IllegalArgumentException {
  public InvalidContextSettingException(String message) {
    super(message);
  }
}
