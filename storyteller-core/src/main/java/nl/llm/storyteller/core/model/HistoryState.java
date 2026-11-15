package nl.llm.storyteller.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted story history together with the derived-memory bookkeeping for a session.
 *
 * <p>{@code contextTargetIndex} is the durable high-water mark of the request frontier: the newest
 * boundary derived memory must still condense up to. It only ever grows when a turn is appended and
 * only shrinks when a turn is undone, so an asynchronous memory pass can resume toward it after a
 * restart. A stored value that is absent (older files) reads back as {@code 0}.
 */
public record HistoryState(
  List<Message> messages,
  int summaryCursor,
  int recentSummaryCursor,
  int canonicalStateCursor,
  int contextTargetIndex
) {
  /** Convenience for callers and older data that carry no request frontier yet. */
  public HistoryState(List<Message> messages, int summaryCursor, int recentSummaryCursor, int canonicalStateCursor) {
    this(messages, summaryCursor, recentSummaryCursor, canonicalStateCursor, 0);
  }

  public static HistoryState empty() {
    return new HistoryState(
      new ArrayList<>(),
      0,
      0,
      0,
      0
    );
  }
}
