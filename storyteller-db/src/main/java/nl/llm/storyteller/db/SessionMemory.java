package nl.llm.storyteller.db;

import nl.llm.storyteller.core.model.Message;

import java.util.List;

/**
 * Derived-memory state for a session.
 *
 * <p>{@code contextTargetIndex} mirrors the durable request frontier on the memory row: the newest
 * boundary the mid-term layer must still condense toward. It is written with the turn (after a
 * successful generation) and clamped by an undo, and read back so a rolling request can plan memory
 * against the same boundary it sent. A stored value that predates this column reads back as {@code 0}.
 */
public record SessionMemory(
  String summary,
  String recentSummary,
  String canonicalState,
  int summaryCursor,
  int recentSummaryCursor,
  int canonicalStateCursor,
  int contextTargetIndex,
  List<Message> messages
) {
  public SessionMemory {
    summary = valueOrEmpty(summary);
    recentSummary = valueOrEmpty(recentSummary);
    canonicalState = valueOrEmpty(canonicalState);
    messages = List.copyOf(messages);
  }

  /** Convenience for callers and older rows that carry no request frontier yet. */
  public SessionMemory(
    String summary, String recentSummary, String canonicalState,
    int summaryCursor, int recentSummaryCursor, int canonicalStateCursor, List<Message> messages
  ) {
    this(summary, recentSummary, canonicalState, summaryCursor, recentSummaryCursor, canonicalStateCursor, 0, messages);
  }

  private static String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }
}
