package nl.llm.storyteller.db;

import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;

import java.util.Objects;

public record SessionSettings(
  SessionPrompts prompts,
  KnowledgeGraphDocument knowledgeGraph,
  Double temperature,
  Boolean streamingEnabled,
  Boolean validationEnabled,
  Boolean graphEnabled,
  Integer maxTokens
) {
  public SessionSettings {
    if (Boolean.TRUE.equals(streamingEnabled) && Boolean.TRUE.equals(validationEnabled)) {
      throw new IllegalArgumentException("Streaming and validation cannot both be enabled.");
    }
    Objects.requireNonNull(prompts, "prompts");
    Objects.requireNonNull(knowledgeGraph, "knowledgeGraph");
  }

  public boolean streamingEnabled(boolean defaultValue) {
    return streamingEnabled != null ? streamingEnabled : defaultValue;
  }

  public boolean validationEnabled(boolean defaultValue) {
    return validationEnabled != null ? validationEnabled : defaultValue;
  }

  public boolean graphEnabled(boolean defaultValue) {
    return graphEnabled != null ? graphEnabled : defaultValue;
  }

  public SessionSettings(SessionPrompts prompts, KnowledgeGraphDocument knowledgeGraph, Double temperature) {
    this(prompts, knowledgeGraph, temperature, null, null, null, null);
  }

  public SessionSettings(
    SessionPrompts prompts,
    KnowledgeGraphDocument knowledgeGraph,
    Double temperature,
    Boolean streamingEnabled,
    Boolean validationEnabled,
    Boolean graphEnabled
  ) {
    this(prompts, knowledgeGraph, temperature, streamingEnabled, validationEnabled, graphEnabled, null);
  }

  public SessionSettings(SessionPrompts prompts, KnowledgeGraphDocument knowledgeGraph) {
    this(prompts, knowledgeGraph, null);
  }
}
