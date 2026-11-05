package nl.llm.storyteller.db;

import java.util.Map;

public interface SessionSettingsRepository {
  SessionSettings load(String sessionId);

  void save(String sessionId, SessionSettings settings);

  void patchConfiguration(String sessionId, Map<String, String> prompts, boolean updateTemperature, Double temperature);

  default void patchConfiguration(String sessionId, Map<String, String> prompts, boolean updateTemperature,
    Double temperature, boolean updateGeneration, Boolean streamingEnabled, Boolean validationEnabled) {
    patchConfiguration(sessionId, prompts, updateTemperature, temperature, updateGeneration, streamingEnabled,
      validationEnabled, false, null);
  }

  default void patchConfiguration(String sessionId, Map<String, String> prompts, boolean updateTemperature,
    Double temperature, boolean updateGeneration, Boolean streamingEnabled, Boolean validationEnabled,
    boolean updateGraph, Boolean graphEnabled) {
    if (updateGeneration || updateGraph) {
      throw new UnsupportedOperationException("Generation and knowledge graph settings need dedicated"
        + " repository support.");
    }
    patchConfiguration(sessionId, prompts, updateTemperature, temperature);
  }

  void setTemperature(String sessionId, Double temperature);
}
