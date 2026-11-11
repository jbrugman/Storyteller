package nl.llm.storyteller.db;

final class StoryQueries {
  static final String DELETE_MESSAGES = "DELETE FROM story_message WHERE session_id = ?";
  static final String DELETE_MESSAGES_FROM = """
    DELETE FROM story_message
    WHERE session_id = ? AND message_index >= ?
    """;
  static final String SELECT_RECENT_MESSAGES = """
    SELECT message_index, message_role, content, image_media_type, image_content
    FROM story_message
    WHERE session_id = ?
    ORDER BY message_index DESC
    LIMIT ?
    """;
  static final String SELECT_MESSAGES_BEFORE = """
    SELECT message_index, message_role, content, image_media_type, image_content
    FROM story_message
    WHERE session_id = ? AND message_index < ?
    ORDER BY message_index DESC
    LIMIT ?
    """;
  static final String SELECT_LAST_MESSAGE_INDEX = """
    SELECT MAX(message_index) AS last_message_index
    FROM story_message
    WHERE session_id = ?
    """;
  static final String SELECT_PAST_EXCHANGES = """
    SELECT user_message.message_index,
           user_message.content AS prompt,
           assistant_message.content AS response
    FROM story_message user_message
    JOIN story_message assistant_message
      ON assistant_message.session_id = user_message.session_id
     AND assistant_message.message_index = user_message.message_index + 1
     AND assistant_message.message_role = 'assistant'
    WHERE user_message.session_id = ?
      AND user_message.message_role = 'user'
      AND user_message.message_index IN (?, ?, ?)
    ORDER BY user_message.message_index
    """;
  static final String INSERT_MESSAGE = """
    INSERT INTO story_message (
      session_id, message_index, message_role, content, image_media_type, image_content
    )
    VALUES (?, ?, ?, ?, ?, ?)
    """;
  static final String SELECT_IMAGE = """
    SELECT image_media_type, image_content
    FROM story_message
    WHERE session_id = ? AND message_index = ?
    """;
  static final String UPDATE_SESSION_AFTER_TURN = """
    UPDATE story_session
    SET updated_at = ?
    WHERE session_id = ?
    """;
  static final String UPDATE_ASSISTANT_MESSAGE = """
    UPDATE story_message
    SET content = ?
    WHERE session_id = ?
      AND message_index = ?
      AND message_role = 'assistant'
    """;
  static final String DELETE_MESSAGE = """
    DELETE FROM story_message
    WHERE session_id = ? AND message_index = ?
    """;
  static final String CLAMP_MEMORY_CURSORS = """
    UPDATE session_memory
    SET summary_cursor = LEAST(summary_cursor, ?),
        recent_summary_cursor = LEAST(recent_summary_cursor, ?),
        canonical_state_cursor = LEAST(canonical_state_cursor, ?),
        context_target_index = LEAST(context_target_index, ?)
    WHERE session_id = ?
    """;
  static final String RAISE_CONTEXT_TARGET = """
    UPDATE session_memory
    SET context_target_index = GREATEST(context_target_index, ?)
    WHERE session_id = ?
    """;
  static final String DELETE_UNDONE_TURN_BASED_FACTS = """
    DELETE FROM knowledge_fact
    WHERE session_id = ?
      AND fact_source = 'TURNBASED'
      AND source_turn > ?
    """;
  static final String INCREMENT_GRAPH_REVISION = """
    UPDATE knowledge_graph
    SET revision = revision + 1
    WHERE session_id = ?
    """;

  private StoryQueries() {
  }
}
