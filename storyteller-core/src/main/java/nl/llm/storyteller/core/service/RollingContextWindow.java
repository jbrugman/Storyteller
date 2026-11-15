package nl.llm.storyteller.core.service;

import nl.llm.storyteller.core.config.AppConfig;
import nl.llm.storyteller.core.model.Message;

import java.util.List;

/**
 * Decides how much persisted story history a context can send verbatim.
 *
 * <p>A context is assembled from the space left in the model's context window rather than from a
 * message count. Callers measure the frame they always send (the system message holding prompts,
 * derived memory and knowledge-graph facts, plus the current prompt) and this class fits whole
 * complete turns of history into whatever the window leaves free. The frame never rolls off; only
 * history is dropped.
 *
 * <p>Token counts are estimated at three characters per token plus a fixed per-message overhead. The
 * estimate is deliberately pessimistic and only decides how much history to keep and when to warn.
 * The model server stays the authority on the real context window, so a tight setting warns the user
 * instead of failing a turn silently.
 */
public final class RollingContextWindow {
  private static final int CHARS_PER_TOKEN = 3;
  private static final int MESSAGE_OVERHEAD_TOKENS = 4;
  /**
   * Flat cost charged to a message carrying an image. Attached pictures are not text, so the character
   * estimate cannot see them, while the model spends real context on them. One conservative flat cost
   * per image keeps the estimate on the safe side without pretending to know the encoder's counting.
   */
  private static final int IMAGE_OVERHEAD_TOKENS = 1024;
  private static final String USER_ROLE = "user";

  /**
   * The context policy in force. Session settings override the server settings field by field, and a
   * null override keeps the inherited value.
   */
  public record Policy(
    String mode,
    int windowTokens,
    int responseReserveTokens,
    int responseCapTokens,
    int fallbackTurns
  ) {
    public static Policy from(AppConfig config) {
      return new Policy(config.contextMode(), config.contextWindow(), config.responseReserveTokens(),
        config.chatMaxTokens(), config.maxRecentTurns());
    }

    public Policy withSession(String sessionMode, Integer sessionWindowTokens, Integer sessionResponseCapTokens) {
      return new Policy(
        sessionMode == null || sessionMode.isBlank() ? mode : sessionMode,
        sessionWindowTokens == null ? windowTokens : sessionWindowTokens,
        responseReserveTokens,
        sessionResponseCapTokens == null ? responseCapTokens : Math.max(0, sessionResponseCapTokens),
        fallbackTurns
      );
    }

    public boolean rolling() {
      return AppConfig.CONTEXT_MODE_ROLLING.equalsIgnoreCase(mode);
    }

    /** Space kept free for the model's reply: the configured reserve, or a larger response cap. */
    public int reserveTokens() {
      return Math.max(responseReserveTokens, Math.max(0, responseCapTokens));
    }

    /** Space left for verbatim history once the always-sent frame and the response reserve are paid for. */
    public int historyBudgetTokens(int frameTokens) {
      return Math.max(0, windowTokens - Math.max(0, frameTokens) - reserveTokens());
    }
  }

  /**
   * Outcome of fitting history into the window. {@code firstVerbatimIndex} is the index of the first
   * persisted message sent verbatim; everything before it reaches the model only through derived
   * memory, which is the normal outcome of rolling mode rather than a problem to report.
   * {@code totalMessages} is the whole story, so it can exceed the messages the planner was given.
   */
  public record Report(
    String mode,
    int windowTokens,
    int requiredTokens,
    int historyTokens,
    int verbatimTokens,
    int verbatimMessages,
    int totalMessages,
    int firstVerbatimIndex,
    String warning
  ) {
    public int droppedMessages() {
      return firstVerbatimIndex;
    }

    /** Story messages the model sees only through derived memory, because the window did not hold them. */
    public int summarizedMessages() {
      return Math.max(0, totalMessages - verbatimMessages);
    }
  }

  private final Policy policy;

  public RollingContextWindow(Policy policy) {
    this.policy = policy;
  }

  public RollingContextWindow(AppConfig config) {
    this(Policy.from(config));
  }

  public boolean enabled() {
    return policy.rolling() && policy.windowTokens() > 0;
  }

  /**
   * Whether the mid-term (recent summary) layer has work under this context policy.
   *
   * <p>A rolling window decides how much story goes out verbatim, so the layer always runs once the
   * window is known: the request frontier it records, not a turn count, tells it what to condense.
   * Rolling with an unknown window cannot plan a window and falls back to sending turn counts, so it
   * follows the turns-mode rule. Turns mode keeps that rule: the layer only exists while its window
   * reaches beyond the turns sent verbatim, otherwise every recent turn still goes out raw.
   */
  public boolean midTermLayerEnabled(int recentSummaryWindowTurns, int verbatimTurns) {
    return enabled() || recentSummaryWindowTurns > verbatimTurns;
  }

  public Policy policy() {
    return policy;
  }

  public Report plan(List<Message> history, int frameTokens) {
    List<Message> messages = history == null ? List.of() : history;
    return plan(messages, frameTokens, messages.size());
  }

  /**
   * Plan a context from the newest messages of a longer story.
   *
   * <p>Rolling mode reads no more history than the window could hold, so the slice handed over can be
   * shorter than the story behind it. {@code totalMessages} says how many messages the story actually
   * has, which lets a report state how much of the story still goes out verbatim rather than describing
   * the slice it happened to receive. A smaller slice can only ever mean more of the story lives in
   * derived memory, never a message that was counted twice.
   */
  public Report plan(List<Message> history, int frameTokens, int totalMessages) {
    List<Message> messages = history == null ? List.of() : history;
    int total = Math.max(totalMessages, messages.size());
    int required = Math.max(0, frameTokens) + policy.reserveTokens();
    int window = policy.windowTokens();

    if (!policy.rolling()) {
      return new Report(AppConfig.CONTEXT_MODE_TURNS, window, required, 0,
        estimateMessages(messages), messages.size(), total, 0, "");
    }
    if (window <= 0) {
      return new Report(AppConfig.CONTEXT_MODE_ROLLING, window, required, 0,
        estimateMessages(messages), messages.size(), total, 0,
        "Rolling context needs a context window. Set the session's context window or chat.contextWindow to"
          + " the model's size in tokens; until then Storyteller sends " + policy.fallbackTurns()
          + " recent turns like turns mode does."
      );
    }

    int budget = Math.max(0, window - required);
    int start = completeTurnStart(messages, budget);
    int verbatimTokens = estimateMessages(messages.subList(start, messages.size()));
    return new Report(AppConfig.CONTEXT_MODE_ROLLING, window, required, budget, verbatimTokens,
      messages.size() - start, total, start,
      warning(required, window, budget, total, messages.size() - start));
  }

  /** The verbatim history behind a report, so callers never recompute the window themselves. */
  public List<Message> verbatimMessages(List<Message> history, Report report) {
    List<Message> messages = history == null ? List.of() : history;
    if (messages.isEmpty()) {
      return List.of();
    }
    return List.copyOf(messages.subList(Math.clamp(report.firstVerbatimIndex(), 0, messages.size()), messages.size()));
  }

  /** What a message holding this text costs. An empty message still costs its per-message overhead. */
  public static int estimateTokens(String text) {
    return MESSAGE_OVERHEAD_TOKENS + textTokens(text);
  }

  /**
   * What one message costs: its per-message overhead, its text and a flat charge when it carries an
   * image. This is the only cost a planner may compare against a budget, so a message cannot become
   * free by holding no text, which is exactly what a turn carrying a picture looks like to a
   * character estimate.
   */
  public static int estimateMessage(Message message) {
    return estimateTokens(message.content()) + (carriesImage(message) ? IMAGE_OVERHEAD_TOKENS : 0);
  }

  /** Text alone, without the overhead a message costs on top of it. */
  private static int textTokens(String text) {
    return text == null || text.isEmpty() ? 0 : (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
  }

  private static boolean carriesImage(Message message) {
    return message.imageDataUrl() != null && !message.imageDataUrl().isBlank();
  }

  public static int estimateMessages(List<Message> messages) {
    int tokens = 0;
    for (Message message : messages) {
      tokens += estimateMessage(message);
    }
    return tokens;
  }

  /**
   * Upper bound on the number of persisted messages that can fit a budget. Every message costs at least
   * the per-message overhead, so a caller never has to read more than this to fill the budget.
   */
  public static int maximumMessagesWithinBudget(int budgetTokens) {
    return Math.max(0, budgetTokens / MESSAGE_OVERHEAD_TOKENS);
  }

  /**
   * Start index of the newest complete turns fitting {@code budget} tokens. Turns stay whole: the
   * returned index always points at a user prompt, so trimming never separates a reply from its
   * prompt. Zero means not even the newest turn fits.
   */
  private static int completeTurnStart(List<Message> messages, int budget) {
    int start = messages.size();
    int used = 0;
    for (int index = messages.size() - 1; index >= 0; index--) {
      int cost = estimateMessage(messages.get(index));
      if (used + cost > budget) {
        break;
      }
      used += cost;
      start = index;
    }
    while (start < messages.size() && !USER_ROLE.equalsIgnoreCase(messages.get(start).role())) {
      start++;
    }
    return start;
  }

  /**
   * Warnings stay limited to what the user can act on. Dropping older turns is what rolling mode is
   * for, so a warning only appears when the always-sent frame no longer fits, or when not even the
   * newest turn fits and the model would answer without any story context at all.
   */
  private static String warning(int required, int window, int budget, int totalMessages, int verbatimMessages) {
    if (budget <= 0) {
      return "Required context needs " + required + " tokens but the context window is " + window
        + " tokens, so no story turns fit. Compact the canonical state or history on the memory page,"
        + " or raise the context window.";
    }
    if (totalMessages > 0 && verbatimMessages == 0) {
      return "The context window leaves only " + budget + " tokens for recent turns after " + required
        + " tokens of prompts and memory, which does not hold a single story turn. Compact the canonical"
        + " state or history on the memory page, or raise the context window.";
    }
    return "";
  }
}
