package nl.llm.storyteller.db;

import java.util.Map;
import java.util.Objects;

/**
 * A partial update of a session's configuration.
 *
 * <p>Each {@code update*} flag says whether that setting was submitted at all. Inside a submitted setting a
 * null value is a meaningful choice rather than a missing field: it returns the session to the server
 * default. Keeping the groups together avoids a positional parameter list that grows with every setting,
 * but a group is only allowed where changing one column cannot change another: settings a caller can
 * submit on their own each get their own flag.
 */
public record ConfigurationPatch(
  Map<String, String> prompts,
  boolean updateTemperature,
  Double temperature,
  boolean updateGeneration,
  Boolean streamingEnabled,
  Boolean validationEnabled,
  boolean updateGraph,
  Boolean graphEnabled,
  boolean updateMaxTokens,
  Integer maxTokens,
  boolean updateContextMode,
  String contextMode,
  boolean updateContextWindow,
  Integer contextWindow
) {
  public ConfigurationPatch {
    Objects.requireNonNull(prompts, "prompts");
  }

  /** Prompt and temperature changes only, the shape used before generation and context settings existed. */
  public ConfigurationPatch(Map<String, String> prompts, boolean updateTemperature, Double temperature) {
    this(prompts, updateTemperature, temperature, false, null, null, false, null, false, null,
      false, null, false, null);
  }

  /** A patch carrying both context settings, so mode and window can be chosen in one step. */
  public static ConfigurationPatch context(String contextMode, Integer contextWindow) {
    return new ConfigurationPatch(Map.of(), false, null, false, null, null, false, null, false, null,
      true, contextMode, true, contextWindow);
  }

  /** A patch that chooses the context mode and leaves the stored window untouched. */
  public static ConfigurationPatch contextMode(String contextMode) {
    return new ConfigurationPatch(Map.of(), false, null, false, null, null, false, null, false, null,
      true, contextMode, false, null);
  }

  /** A patch that chooses the context window and leaves the stored mode untouched. */
  public static ConfigurationPatch contextWindow(Integer contextWindow) {
    return new ConfigurationPatch(Map.of(), false, null, false, null, null, false, null, false, null,
      false, null, true, contextWindow);
  }
}
