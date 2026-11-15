package nl.llm.storyteller.db;

import nl.llm.storyteller.core.model.Message;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static nl.llm.storyteller.db.StoryQueries.DELETE_MESSAGE;
import static nl.llm.storyteller.db.StoryQueries.INSERT_MESSAGE;
import static nl.llm.storyteller.db.StoryQueries.SELECT_IMAGE;
import static nl.llm.storyteller.db.StoryQueries.SELECT_LAST_MESSAGE_INDEX;
import static nl.llm.storyteller.db.StoryQueries.SELECT_MESSAGES_BEFORE;
import static nl.llm.storyteller.db.StoryQueries.SELECT_PAST_EXCHANGES;
import static nl.llm.storyteller.db.StoryQueries.SELECT_RECENT_MESSAGES;
import static nl.llm.storyteller.db.StoryQueries.UPDATE_SESSION_AFTER_TURN;

public final class JdbcStoryRepository implements StoryRepository {
  private static final String MESSAGE_INDEX = "message_index";
  private static final String MESSAGE_ROLE = "message_role";
  private static final String CONTENT = "content";
  private static final String IMAGE_MEDIA_TYPE = "image_media_type";
  private static final String IMAGE_CONTENT = "image_content";
  private static final String LAST_MESSAGE_INDEX = "last_message_index";
  private static final String USER = "user";
  private static final String ASSISTANT = "assistant";
  private static final String LOAD_MESSAGES_ERROR = "Could not load story messages for session ";

  private final Database database;

  public JdbcStoryRepository(Database database) {
    this.database = database;
  }

  @Override
  public List<Message> loadRecentMessages(String sessionId, int maximumMessages) {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(SELECT_RECENT_MESSAGES)) {
      statement.setString(1, sessionId);
      statement.setInt(2, maximumMessages);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<Message> messages = readMessages(resultSet, sessionId);
        return List.copyOf(messages.reversed());
      }
    } catch (SQLException ex) {
      throw new DatabaseException(LOAD_MESSAGES_ERROR + sessionId + ".", ex);
    }
  }

  @Override
  public List<StoryMessageRecord> loadMessagesBefore(
    String sessionId,
    int beforeMessageIndex,
    int maximumMessages
  ) {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(SELECT_MESSAGES_BEFORE)) {
      statement.setString(1, sessionId);
      statement.setInt(2, beforeMessageIndex);
      statement.setInt(3, maximumMessages);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<StoryMessageRecord> messages = new ArrayList<>();
        while (resultSet.next()) {
          messages.add(new StoryMessageRecord(
            resultSet.getInt(MESSAGE_INDEX),
            resultSet.getString(MESSAGE_ROLE),
            database.encryption().decrypt(sessionId, "story_message.content", resultSet.getString(CONTENT)),
            resultSet.getBytes(IMAGE_CONTENT) != null
          ));
        }
        return List.copyOf(messages.reversed());
      }
    } catch (SQLException ex) {
      throw new DatabaseException("Could not load story message page for session " + sessionId + ".", ex);
    }
  }

  @Override
  public Optional<StoryImage> loadImage(String sessionId, int messageIndex) {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(SELECT_IMAGE)) {
      statement.setString(1, sessionId);
      statement.setInt(2, messageIndex);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return Optional.empty();
        }
        String mediaType = resultSet.getString(IMAGE_MEDIA_TYPE);
        byte[] content = database.encryption().decrypt(sessionId, "story_message.image_content", resultSet.getBytes(IMAGE_CONTENT));
        return content == null || mediaType == null
          ? Optional.empty()
          : Optional.of(new StoryImage(mediaType, content));
      }
    } catch (SQLException ex) {
      throw new DatabaseException("Could not load story image for session " + sessionId + ".", ex);
    }
  }

  @Override
  public int lastMessageIndex(String sessionId) {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(SELECT_LAST_MESSAGE_INDEX)) {
      statement.setString(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        int messageIndex = resultSet.getInt(LAST_MESSAGE_INDEX);
        return resultSet.wasNull() ? -1 : messageIndex;
      }
    } catch (SQLException ex) {
      throw new DatabaseException(LOAD_MESSAGES_ERROR + sessionId + ".", ex);
    }
  }

  @Override
  public List<PastStoryExchange> loadPastExchanges(String sessionId, List<Integer> messageIndexes) {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(SELECT_PAST_EXCHANGES)) {
      statement.setString(1, sessionId);
      for (int index = 0; index < 3; index++) {
        statement.setInt(index + 2, index < messageIndexes.size() ? messageIndexes.get(index) : -1);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        List<PastStoryExchange> exchanges = new ArrayList<>();
        while (resultSet.next()) {
          exchanges.add(new PastStoryExchange(
            resultSet.getInt(MESSAGE_INDEX),
            database.encryption().decrypt(sessionId, "story_message.content", resultSet.getString("prompt")),
            database.encryption().decrypt(sessionId, "story_message.content", resultSet.getString("response"))
          ));
        }
        return List.copyOf(exchanges);
      }
    } catch (SQLException ex) {
      throw new DatabaseException(LOAD_MESSAGES_ERROR + sessionId + ".", ex);
    }
  }

  private List<Message> readMessages(ResultSet resultSet, String sessionId) throws SQLException {
    List<Message> messages = new ArrayList<>();
    while (resultSet.next()) {
      String role = resultSet.getString(MESSAGE_ROLE);
      String content = database.encryption().decrypt(sessionId, "story_message.content", resultSet.getString(CONTENT));
      String mediaType = resultSet.getString(IMAGE_MEDIA_TYPE);
      byte[] imageContent = database.encryption().decrypt(sessionId, "story_message.image_content", resultSet.getBytes(IMAGE_CONTENT));
      messages.add(imageContent == null || mediaType == null
        ? new Message(role, content)
        : Message.withImage(role, content, new StoryImage(mediaType, imageContent).dataUrl()));
    }
    return List.copyOf(messages);
  }

  @Override
  public StoryTurnRecord appendTurn(
    String sessionId,
    String userInput,
    String assistantResponse,
    StoryImage image,
    Instant updatedAt
  ) {
    return appendTurn(sessionId, userInput, assistantResponse, image, updatedAt, 0);
  }

  @Override
  public StoryTurnRecord appendTurn(
    String sessionId,
    String userInput,
    String assistantResponse,
    StoryImage image,
    Instant updatedAt,
    int contextTarget
  ) {
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      return appendInTransaction(connection, sessionId, userInput, assistantResponse, image, updatedAt, contextTarget);
    } catch (SQLException ex) {
      throw new DatabaseException("Could not append story turn for session " + sessionId + ".", ex);
    }
  }

  @Override
  public boolean undoLastTurn(String sessionId, Instant updatedAt) {
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      return undoInTransaction(connection, sessionId, updatedAt);
    } catch (SQLException ex) {
      throw new DatabaseException("Could not undo the last story turn for session " + sessionId + ".", ex);
    }
  }

  @Override
  public boolean updateAssistantMessage(String sessionId, int messageIndex, String content, Instant updatedAt) {
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      return updateAssistantMessageInTransaction(connection, sessionId, messageIndex, content, updatedAt);
    } catch (SQLException ex) {
      throw new DatabaseException("Could not update story message " + messageIndex + " for session "
        + sessionId + ".", ex);
    }
  }

  private boolean updateAssistantMessageInTransaction(
    Connection connection,
    String sessionId,
    int messageIndex,
    String content,
    Instant updatedAt
  ) throws SQLException {
    try {
      int updatedMessages;
      try (PreparedStatement statement = connection.prepareStatement(StoryQueries.UPDATE_ASSISTANT_MESSAGE)) {
        statement.setString(1, database.encryption().encrypt(sessionId, "story_message.content", content));
        statement.setString(2, sessionId);
        statement.setInt(3, messageIndex);
        updatedMessages = statement.executeUpdate();
      }
      if (updatedMessages != 1) {
        connection.rollback();
        return false;
      }
      updateSession(connection, sessionId, updatedAt);
      connection.commit();
      return true;
    } catch (SQLException ex) {
      rollback(connection, ex);
      throw ex;
    }
  }

  private boolean undoInTransaction(Connection connection, String sessionId, Instant updatedAt)
    throws SQLException {
    try {
      List<Integer> messageIndexes = lastCompleteTurnIndexes(connection, sessionId);
      if (messageIndexes.isEmpty()) {
        connection.rollback();
        return false;
      }
      for (int messageIndex : messageIndexes) {
        deleteMessage(connection, sessionId, messageIndex);
      }
      int remainingMessageCount = messageIndexes.getLast();
      clampMemoryCursors(connection, sessionId, remainingMessageCount);
      removeUndoneTurnBasedFacts(connection, sessionId, remainingMessageCount / 2);
      updateSession(connection, sessionId, updatedAt);
      connection.commit();
      return true;
    } catch (SQLException ex) {
      rollback(connection, ex);
      throw ex;
    }
  }

  private List<Integer> lastCompleteTurnIndexes(Connection connection, String sessionId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SELECT_RECENT_MESSAGES)) {
      statement.setString(1, sessionId);
      statement.setInt(2, 2);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next() || !ASSISTANT.equals(resultSet.getString(MESSAGE_ROLE))) {
          return List.of();
        }
        int assistantIndex = resultSet.getInt(MESSAGE_INDEX);
        if (!resultSet.next() || !USER.equals(resultSet.getString(MESSAGE_ROLE))) {
          return List.of();
        }
        return List.of(assistantIndex, resultSet.getInt(MESSAGE_INDEX));
      }
    }
  }

  private void deleteMessage(Connection connection, String sessionId, int messageIndex) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(DELETE_MESSAGE)) {
      statement.setString(1, sessionId);
      statement.setInt(2, messageIndex);
      statement.executeUpdate();
    }
  }

  private void clampMemoryCursors(Connection connection, String sessionId, int remainingMessageCount)
    throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(StoryQueries.CLAMP_MEMORY_CURSORS)) {
      statement.setInt(1, remainingMessageCount);
      statement.setInt(2, remainingMessageCount);
      statement.setInt(3, remainingMessageCount);
      statement.setInt(4, remainingMessageCount);
      statement.setString(5, sessionId);
      statement.executeUpdate();
    }
  }

  private void removeUndoneTurnBasedFacts(Connection connection, String sessionId, int remainingTurnCount)
    throws SQLException {
    int removedFacts;
    try (PreparedStatement statement = connection.prepareStatement(StoryQueries.DELETE_UNDONE_TURN_BASED_FACTS)) {
      statement.setString(1, sessionId);
      statement.setInt(2, remainingTurnCount);
      removedFacts = statement.executeUpdate();
    }
    if (removedFacts == 0) {
      return;
    }
    try (PreparedStatement statement = connection.prepareStatement(StoryQueries.INCREMENT_GRAPH_REVISION)) {
      statement.setString(1, sessionId);
      statement.executeUpdate();
    }
  }

  private StoryTurnRecord appendInTransaction(
    Connection connection,
    String sessionId,
    String userInput,
    String assistantResponse,
    StoryImage image,
    Instant updatedAt,
    int contextTarget
  ) throws SQLException {
    try {
      int userMessageIndex = nextMessageIndex(connection, sessionId);
      insertMessage(connection, sessionId, userMessageIndex, USER, userInput, image);
      int assistantMessageIndex = userMessageIndex + 1;
      insertMessage(connection, sessionId, assistantMessageIndex, ASSISTANT, assistantResponse, null);
      updateSession(connection, sessionId, updatedAt);
      raiseContextTarget(connection, sessionId, contextTarget);
      connection.commit();
      return new StoryTurnRecord(userMessageIndex, assistantMessageIndex);
    } catch (SQLException ex) {
      rollback(connection, ex);
      throw ex;
    }
  }

  /**
   * Raises the durable request frontier to {@code contextTarget} with {@code GREATEST}, so it never
   * moves backwards. A non-positive target is a no-op. The update runs on the append transaction, so a
   * turn and the boundary derived memory must condense toward commit or roll back together.
   */
  private void raiseContextTarget(Connection connection, String sessionId, int contextTarget)
    throws SQLException {
    if (contextTarget <= 0) {
      return;
    }
    try (PreparedStatement statement = connection.prepareStatement(StoryQueries.RAISE_CONTEXT_TARGET)) {
      statement.setInt(1, contextTarget);
      statement.setString(2, sessionId);
      statement.executeUpdate();
    }
  }

  private int nextMessageIndex(Connection connection, String sessionId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SELECT_LAST_MESSAGE_INDEX)) {
      statement.setString(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        int lastIndex = resultSet.getInt(LAST_MESSAGE_INDEX);
        return resultSet.wasNull() ? 0 : lastIndex + 1;
      }
    }
  }

  private void insertMessage(
    Connection connection,
    String sessionId,
    int messageIndex,
    String role,
    String content,
    StoryImage image
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_MESSAGE)) {
      statement.setString(1, sessionId);
      statement.setInt(2, messageIndex);
      statement.setString(3, role);
      statement.setString(4, database.encryption().encrypt(sessionId, "story_message.content", content));
      if (image == null) {
        statement.setNull(5, java.sql.Types.VARCHAR);
        statement.setNull(6, java.sql.Types.BLOB);
      } else {
        statement.setString(5, image.mediaType());
        statement.setBytes(6, database.encryption().encrypt(sessionId, "story_message.image_content", image.content()));
      }
      statement.executeUpdate();
    }
  }

  private void updateSession(Connection connection, String sessionId, Instant updatedAt) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UPDATE_SESSION_AFTER_TURN)) {
      statement.setTimestamp(1, Timestamp.from(updatedAt));
      statement.setString(2, sessionId);
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Session no longer exists.");
      }
    }
  }

  private void rollback(Connection connection, SQLException original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
    }
  }
}
