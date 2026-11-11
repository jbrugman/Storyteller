package nl.llm.storyteller.api.http.dto;

import nl.llm.storyteller.core.service.RollingContextWindow;

/**
 * How this session's context is assembled, measured without a new user prompt.
 *
 * <p>{@code summarizedMessages} counts story messages that reach the model only through derived memory.
 * In rolling mode that is the designed outcome rather than a problem, so only {@code warning} carries
 * something the user has to act on.
 */
public record SessionContextResponse(
  String mode,
  int contextWindow,
  int requiredTokens,
  int historyTokens,
  int verbatimMessages,
  int totalMessages,
  int summarizedMessages,
  String warning
) {
  public static SessionContextResponse from(RollingContextWindow.Report report) {
    return new SessionContextResponse(report.mode(), report.windowTokens(), report.requiredTokens(),
      report.historyTokens(), report.verbatimMessages(), report.totalMessages(),
      report.summarizedMessages(), report.warning());
  }
}
