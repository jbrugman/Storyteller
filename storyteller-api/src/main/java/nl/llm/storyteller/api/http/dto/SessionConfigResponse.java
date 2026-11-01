package nl.llm.storyteller.api.http.dto;

public record SessionConfigResponse(
  String sessionId,
  String systemPrompt,
  String fixedProtagonists,
  String rules,
  Double temperature,
  Double effectiveTemperature,
  Boolean streamingEnabled,
  Boolean validationEnabled,
  boolean effectiveStreamingEnabled,
  boolean effectiveValidationEnabled
) { }
