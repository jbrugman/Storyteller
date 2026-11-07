package nl.llm.storyteller.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public final class JdbcSessionSettingsRepository implements SessionSettingsRepository {
  private static final String SELECT_SESSION_CONFIGURATION = """
    SELECT temperature, streaming_enabled, validation_enabled, graph_enabled, max_tokens
      FROM session_configuration WHERE session_id = ?
    """;
  private static final String UPDATE_SESSION_CONFIGURATION_TEMPERATURE = """
    UPDATE session_configuration SET temperature = ? WHERE session_id = ?
    """;
  private static final String UPDATE_SESSION_CONFIGURATION_MAX_TOKENS = """
    UPDATE session_configuration SET max_tokens = ? WHERE session_id = ?
    """;

  private final Database database;
  private final JdbcSessionBundleRepository bundleRepository;

  public JdbcSessionSettingsRepository(Database database) {
    this.database = database;
    this.bundleRepository = new JdbcSessionBundleRepository(database);
  }

  @Override
  public SessionSettings load(String sessionId) {
    try (Connection connection = database.openConnection()) {
      var prompts = loadPrompts(connection, sessionId);
      var graph = bundleRepository.loadGraph(connection, sessionId);
      try (PreparedStatement statement = connection.prepareStatement(SELECT_SESSION_CONFIGURATION)) {
        statement.setString(1, sessionId);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) throw new SQLException("Session configuration does not exist: " + sessionId);
          BigDecimal temperature = rows.getBigDecimal("temperature");
          return new SessionSettings(prompts, graph, temperature == null ? null : temperature.doubleValue(),
            rows.getObject("streaming_enabled", Boolean.class), rows.getObject("validation_enabled", Boolean.class),
            rows.getObject("graph_enabled", Boolean.class), rows.getObject("max_tokens", Integer.class));
        }
      }
    } catch (SQLException ex) {
      throw new DatabaseException("Could not load settings for session " + sessionId + ".", ex);
    }
  }

  @Override
  public void save(String sessionId, SessionSettings settings) {
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      saveInTransaction(connection, sessionId, settings);
    } catch (SQLException ex) {
      throw new DatabaseException("Could not save settings for session " + sessionId + ".", ex);
    }
  }

  @Override
  public void setTemperature(String sessionId, Double temperature) {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(UPDATE_SESSION_CONFIGURATION_TEMPERATURE)) {
      BigDecimal bd = temperature != null ? BigDecimal.valueOf(temperature) : null;
      statement.setBigDecimal(1, bd);
      statement.setString(2, sessionId);
      requireUpdated(statement, "Session configuration does not exist: " + sessionId);
    } catch (SQLException ex) {
      throw new DatabaseException("Could not set temperature for session " + sessionId + ".", ex);
    }
  }

  @Override
  public void patchConfiguration(
    String sessionId, Map<String, String> prompts, boolean updateTemperature, Double temperature
  ) {
    patchConfiguration(sessionId, prompts, updateTemperature, temperature, false, null, null, false, null);
  }

  @Override
  public void patchConfiguration(String sessionId, Map<String, String> prompts, boolean updateTemperature,
    Double temperature, boolean updateGeneration, Boolean streamingEnabled, Boolean validationEnabled,
    boolean updateGraph, Boolean graphEnabled) {
    patchConfiguration(sessionId, prompts, updateTemperature, temperature, updateGeneration, streamingEnabled,
      validationEnabled, updateGraph, graphEnabled, false, null);
  }

  @Override
  public void patchConfiguration(String sessionId, Map<String, String> prompts, boolean updateTemperature,
    Double temperature, boolean updateGeneration, Boolean streamingEnabled, Boolean validationEnabled,
    boolean updateGraph, Boolean graphEnabled, boolean updateMaxTokens, Integer maxTokens) {
    if (Boolean.TRUE.equals(streamingEnabled) && Boolean.TRUE.equals(validationEnabled)) {
      throw new IllegalArgumentException("Streaming and validation cannot both be enabled.");
    }
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        for (var prompt : prompts.entrySet()) {
          updatePrompt(connection, sessionId, prompt.getKey(), prompt.getValue());
        }
        if (updateTemperature) {
          updateTemperatureInTransaction(connection, sessionId, temperature);
        }
        if (updateGeneration || updateGraph) {
          updateGeneration(connection, sessionId, streamingEnabled, validationEnabled, graphEnabled);
        }
        if (updateMaxTokens) {
          updateMaxTokensInTransaction(connection, sessionId, maxTokens);
        }
        connection.commit();
      } catch (SQLException ex) {
        rollback(connection, ex);
        throw ex;
      }
    } catch (SQLException ex) {
      throw new DatabaseException("Could not patch configuration for session " + sessionId + ".", ex);
    }
  }

  private void updateGeneration(Connection connection, String sessionId, Boolean streaming, Boolean validation,
    Boolean graph) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
      "UPDATE session_configuration SET streaming_enabled = ?, validation_enabled = ?, graph_enabled = ?"
        + " WHERE session_id = ?")) {
      statement.setObject(1, streaming, java.sql.Types.BOOLEAN);
      statement.setObject(2, validation, java.sql.Types.BOOLEAN);
      statement.setObject(3, graph, java.sql.Types.BOOLEAN);
      statement.setString(4, sessionId);
      requireUpdated(statement, "Session configuration does not exist: " + sessionId);
    }
  }

  private SessionPrompts loadPrompts(Connection connection, String sessionId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SessionPromptQueries.SELECT_PROMPTS)) {
      statement.setString(1, sessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return SessionPromptPersistenceSupport.readPrompts(resultSet, database.encryption(), sessionId);
      }
    }
  }

  private void saveInTransaction(Connection connection, String sessionId, SessionSettings settings)
    throws SQLException {
    try {
      updatePrompt(connection, sessionId, SessionPrompts.SYSTEM_PROMPT_NAME, settings.prompts().systemPrompt());
      updatePrompt(
        connection,
        sessionId,
        SessionPrompts.FIXED_PROTAGONISTS_NAME,
        settings.prompts().fixedProtagonists()
      );
      updatePrompt(connection, sessionId, SessionPrompts.RULES_NAME, settings.prompts().rules());
      bundleRepository.replaceGraph(connection, sessionId, settings.knowledgeGraph());
      updateTemperatureInTransaction(connection, sessionId, settings.temperature());
      updateGeneration(connection, sessionId, settings.streamingEnabled(), settings.validationEnabled(),
        settings.graphEnabled());
      updateMaxTokensInTransaction(connection, sessionId, settings.maxTokens());
      connection.commit();
    } catch (SQLException ex) {
      rollback(connection, ex);
      throw ex;
    }
  }

  private void updateTemperatureInTransaction(Connection connection, String sessionId, Double temperature)
    throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UPDATE_SESSION_CONFIGURATION_TEMPERATURE)) {
      BigDecimal bd = temperature != null ? BigDecimal.valueOf(temperature) : null;
      statement.setBigDecimal(1, bd);
      statement.setString(2, sessionId);
      requireUpdated(statement, "Session configuration does not exist: " + sessionId);
    }
  }

  private void updateMaxTokensInTransaction(Connection connection, String sessionId, Integer maxTokens)
    throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UPDATE_SESSION_CONFIGURATION_MAX_TOKENS)) {
      if (maxTokens == null) {
        statement.setNull(1, java.sql.Types.INTEGER);
      } else {
        statement.setInt(1, maxTokens);
      }
      statement.setString(2, sessionId);
      requireUpdated(statement, "Session configuration does not exist: " + sessionId);
    }
  }

  private void updatePrompt(Connection connection, String sessionId, String name, String content) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SessionPromptQueries.UPDATE_PROMPT)) {
      statement.setString(1, database.encryption().encrypt(sessionId, "session_prompt_override.override_content", content));
      statement.setString(2, sessionId);
      statement.setString(3, name);
      requireUpdated(statement, "Session prompt does not exist: " + name);
    }
  }

  private void requireUpdated(PreparedStatement statement, String message) throws SQLException {
    if (statement.executeUpdate() != 1) {
      throw new SQLException(message);
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
