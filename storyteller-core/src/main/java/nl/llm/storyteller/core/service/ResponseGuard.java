package nl.llm.storyteller.core.service;

import nl.llm.storyteller.core.model.ValidationOutcome;

import java.io.IOException;

public final class ResponseGuard {
  private final nl.llm.storyteller.core.config.AppConfig config;
  private final ValidationClient validationClient;
  private final ValidationDecisionParser validationDecisionParser;
  private final ResponseSanitizer responseSanitizer;

  public ResponseGuard(ChatClient client, nl.llm.storyteller.core.config.AppConfig config) {
    this(
      config,
      new ValidationClient(client, config),
      new ValidationDecisionParser(),
      new ResponseSanitizer()
    );
  }

  public ResponseGuard(
    nl.llm.storyteller.core.config.AppConfig config,
    ValidationClient validationClient,
    ValidationDecisionParser validationDecisionParser,
    ResponseSanitizer responseSanitizer
  ) {
    this.config = config;
    this.validationClient = validationClient;
    this.validationDecisionParser = validationDecisionParser;
    this.responseSanitizer = responseSanitizer;
  }

  public String validate(String validationSystemPrompt, String validationRequest, String assistantResponse)
    throws InterruptedException {
    return validate(validationSystemPrompt, validationRequest, assistantResponse, config.validationEnabled());
  }

  public String validate(String validationSystemPrompt, String validationRequest, String assistantResponse,
    boolean validationEnabled) throws InterruptedException {
    if (!validationEnabled) {
      return responseSanitizer.sanitize(assistantResponse);
    }

    if (assistantResponse == null || assistantResponse.isBlank()) {
      return config.validationFailClosedMessage();
    }

    final String validatedResponse;
    try {
      validatedResponse = validationClient.validate(validationSystemPrompt, validationRequest);
    } catch (IOException | RuntimeException _) {
      return config.validationFailClosedMessage();
    }

    if (validatedResponse == null || validatedResponse.isBlank()) {
      return config.validationFailClosedMessage();
    }

    return applyDecision(validatedResponse, assistantResponse);
  }

  private String applyDecision(String validationResult, String assistantResponse) {
    ValidationOutcome outcome = validationDecisionParser.parse(validationResult);
    if (outcome == null) {
      return config.validationFailClosedMessage();
    }

    if (outcome.isAllow()) {
      return responseSanitizer.sanitize(assistantResponse);
    }
    if (outcome.isReplace() && outcome.replacementText() != null && !outcome.replacementText().isBlank()) {
      return responseSanitizer.sanitize(outcome.replacementText());
    }
    return config.validationFailClosedMessage();
  }
}
