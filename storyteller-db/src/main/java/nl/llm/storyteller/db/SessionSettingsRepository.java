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
    patchConfiguration(sessionId, prompts, updateTemperature, temperature, updateGeneration, streamingEnabled,
      validationEnabled, updateGraph, graphEnabled, false, null);
  }

  default void patchConfiguration(String sessionId, Map<String, String> prompts, boolean updateTemperature,
    Double temperature, boolean updateGeneration, Boolean streamingEnabled, Boolean validationEnabled,
    boolean updateGraph, Boolean graphEnabled, boolean updateMaxTokens, Integer maxTokens) {
    patchConfiguration(sessionId, new ConfigurationPatch(prompts, updateTemperature, temperature, updateGeneration,
      streamingEnabled, validationEnabled, updateGraph, graphEnabled, updateMaxTokens, maxTokens,
      false, null, false, null));
  }

  /**
   * Apply a partial configuration update. Repositories that store more than prompts and temperature
   * override this; the default accepts only the groups it can persist, so a caller can never lose a
   * submitted setting silently.
   */
  default void patchConfiguration(String sessionId, ConfigurationPatch patch) {
    if (patch.updateGeneration() || patch.updateGraph() || patch.updateMaxTokens()
      || patch.updateContextMode() || patch.updateContextWindow()) {
      throw new UnsupportedOperationException("Generation, knowledge graph, response limit and context settings"
        + " need dedicated repository support.");
    }
    patchConfiguration(sessionId, patch.prompts(), patch.updateTemperature(), patch.temperature());
  }

  void setTemperature(String sessionId, Double temperature);
}
