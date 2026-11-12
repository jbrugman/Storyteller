package nl.llm.storyteller.api.web;

import nl.llm.storyteller.api.session.InvalidContextSettingException;
import nl.llm.storyteller.api.session.InvalidFixedProtagonistsException;
import nl.llm.storyteller.api.session.InvalidKnowledgeGraphException;
import nl.llm.storyteller.db.SessionPrompts;

public record StorySettingsPage(
  SessionPrompts prompts,
  String knowledgeGraph,
  Double temperature,
  String yamlError,
  int yamlErrorLine,
  int yamlErrorColumn,
  String knowledgeGraphError,
  String contextError,
  String notificationTitle,
  String notificationMessage
) {
  static StorySettingsPage valid(
    SessionPrompts prompts,
    String knowledgeGraph,
    Double temperature,
    String notificationTitle,
    String notificationMessage
  ) {
    return new StorySettingsPage(
      prompts, knowledgeGraph, temperature, "", 0, 0, "", "", notificationTitle, notificationMessage
    );
  }

  static StorySettingsPage invalidYaml(
    SessionPrompts prompts,
    String knowledgeGraph,
    Double temperature,
    InvalidFixedProtagonistsException error
  ) {
    return new StorySettingsPage(prompts, knowledgeGraph, temperature, error.getMessage(), error.line(),
      error.column(), "", "", "", "");
  }

  static StorySettingsPage invalidKnowledgeGraph(
    SessionPrompts prompts,
    String knowledgeGraph,
    Double temperature,
    InvalidKnowledgeGraphException error
  ) {
    return new StorySettingsPage(prompts, knowledgeGraph, temperature, "", 0, 0, error.getMessage(), "", "", "");
  }

  static StorySettingsPage invalidContext(
    SessionPrompts prompts,
    String knowledgeGraph,
    Double temperature,
    InvalidContextSettingException error
  ) {
    return new StorySettingsPage(prompts, knowledgeGraph, temperature, "", 0, 0, "", error.getMessage(), "", "");
  }

  public boolean hasYamlError() {
    return !yamlError.isEmpty();
  }

  public boolean hasKnowledgeGraphError() {
    return !knowledgeGraphError.isEmpty();
  }

  public boolean hasContextError() {
    return !contextError.isEmpty();
  }

  public boolean hasNotification() {
    return !notificationMessage.isBlank();
  }
}
