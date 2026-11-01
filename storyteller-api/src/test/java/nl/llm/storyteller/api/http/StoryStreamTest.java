package nl.llm.storyteller.api.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoryStreamTest {
  @Test
  @DisplayName("""
    Given a successfully generated and persisted turn,
    When the final SSE event is serialized,
    Then its indexes and complete text are sent once without a generation error
    """)
  void shouldSendCompletedTurn() throws Exception {
    StoryStreamNativeProbe.verifyCompletion();
  }

  @Test
  @DisplayName("""
    Given generation interrupted after partial text,
    When the stream terminates,
    Then it emits an error and no successful completion
    """)
  void shouldPreserveRealFailures() throws Exception {
    StoryStreamNativeProbe.verifyFailure();
  }
}
