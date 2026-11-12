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
  Boolean graphEnabled,
  boolean effectiveStreamingEnabled,
  boolean effectiveValidationEnabled,
  boolean effectiveGraphEnabled,
  Integer maxTokens,
  Integer effectiveMaxTokens,
  String contextMode,
  String effectiveContextMode,
  Integer contextWindow,
  Integer effectiveContextWindow,
  String appVersion
) { }
