package nl.llm.storyteller.core;

import nl.llm.storyteller.core.model.HistoryState;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.service.HistoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryStoreTest {
    @Test
    @DisplayName("""
        Given a transient image message,
        When the message is persisted to history,
        Then only its text should be stored
        """)
    void shouldNeverPersistTransientImageData() throws Exception {
        Path directory = Files.createTempDirectory("storyteller-image-history");
        Path historyFile = directory.resolve("history.json");
        HistoryStore historyStore = new HistoryStore(historyFile, directory.resolve("legacy-history.txt"));

        historyStore.save(new HistoryState(
            List.of(Message.withImage("user", "Describe the island.", "data:image/png;base64,SECRET")),
            0,
            0,
            0
        ));

        String persisted = Files.readString(historyFile);
        assertTrue(persisted.contains("Describe the island."));
        assertFalse(persisted.contains("data:image"));
        assertFalse(persisted.contains("SECRET"));
    }

    @ParameterizedTest
    @MethodSource("recentTurnCases")
    @DisplayName("""
        Given a history with three complete user-assistant turns,
        When the most recent complete turns are requested,
        Then only the requested trailing complete turns should be returned
        """)
    void shouldReturnOnlyTheRequestedTrailingCompleteTurns(int requestedTurns, List<String> expectedContents) throws Exception {
        HistoryStore historyStore = createStoreWithThreeTurns();

        List<String> actualContents = historyStore.recentMessages(requestedTurns).stream()
            .map(Message::content)
            .toList();

        assertIterableEquals(expectedContents, actualContents);
    }

    @ParameterizedTest
    @MethodSource("recentWindowCases")
    @DisplayName("""
        Given a history with several complete turns,
        When a recent-message window is requested with trailing turns excluded,
        Then only the older portion of that recent window should be returned
        """)
    void shouldReturnOnlyTheOlderPortionOfARecentWindow(
        int totalRecentTurns,
        int trailingTurnsToExclude,
        List<String> expectedContents
    ) throws Exception {
        HistoryStore historyStore = createStoreWithThreeTurns();

        List<String> actualContents = historyStore.recentMessagesWindow(totalRecentTurns, trailingTurnsToExclude).stream()
            .map(Message::content)
            .toList();

        assertIterableEquals(expectedContents, actualContents);
    }

    @Test
    @DisplayName("""
        Given a legacy history.md file with user and assistant entries,
        When the history store is loaded without a history.json file,
        Then the legacy conversation should be migrated into history.json
        """)
    void shouldMigrateLegacyHistoryIntoJsonOnFirstLoad() throws Exception {
        Path tempDirectory = Files.createTempDirectory("storyteller-history-legacy");
        Path historyFile = tempDirectory.resolve("history.json");
        Path legacyFile = tempDirectory.resolve("history.md");
        Files.writeString(
            legacyFile,
            """
            USER: First input
            continuation
            ASSISTANT: First answer
            USER: Second input
            ASSISTANT: Second answer
            """.strip()
        );

        HistoryStore historyStore = new HistoryStore(historyFile, legacyFile);

        HistoryState state = historyStore.load();

        assertEquals(4, state.messages().size());
        assertEquals("First input\ncontinuation", state.messages().get(0).content());
        assertEquals("First answer", state.messages().get(1).content());
        assertEquals("Second input", state.messages().get(2).content());
        assertEquals("Second answer", state.messages().get(3).content());
    }

    @Test
    @DisplayName("""
        Given a history.json path inside a missing memory directory,
        When a turn is appended,
        Then the history store should create the parent directory automatically
        """)
    void shouldCreateParentDirectoryWhenSavingHistory() throws Exception {
        Path tempDirectory = Files.createTempDirectory("storyteller-history-parent");
        Path historyFile = tempDirectory.resolve("memory/history.json");
        Path legacyFile = tempDirectory.resolve("history.md");

        HistoryStore historyStore = new HistoryStore(historyFile, legacyFile);

        historyStore.appendTurn("user-1", "assistant-1");

        assertTrue(Files.exists(historyFile));
    }

    @Test
    @DisplayName("""
        Given a history with three turns and cursors that already moved forward,
        When the last turn is removed,
        Then the last user input should be returned and all cursors should be clamped to the shortened history
        """)
    void shouldRemoveLastTurnAndClampCursors() throws Exception {
        Path tempDirectory = Files.createTempDirectory("storyteller-history-remove");
        Path historyFile = tempDirectory.resolve("history.json");
        Path legacyFile = tempDirectory.resolve("history.md");
        HistoryStore historyStore = new HistoryStore(historyFile, legacyFile);
        historyStore.save(new HistoryState(
            List.of(
                new Message("user", "user-1"),
                new Message("assistant", "assistant-1"),
                new Message("user", "user-2"),
                new Message("assistant", "assistant-2"),
                new Message("user", "user-3"),
                new Message("assistant", "assistant-3")
            ),
            6,
            6,
            6
        ));

        String restoredInput = historyStore.removeLastTurn();
        HistoryState state = historyStore.load();

        assertEquals("user-3", restoredInput);
        assertIterableEquals(
            List.of("user-1", "assistant-1", "user-2", "assistant-2"),
            state.messages().stream().map(Message::content).toList()
        );
        assertEquals(4, state.summaryCursor());
        assertEquals(4, state.recentSummaryCursor());
        assertEquals(4, state.canonicalStateCursor());
    }

    @Test
    @DisplayName("""
        Given a history with several complete turns,
        When the most recent persisted turn is requested,
        Then the latest user input and assistant reply should be returned as a pair
        """)
    void shouldLoadLastTurn() throws Exception {
        HistoryStore historyStore = createStoreWithThreeTurns();

        HistoryStore.LastTurn lastTurn = historyStore.loadLastTurn();

        assertEquals("user-3", lastTurn.userInput());
        assertEquals("assistant-3", lastTurn.assistantResponse());
    }

    @Test
    @DisplayName("""
        Given a stored history with a request frontier,
        When the memory layers write, a turn records a higher frontier, and a turn is undone,
        Then the frontier should survive memory writes, grow on append, and clamp back on undo
        """)
    void shouldGrowTheRequestFrontierOnAppendAndClampItOnUndo() throws Exception {
        Path tempDirectory = Files.createTempDirectory("storyteller-history-frontier");
        HistoryStore historyStore = new HistoryStore(
            tempDirectory.resolve("history.json"), tempDirectory.resolve("history.md"));
        historyStore.save(new HistoryState(
            List.of(
                new Message("user", "user-1"),
                new Message("assistant", "assistant-1"),
                new Message("user", "user-2"),
                new Message("assistant", "assistant-2"),
                new Message("user", "user-3"),
                new Message("assistant", "assistant-3")
            ),
            0,
            0,
            0,
            6
        ));
        assertEquals(6, historyStore.load().contextTargetIndex());

        historyStore.markSummarized(2);
        historyStore.markRecentSummarized(2);
        historyStore.markCanonicalStateUpdated(2);
        HistoryState afterMemory = historyStore.load();
        assertEquals(2, afterMemory.summaryCursor());
        assertEquals(2, afterMemory.recentSummaryCursor());
        assertEquals(2, afterMemory.canonicalStateCursor());
        assertEquals(6, afterMemory.contextTargetIndex());

        historyStore.appendTurn("user-4", "assistant-4", 8);
        HistoryState afterAppend = historyStore.load();
        assertEquals(8, afterAppend.messages().size());
        assertEquals(8, afterAppend.contextTargetIndex());

        historyStore.removeLastTurn();
        HistoryState afterUndo = historyStore.load();
        assertEquals(6, afterUndo.messages().size());
        assertEquals(6, afterUndo.contextTargetIndex());
        assertEquals(2, afterUndo.recentSummaryCursor());

        historyStore.appendTurn("user-5", "assistant-5");
        assertEquals(6, historyStore.load().contextTargetIndex());
    }

    @Test
    @DisplayName("""
        Given a history.json written before the request frontier existed,
        When the history store is loaded,
        Then the request frontier should read back as zero
        """)
    void shouldReadAnAbsentRequestFrontierAsZeroForOlderFiles() throws Exception {
        Path tempDirectory = Files.createTempDirectory("storyteller-history-legacy-frontier");
        Path historyFile = tempDirectory.resolve("history.json");
        Files.writeString(historyFile, """
            {
              "messages": [
                {"role": "user", "content": "user-1"},
                {"role": "assistant", "content": "assistant-1"}
              ],
              "summary_cursor": 1,
              "recent_summary_cursor": 1,
              "canonical_state_cursor": 1
            }
            """);
        HistoryStore historyStore = new HistoryStore(historyFile, tempDirectory.resolve("history.md"));
        assertEquals(0, historyStore.load().contextTargetIndex());
    }

    private static Stream<Arguments> recentTurnCases() {
        return Stream.of(
            Arguments.of(1, List.of("user-3", "assistant-3")),
            Arguments.of(2, List.of("user-2", "assistant-2", "user-3", "assistant-3")),
            Arguments.of(3, List.of("user-1", "assistant-1", "user-2", "assistant-2", "user-3", "assistant-3"))
        );
    }

    private static Stream<Arguments> recentWindowCases() {
        return Stream.of(
            Arguments.of(2, 1, List.of("user-2", "assistant-2")),
            Arguments.of(3, 1, List.of("user-1", "assistant-1", "user-2", "assistant-2")),
            Arguments.of(3, 2, List.of("user-1", "assistant-1"))
        );
    }

    private static HistoryStore createStoreWithThreeTurns() throws Exception {
        Path tempDirectory = Files.createTempDirectory("storyteller-history");
        Path historyFile = tempDirectory.resolve("history.json");
        Path legacyFile = tempDirectory.resolve("history.md");

        HistoryStore historyStore = new HistoryStore(historyFile, legacyFile);
        historyStore.appendTurn("user-1", "assistant-1");
        historyStore.appendTurn("user-2", "assistant-2");
        historyStore.appendTurn("user-3", "assistant-3");
        return historyStore;
    }
}
