package nl.llm.storyteller.core.service;

import nl.llm.storyteller.core.model.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollingContextWindowTest {
  private static final int FRAME_TOKENS = 100;

  @Test
  @DisplayName("""
      Given rolling mode without a known context window,
      When the context is planned,
      Then all history stays available and the user is told the window has to be configured
      """)
  void shouldWarnWhenRollingModeHasNoKnownWindow() {
    RollingContextWindow window = new RollingContextWindow(
      new RollingContextWindow.Policy("rolling", 0, 1024, 0, 2));
    List<Message> history = List.of(new Message("user", "prompt"), new Message("assistant", "reply"));

    assertFalse(window.enabled());
    RollingContextWindow.Report report = window.plan(history, FRAME_TOKENS);
    assertEquals(0, report.firstVerbatimIndex());
    assertEquals(history.size(), report.verbatimMessages());
    assertTrue(report.warning().contains("needs a context window"));
  }

  @Test
  @DisplayName("""
      Given turns mode,
      When the context is planned,
      Then nothing is dropped and no warning is produced regardless of the window
      """)
  void shouldNotTrimOrWarnInTurnsMode() {
    RollingContextWindow window = new RollingContextWindow(
      new RollingContextWindow.Policy("turns", 120, 1024, 0, 2));
    List<Message> history = List.of(new Message("user", "prompt"), new Message("assistant", "reply"));

    RollingContextWindow.Report report = window.plan(history, FRAME_TOKENS);

    assertEquals(0, report.firstVerbatimIndex());
    assertEquals(history.size(), report.verbatimMessages());
    assertEquals("", report.warning());
  }

  @Test
  @DisplayName("""
      Given a window that leaves exactly enough room for the newest turn,
      When the context is planned,
      Then that turn is kept whole and the older turn is dropped
      """)
  void shouldKeepNewestTurnAtTheExactBoundary() {
    List<Message> history = List.of(
      new Message("user", "first prompt"),
      new Message("assistant", "first reply"),
      new Message("user", "second prompt"),
      new Message("assistant", "second reply"));
    int newestTurnTokens = RollingContextWindow.estimateMessages(history.subList(2, history.size()));
    RollingContextWindow window = new RollingContextWindow(
      new RollingContextWindow.Policy("rolling", FRAME_TOKENS + newestTurnTokens, 0, 0, 2));

    RollingContextWindow.Report report = window.plan(history, FRAME_TOKENS);

    assertEquals(2, report.firstVerbatimIndex());
    assertEquals(2, report.verbatimMessages());
    assertEquals(newestTurnTokens, report.verbatimTokens());
    assertEquals("", report.warning());
    assertEquals(2, report.droppedMessages());
    assertEquals(history.subList(2, history.size()), window.verbatimMessages(history, report));
  }

  @Test
  @DisplayName("""
      Given a window one token smaller than the newest turn,
      When the context is planned,
      Then the newest turn is dropped as well instead of being truncated
      """)
  void shouldDropTheWholeTurnOneTokenBelowTheBoundary() {
    List<Message> history = List.of(
      new Message("user", "first prompt"),
      new Message("assistant", "first reply"),
      new Message("user", "second prompt"),
      new Message("assistant", "second reply"));
    int newestTurnTokens = RollingContextWindow.estimateMessages(history.subList(2, history.size()));
    RollingContextWindow window = new RollingContextWindow(
      new RollingContextWindow.Policy("rolling", FRAME_TOKENS + newestTurnTokens - 1, 0, 0, 2));

    RollingContextWindow.Report report = window.plan(history, FRAME_TOKENS);

    assertEquals(history.size(), report.firstVerbatimIndex());
    assertEquals(0, report.verbatimMessages());
    assertEquals(List.of(), window.verbatimMessages(history, report));
    assertTrue(report.warning().contains("does not hold a single story turn"));
  }

  @Test
  @DisplayName("""
      Given a window that fits the newest reply but not the prompt before it,
      When the context is planned,
      Then the orphaned reply is dropped so a reply never separates from its prompt
      """)
  void shouldDropAnOrphanedReplyWhenItsPromptNoLongerFits() {
    List<Message> history = List.of(
      new Message("user", "a".repeat(600)),
      new Message("assistant", "short reply"),
      new Message("user", "another prompt"),
      new Message("assistant", "another reply"));
    int newestReplyOnly = RollingContextWindow.estimateTokens("another reply");
    RollingContextWindow window = new RollingContextWindow(
      new RollingContextWindow.Policy("rolling", FRAME_TOKENS + newestReplyOnly, 0, 0, 2));

    RollingContextWindow.Report report = window.plan(history, FRAME_TOKENS);

    assertEquals(history.size(), report.firstVerbatimIndex());
    assertEquals(0, report.verbatimMessages());
  }

  @Test
  @DisplayName("""
      Given a frame plus reserve that already exceeds the window,
      When the context is planned,
      Then the required frame is reported intact and the user is told to compact or widen
      """)
  void shouldWarnWhenTheMandatoryFrameOverflowsTheWindow() {
    RollingContextWindow window = new RollingContextWindow(
      new RollingContextWindow.Policy("rolling", 500, 1024, 0, 2));
    List<Message> history = List.of(new Message("user", "prompt"), new Message("assistant", "reply"));

    RollingContextWindow.Report report = window.plan(history, FRAME_TOKENS);

    assertEquals(FRAME_TOKENS + 1024, report.requiredTokens());
    assertEquals(0, report.historyTokens());
    assertEquals(history.size(), report.firstVerbatimIndex());
    assertTrue(report.warning().contains("no story turns fit"));
  }

  @Test
  @DisplayName("""
      Given a large window,
      When the context is planned,
      Then the whole story is kept verbatim without a warning
      """)
  void shouldKeepEverythingWhenTheWindowIsLargeEnough() {
    List<Message> history = List.of(
      new Message("user", "first prompt"),
      new Message("assistant", "first reply"),
      new Message("user", "second prompt"),
      new Message("assistant", "second reply"));
    int historyTokens = RollingContextWindow.estimateMessages(history);
    RollingContextWindow window = new RollingContextWindow(
      new RollingContextWindow.Policy("rolling", FRAME_TOKENS + historyTokens + 1, 0, 0, 2));

    RollingContextWindow.Report report = window.plan(history, FRAME_TOKENS);

    assertEquals(0, report.firstVerbatimIndex());
    assertEquals(history.size(), report.verbatimMessages());
    assertEquals(historyTokens, report.verbatimTokens());
    assertEquals("", report.warning());
  }

  @Test
  @DisplayName("""
      Given empty history,
      When the context is planned,
      Then there is nothing to drop and an oversized frame still warns
      """)
  void shouldPlanEmptyHistoryWithoutDropping() {
    RollingContextWindow window = new RollingContextWindow(
      new RollingContextWindow.Policy("rolling", 8192, 1024, 0, 2));

    RollingContextWindow.Report report = window.plan(List.of(), FRAME_TOKENS);

    assertEquals(0, report.verbatimMessages());
    assertEquals(0, report.droppedMessages());
    assertEquals("", report.warning());
    assertEquals(List.of(), window.verbatimMessages(List.of(), report));
  }

  @Test
  @DisplayName("""
      Given a session without overrides,
      When the server policy is applied to the session,
      Then every server value is inherited unchanged
      """)
  void shouldInheritServerPolicyWhenSessionHasNoOverrides() {
    RollingContextWindow.Policy server = new RollingContextWindow.Policy("rolling", 8192, 1024, 512, 2);

    RollingContextWindow.Policy session = server.withSession(null, null, null);

    assertEquals("rolling", session.mode());
    assertEquals(8192, session.windowTokens());
    assertEquals(1024, session.responseReserveTokens());
    assertEquals(512, session.responseCapTokens());
    assertEquals(2, session.fallbackTurns());
  }

  @Test
  @DisplayName("""
      Given a session that switches mode and window,
      When the server policy is applied to the session,
      Then only the overridden fields change
      """)
  void shouldOverrideOnlyTheFieldsTheSessionSets() {
    RollingContextWindow.Policy server = new RollingContextWindow.Policy("rolling", 8192, 1024, 512, 2);

    RollingContextWindow.Policy session = server.withSession("turns", 4096, null);

    assertEquals("turns", session.mode());
    assertEquals(4096, session.windowTokens());
    assertEquals(1024, session.responseReserveTokens());
    assertEquals(512, session.responseCapTokens());
  }

  @Test
  @DisplayName("""
      Given a response reserve and a response cap,
      When the reserved response space is resolved,
      Then the larger of the two wins and an uncapped session keeps the reserve
      """)
  void shouldReserveTheLargerOfReserveAndResponseCap() {
    RollingContextWindow.Policy policy = new RollingContextWindow.Policy("rolling", 8192, 1024, 0, 2);

    assertEquals(1024, policy.reserveTokens());
    assertEquals(4096, policy.withSession(null, null, 4096).reserveTokens());
    assertEquals(1024, policy.withSession(null, null, 0).reserveTokens());
  }

  @Test
  @DisplayName("""
    Given history where one turn carries an attached image,
    When the context is planned,
    Then the image is charged so a picture never silently consumes the window
    """)
  void shouldChargeAttachedImagesAgainstTheBudget() {
    List<Message> withImage = List.of(
      new Message("user", "look at this", "data:image/png;base64,AAAA"),
      new Message("assistant", "a bright red door"));
    List<Message> withoutImage = List.of(
      new Message("user", "look at this"),
      new Message("assistant", "a bright red door"));

    int imageTokens = RollingContextWindow.estimateMessages(withImage)
      - RollingContextWindow.estimateMessages(withoutImage);

    assertTrue(imageTokens > 0, "An attached image costs context");
  }

  @Test
  @DisplayName("""
    Given a history budget in tokens,
    When the maximum message count is resolved,
    Then it stays high enough that the planner still sees every message that could fit
    """)
  void shouldBoundTheMessagesThatCanFillABudget() {
    assertEquals(0, RollingContextWindow.maximumMessagesWithinBudget(0));
    assertEquals(0, RollingContextWindow.maximumMessagesWithinBudget(3));
    assertEquals(1, RollingContextWindow.maximumMessagesWithinBudget(4));

    int budget = 100;
    int smallestMessageTokens = RollingContextWindow.estimateTokens("a");
    int messagesThatFit = budget / smallestMessageTokens;

    assertTrue(RollingContextWindow.maximumMessagesWithinBudget(budget) >= messagesThatFit,
      "The bound must not truncate history the budget could still hold");
  }

  @Test
  @DisplayName("""
      Given a message that holds no text,
      When its cost is estimated,
      Then it still pays the per-message overhead the message bound assumes
      """)
  void shouldChargeTheOverheadOfAnEmptyMessage() {
    int emptyMessageTokens = RollingContextWindow.estimateMessage(new Message("user", ""));
    int missingTextTokens = RollingContextWindow.estimateMessage(new Message("user", null));

    assertEquals(emptyMessageTokens, missingTextTokens, "Missing text costs no more than empty text");
    assertEquals(emptyMessageTokens, RollingContextWindow.estimateTokens(""),
      "A message holding no text still costs its overhead, because estimateTokens is a message cost");
    assertEquals(emptyMessageTokens + 1, RollingContextWindow.estimateTokens("a"),
      "A single character is one estimated token on top of the message overhead");
    assertTrue(RollingContextWindow.maximumMessagesWithinBudget(emptyMessageTokens) >= 1,
      "The bound on how many messages fit a budget assumes this minimum cost, so a message must never be free");
  }

  @Test
  @DisplayName("""
      Given a newest turn whose prompt holds an image instead of text,
      When the context is planned against a small history budget,
      Then that turn is dropped because a picture spends context of its own
      """)
  void shouldChargeAnImageWhenChoosingTheTurnsToKeep() {
    List<Message> history = List.of(
      new Message("user", "first prompt"),
      new Message("assistant", "first reply"),
      new Message("user", "", "data:image/png;base64,AAAA"),
      new Message("assistant", ""));
    RollingContextWindow window = new RollingContextWindow(
      new RollingContextWindow.Policy("rolling", FRAME_TOKENS + 100, 0, 0, 2));

    RollingContextWindow.Report report = window.plan(history, FRAME_TOKENS);

    assertEquals(history.size(), report.firstVerbatimIndex(),
      "An image-only turn must not look cheap enough to survive a budget it cannot pay for");
    assertEquals(0, report.verbatimMessages());
  }

  @Test
  @DisplayName("""
      Given a window that holds the newest turn behind a text-only prompt,
      When the same turn is planned behind a prompt carrying an image,
      Then the picture costs a whole turn instead of silently overflowing the window
      """)
  void shouldGiveUpATurnWhenThePromptCarriesAnImage() {
    List<Message> history = List.of(
      new Message("user", "a".repeat(3000)),
      new Message("assistant", "b".repeat(3000)),
      new Message("user", "c".repeat(3000)),
      new Message("assistant", "d".repeat(3000)));
    int newestTurnTokens = RollingContextWindow.estimateMessages(history.subList(2, history.size()));
    int imageCharge = RollingContextWindow.estimateMessage(new Message("user", "c".repeat(3000), "data:image/png;base64,AAAA"))
      - RollingContextWindow.estimateMessage(new Message("user", "c".repeat(3000)));
    RollingContextWindow window = new RollingContextWindow(
      new RollingContextWindow.Policy("rolling", FRAME_TOKENS + newestTurnTokens, 0, 0, 2));

    assertEquals(2, window.plan(history, FRAME_TOKENS).firstVerbatimIndex(),
      "Behind a text-only prompt the newest turn fits exactly");

    RollingContextWindow.Report withImage = window.plan(history, FRAME_TOKENS + imageCharge);

    assertEquals(history.size(), withImage.firstVerbatimIndex(),
      "The picture is paid for out of the story rather than added on top of a full window");
    assertEquals(0, withImage.verbatimMessages());
    assertTrue(withImage.warning().contains("does not hold a single story turn"),
      "The user is told the window ran short: " + withImage.warning());
  }
}
