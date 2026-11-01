package nl.llm.storyteller.db;

import java.util.Map;

public interface SessionSettingsRepository {
  SessionSettings load(String sessionId);

  void save(String sessionId, SessionSettings settings);

  void patchConfiguration(String sessionId, Map<String, String> prompts, boolean updateTemperature, Double temperature);

  default void patchConfiguration(String sessionId, Map<String, String> prompts, boolean updateTemperature,
    Double temperature, boolean updateGeneration, Boolean streamingEnabled, Boolean validationEnabled) {
    if (updateGeneration) {
      throw new UnsupportedOperationException("Generation settings are not supported by this repository.");
    }
    patchConfiguration(sessionId, prompts, updateTemperature, temperature);
  }

  void setTemperature(String sessionId, Double temperature);
}
