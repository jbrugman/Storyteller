package nl.llm.storyteller.db;

final class ApplicationStorageQueries {
  static final String SELECT_HISTORY = """
    SELECT summary_cursor, recent_summary_cursor, canonical_state_cursor, context_target_index
    FROM session_memory
    WHERE session_id = ?
    """;
  static final String SELECT_MESSAGES = """
    SELECT message_role, content
    FROM story_message
    WHERE session_id = ?
    ORDER BY message_index
    """;
  static final String UPDATE_CURSORS = """
    UPDATE session_memory
    SET summary_cursor = ?, recent_summary_cursor = ?, canonical_state_cursor = ?
    WHERE session_id = ?
    """;
  static final String UPDATE_CONTEXT_TARGET = """
    UPDATE session_memory
    SET context_target_index = ?
    WHERE session_id = ?
    """;
  static final String SELECT_SUMMARY = "SELECT summary_content FROM session_memory WHERE session_id = ?";
  static final String UPDATE_SUMMARY = "UPDATE session_memory SET summary_content = ? WHERE session_id = ?";
  static final String SELECT_RECENT_SUMMARY =
    "SELECT recent_summary_content FROM session_memory WHERE session_id = ?";
  static final String UPDATE_RECENT_SUMMARY =
    "UPDATE session_memory SET recent_summary_content = ? WHERE session_id = ?";
  static final String SELECT_CANONICAL_STATE =
    "SELECT canonical_state_content FROM session_memory WHERE session_id = ?";
  static final String UPDATE_CANONICAL_STATE =
    "UPDATE session_memory SET canonical_state_content = ? WHERE session_id = ?";

  private ApplicationStorageQueries() {
  }
}
