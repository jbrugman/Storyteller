package nl.llm.storyteller.api.http.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record SessionStateResponse(
  String sessionId,
  String summary,
  String recentSummary,
  String canonicalState,
  int summaryCursor,
  int recentSummaryCursor,
  int canonicalStateCursor,
  JsonNode knowledgeGraph,
  SessionContextResponse context
) { }
