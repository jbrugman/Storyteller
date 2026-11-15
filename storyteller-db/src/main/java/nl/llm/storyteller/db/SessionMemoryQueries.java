package nl.llm.storyteller.db;

final class SessionMemoryQueries {
  static final String SELECT_MEMORY = """
    SELECT summary_content, recent_summary_content, canonical_state_content,
           summary_cursor, recent_summary_cursor, canonical_state_cursor, context_target_index
    FROM session_memory
    WHERE session_id = ?
    """;
  static final String SELECT_MESSAGES = """
    SELECT message_role, content
    FROM story_message
    WHERE session_id = ?
    ORDER BY message_index
    """;
  static final String UPDATE_SUMMARY = update("summary_content", "summary_cursor");
  static final String UPDATE_RECENT_SUMMARY = update("recent_summary_content", "recent_summary_cursor");
  static final String UPDATE_CANONICAL_STATE = update("canonical_state_content", "canonical_state_cursor");
  static final String RESET_MID_TERM = reset("recent_summary_content", "recent_summary_cursor");
  static final String RESET_LONG_TERM = reset("summary_content", "summary_cursor");
  static final String RESET_CANONICAL_STATE = reset("canonical_state_content", "canonical_state_cursor");

  private SessionMemoryQueries() {
  }

  private static String update(String contentColumn, String cursorColumn) {
    return """
      UPDATE session_memory
      SET %s = ?, %s = ?
      WHERE session_id = ?
        AND %s = ?
        AND COALESCE(%s, '') = ?
        AND ? <= (SELECT COUNT(*) FROM story_message WHERE session_id = ?)
      """.formatted(contentColumn, cursorColumn, cursorColumn, contentColumn);
  }

  private static String reset(String contentColumn, String cursorColumn) {
    return """
      UPDATE session_memory
      SET %s = ?, %s = 0
      WHERE session_id = ?
      """.formatted(contentColumn, cursorColumn);
  }
}
