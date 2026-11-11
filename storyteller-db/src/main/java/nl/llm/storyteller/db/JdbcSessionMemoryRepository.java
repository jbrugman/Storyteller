package nl.llm.storyteller.db;

import nl.llm.storyteller.core.model.Message;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class JdbcSessionMemoryRepository implements SessionMemoryRepository {
  private final Database database;

  public JdbcSessionMemoryRepository(Database database) {
    this.database = database;
  }

  @Override
  public SessionMemory load(String sessionId) {
    try (Connection connection = database.openConnection()) {
      MemoryContent content = loadContent(connection, sessionId);
      return new SessionMemory(
        content.summary(), content.recentSummary(), content.canonicalState(), content.summaryCursor(),
        content.recentSummaryCursor(), content.canonicalStateCursor(), content.contextTargetIndex(), List.of()
      );
    } catch (SQLException ex) {
      throw new DatabaseException("Could not load memory for session " + sessionId + ".", ex);
    }
  }

  @Override
  public SessionMemory loadForUpdate(String sessionId) {
    try (Connection connection = database.openConnection()) {
      MemoryContent content = loadContent(connection, sessionId);
      return new SessionMemory(
        content.summary(), content.recentSummary(), content.canonicalState(), content.summaryCursor(),
        content.recentSummaryCursor(), content.canonicalStateCursor(), content.contextTargetIndex(),
        loadMessages(connection, sessionId)
      );
    } catch (SQLException ex) {
      throw new DatabaseException("Could not load memory update data for session " + sessionId + ".", ex);
    }
  }

  @Override
  public boolean updateSummary(String sessionId, SessionMemory expected, String content, int cursor) {
    return update(
      SessionMemoryQueries.UPDATE_SUMMARY, "summary_content", sessionId, expected.summaryCursor(), expected.summary(), content, cursor
    );
  }

  @Override
  public boolean updateRecentSummary(String sessionId, SessionMemory expected, String content, int cursor) {
    return update(
      SessionMemoryQueries.UPDATE_RECENT_SUMMARY, "recent_summary_content",
      sessionId,
      expected.recentSummaryCursor(),
      expected.recentSummary(),
      content,
      cursor
    );
  }

  @Override
  public boolean updateCanonicalState(String sessionId, SessionMemory expected, String content, int cursor) {
    return update(
      SessionMemoryQueries.UPDATE_CANONICAL_STATE, "canonical_state_content",
      sessionId,
      expected.canonicalStateCursor(),
      expected.canonicalState(),
      content,
      cursor
    );
  }

  @Override
  public boolean resetMidTermMemory(String sessionId) {
    return reset(SessionMemoryQueries.RESET_MID_TERM, "recent_summary_content", sessionId);
  }

  @Override
  public boolean resetLongTermMemory(String sessionId) {
    return reset(SessionMemoryQueries.RESET_LONG_TERM, "summary_content", sessionId);
  }

  @Override
  public boolean resetCanonicalState(String sessionId) {
    return reset(SessionMemoryQueries.RESET_CANONICAL_STATE, "canonical_state_content", sessionId);
  }

  private MemoryContent loadContent(Connection connection, String sessionId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SessionMemoryQueries.SELECT_MEMORY)) {
      statement.setString(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new SQLException("Session memory does not exist for session " + sessionId + ".");
        }
        return new MemoryContent(
          database.encryption().decrypt(sessionId, "session_memory.summary_content", resultSet.getString("summary_content")),
          database.encryption().decrypt(sessionId, "session_memory.recent_summary_content", resultSet.getString("recent_summary_content")),
          database.encryption().decrypt(sessionId, "session_memory.canonical_state_content", resultSet.getString("canonical_state_content")),
          resultSet.getInt("summary_cursor"),
          resultSet.getInt("recent_summary_cursor"),
          resultSet.getInt("canonical_state_cursor"),
          resultSet.getInt("context_target_index")
        );
      }
    }
  }

  private List<Message> loadMessages(Connection connection, String sessionId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SessionMemoryQueries.SELECT_MESSAGES)) {
      statement.setString(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<Message> messages = new ArrayList<>();
        while (resultSet.next()) {
          messages.add(new Message(resultSet.getString("message_role"), database.encryption().decrypt(sessionId, "story_message.content", resultSet.getString("content"))));
        }
        return List.copyOf(messages);
      }
    }
  }

  private boolean update(
    String sql,
    String column,
    String sessionId,
    int expectedCursor,
    String expectedContent,
    String content,
    int cursor
  ) {
    try (Connection connection = database.openConnection();
         PreparedStatement select = connection.prepareStatement(
           "SELECT " + column + " FROM session_memory WHERE session_id = ?");
         PreparedStatement statement = connection.prepareStatement(sql)) {
      select.setString(1, sessionId);
      String storedContent;
      try (ResultSet resultSet = select.executeQuery()) {
        if (!resultSet.next()) {
          return false;
        }
        storedContent = resultSet.getString(1);
      }
      String plaintext = database.encryption().decrypt(sessionId, "session_memory." + column, storedContent);
      if (!java.util.Objects.equals(plaintext == null ? "" : plaintext, expectedContent)) {
        return false;
      }
      statement.setString(1, database.encryption().encrypt(sessionId, "session_memory." + column, content));
      statement.setInt(2, cursor);
      statement.setString(3, sessionId);
      statement.setInt(4, expectedCursor);
      statement.setString(5, storedContent == null ? "" : storedContent);
      statement.setInt(6, cursor);
      statement.setString(7, sessionId);
      return statement.executeUpdate() == 1;
    } catch (SQLException ex) {
      throw new DatabaseException("Could not update memory for session " + sessionId + ".", ex);
    }
  }

  private boolean reset(String sql, String column, String sessionId) {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, database.encryption().encrypt(sessionId, "session_memory." + column, ""));
      statement.setString(2, sessionId);
      return statement.executeUpdate() == 1;
    } catch (SQLException ex) {
      throw new DatabaseException("Could not reset memory for session " + sessionId + ".", ex);
    }
  }

  private record MemoryContent(
    String summary,
    String recentSummary,
    String canonicalState,
    int summaryCursor,
    int recentSummaryCursor,
    int canonicalStateCursor,
    int contextTargetIndex
  ) {
  }
}
