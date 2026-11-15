package nl.llm.storyteller.api.http.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record SessionConfigPatchRequest(
  JsonNode systemPrompt,
  JsonNode fixedProtagonists,
  JsonNode rules,
  JsonNode temperature,
  JsonNode streamingEnabled,
  JsonNode validationEnabled,
  JsonNode graphEnabled,
  JsonNode maxTokens,
  JsonNode contextMode,
  JsonNode contextWindow
) { }
