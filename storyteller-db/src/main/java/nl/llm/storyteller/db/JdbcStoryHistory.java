package nl.llm.storyteller.db;

import nl.llm.storyteller.core.model.HistoryState;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.service.StoryHistory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

public final class JdbcStoryHistory implements StoryHistory {
  private final Database database;
  private final String sessionId;
  private final JdbcStoryRepository stories;
  private final Clock clock;

  public JdbcStoryHistory(Database database, String sessionId) {
    this(database, sessionId, Clock.systemUTC());
  }

  JdbcStoryHistory(Database database, String sessionId, Clock clock) {
    this.database = database;
    this.sessionId = sessionId;
    this.stories = new JdbcStoryRepository(database);
    this.clock = clock;
  }

  @Override
  public synchronized HistoryState load() {
    try (Connection connection = database.openConnection()) {
      List<Message> messages = loadMessages(connection);
      try (PreparedStatement statement = connection.prepareStatement(ApplicationStorageQueries.SELECT_HISTORY)) {
        statement.setString(1, sessionId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            throw new SQLException("Session memory does not exist for session " + sessionId + ".");
          }
          return new HistoryState(
            messages,
            resultSet.getInt("summary_cursor"),
            resultSet.getInt("recent_summary_cursor"),
            resultSet.getInt("canonical_state_cursor"),
            resultSet.getInt("context_target_index")
          );
        }
      }
    } catch (SQLException ex) {
      throw new DatabaseException("Could not load CLI story history.", ex);
    }
  }

  @Override
  public synchronized void save(HistoryState state) {
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        deleteMessages(connection);
        insertMessages(connection, state.messages());
        updateCursors(connection, state);
        saveContextTarget(connection, state);
        connection.commit();
      } catch (SQLException ex) {
        rollback(connection, ex);
        throw ex;
      }
    } catch (SQLException ex) {
      throw new DatabaseException("Could not save CLI story history.", ex);
    }
  }

  @Override
  public synchronized void appendTurn(String userInput, String assistantResponse) {
    appendTurn(userInput, assistantResponse, 0);
  }

  @Override
  public synchronized void appendTurn(String userInput, String assistantResponse, int contextTarget) {
    stories.appendTurn(sessionId, userInput, assistantResponse, null, clock.instant(), contextTarget);
  }

  @Override
  public synchronized String removeLastTurn() {
    HistoryState state = load();
    int lastUserIndex = lastUserIndex(state.messages());
    if (lastUserIndex < 0) {
      return "";
    }
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        deleteMessagesFrom(connection, lastUserIndex);
        int remaining = lastUserIndex;
        clampMemoryCursors(connection, remaining);
        connection.commit();
      } catch (SQLException ex) {
        rollback(connection, ex);
        throw ex;
      }
    } catch (SQLException ex) {
      throw new DatabaseException("Could not remove the last CLI story turn.", ex);
    }
    return state.messages().get(lastUserIndex).content();
  }

  @Override
  public synchronized LastTurn loadLastTurn() {
    List<Message> messages = load().messages();
    int lastUserIndex = lastUserIndex(messages);
    if (lastUserIndex < 0) {
      return new LastTurn("", "");
    }
    String assistantResponse = messages.subList(lastUserIndex + 1, messages.size()).stream()
      .filter(message -> "assistant".equals(message.role()))
      .map(Message::content)
      .findFirst()
      .orElse("");
    return new LastTurn(messages.get(lastUserIndex).content(), assistantResponse);
  }

  @Override
  public synchronized List<Message> recentMessages(int limitTurns) {
    return recentTurns(load().messages(), limitTurns);
  }

  @Override
  public synchronized List<Message> recentMessagesWindow(int totalTurns, int trailingTurnsToExclude) {
    List<Message> messages = load().messages();
    List<Message> window = recentTurns(messages, totalTurns);
    int trailing = recentTurns(messages, trailingTurnsToExclude).size();
    return List.copyOf(window.subList(0, Math.max(0, window.size() - trailing)));
  }

  @Override
  public synchronized void markSummarized(int messagesCount) {
    HistoryState state = load();
    updateCursors(Math.min(messagesCount, state.messages().size()), state.recentSummaryCursor(), state.canonicalStateCursor());
  }

  @Override
  public synchronized void markRecentSummarized(int messagesCount) {
    HistoryState state = load();
    updateCursors(state.summaryCursor(), Math.min(messagesCount, state.messages().size()), state.canonicalStateCursor());
  }

  @Override
  public synchronized void markCanonicalStateUpdated(int messagesCount) {
    HistoryState state = load();
    updateCursors(state.summaryCursor(), state.recentSummaryCursor(), Math.min(messagesCount, state.messages().size()));
  }

  private List<Message> loadMessages(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ApplicationStorageQueries.SELECT_MESSAGES)) {
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

  private void deleteMessages(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(StoryQueries.DELETE_MESSAGES)) {
      statement.setString(1, sessionId);
      statement.executeUpdate();
    }
  }

  private void deleteMessagesFrom(Connection connection, int messageIndex) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(StoryQueries.DELETE_MESSAGES_FROM)) {
      statement.setString(1, sessionId);
      statement.setInt(2, messageIndex);
      statement.executeUpdate();
    }
  }

  private void insertMessages(Connection connection, List<Message> messages) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(StoryQueries.INSERT_MESSAGE)) {
      statement.setNull(5, java.sql.Types.VARCHAR);
      statement.setNull(6, java.sql.Types.BLOB);
      for (int index = 0; index < messages.size(); index++) {
        Message message = messages.get(index);
        statement.setString(1, sessionId);
        statement.setInt(2, index);
        statement.setString(3, message.role());
        statement.setString(4, database.encryption().encrypt(sessionId, "story_message.content", message.content()));
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private void updateCursors(Connection connection, HistoryState state) throws SQLException {
    updateCursors(connection, state.summaryCursor(), state.recentSummaryCursor(), state.canonicalStateCursor());
  }

  private void updateCursors(int summary, int recentSummary, int canonicalState) {
    try (Connection connection = database.openConnection()) {
      updateCursors(connection, summary, recentSummary, canonicalState);
    } catch (SQLException ex) {
      throw new DatabaseException("Could not update CLI story cursors.", ex);
    }
  }

  private void updateCursors(Connection connection, int summary, int recentSummary, int canonicalState)
    throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ApplicationStorageQueries.UPDATE_CURSORS)) {
      statement.setInt(1, summary);
      statement.setInt(2, recentSummary);
      statement.setInt(3, canonicalState);
      statement.setString(4, sessionId);
      statement.executeUpdate();
    }
  }

  /**
   * Writes the request frontier for a full state replace (the {@code save} back door used outside the
   * live turn flow). In the live flow the frontier only grows on {@code appendTurn} and only shrinks on
   * {@code removeLastTurn}; a replace writes the caller's value verbatim.
   */
  private void saveContextTarget(Connection connection, HistoryState state) throws SQLException {
    try (PreparedStatement statement =
           connection.prepareStatement(ApplicationStorageQueries.UPDATE_CONTEXT_TARGET)) {
      statement.setInt(1, state.contextTargetIndex());
      statement.setString(2, sessionId);
      statement.executeUpdate();
    }
  }

  /**
   * Undo: pull every derived-memory cursor and the request frontier back to the message count that
   * survives the undo, so a removed turn is no longer treated as summarized and no frontier claims to
   * have condensed past the story that remains. Mirrors the API undo clamp, in the same transaction.
   */
  private void clampMemoryCursors(Connection connection, int remaining) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(StoryQueries.CLAMP_MEMORY_CURSORS)) {
      statement.setInt(1, remaining);
      statement.setInt(2, remaining);
      statement.setInt(3, remaining);
      statement.setInt(4, remaining);
      statement.setString(5, sessionId);
      statement.executeUpdate();
    }
  }

  private List<Message> recentTurns(List<Message> messages, int turns) {
    if (turns <= 0 || messages.isEmpty()) {
      return List.of();
    }
    int users = 0;
    int start = messages.size();
    for (int index = messages.size() - 1; index >= 0; index--) {
      if ("user".equals(messages.get(index).role())) {
        users++;
        start = index;
        if (users == turns) {
          break;
        }
      }
    }
    return List.copyOf(messages.subList(start, messages.size()));
  }

  private int lastUserIndex(List<Message> messages) {
    for (int index = messages.size() - 1; index >= 0; index--) {
      if ("user".equals(messages.get(index).role())) {
        return index;
      }
    }
    return -1;
  }

  private void rollback(Connection connection, SQLException original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
    }
  }
}
