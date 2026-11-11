package nl.llm.storyteller.api.web;

import nl.llm.storyteller.core.service.RollingContextWindow;
import nl.llm.storyteller.db.SessionMemory;

/**
 * The memory page. {@code context} reports how the next request would be assembled, which is what tells
 * a user whether the layers below are ahead of, or falling behind, the story they summarize.
 */
public record StoryMemoryPage(
  SessionMemory memory,
  RollingContextWindow.Report context,
  String notificationTitle,
  String notificationMessage
) {
  /** Older story turns that reach the model only through the layers above. */
  public int summarizedMessages() {
    return context.summarizedMessages();
  }

  public boolean hasWarning() {
    return !context.warning().isBlank();
  }

  public boolean hasNotification() {
    return !notificationMessage.isBlank();
  }
}
