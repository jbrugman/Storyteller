package nl.llm.storyteller.api.http.dto;

import nl.llm.storyteller.db.StoryMessageRecord;

import java.util.List;

// referenceBeforeMessageIndex is the exclusive cutoff for "use as past context": only exchanges with a
// lower messageIndex lie outside the recent story window and can therefore be referenced by a later turn.
// hasOlder is true only when a limited page stopped above the first message, so older pages can still be requested.
public record SessionHistoryResponse(
  String sessionId,
  List<StoryMessageRecord> messages,
  int referenceBeforeMessageIndex,
  boolean hasOlder
) { }
