package nl.llm.storyteller.core;

import nl.llm.storyteller.core.model.CanonicalStatePromptInput;
import nl.llm.storyteller.core.model.HistoryState;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.model.RecentSummaryPromptInput;
import nl.llm.storyteller.core.model.SummaryPromptInput;
import nl.llm.storyteller.core.service.CanonicalStateManager;
import nl.llm.storyteller.core.service.CanonicalStatePromptBuilder;
import nl.llm.storyteller.core.service.ChatClient;
import nl.llm.storyteller.core.service.HistoryStore;
import nl.llm.storyteller.core.service.PromptLoader;
import nl.llm.storyteller.core.service.PromptTemplateService;
import nl.llm.storyteller.core.service.RecentSummaryManager;
import nl.llm.storyteller.core.service.RecentSummaryPromptBuilder;
import nl.llm.storyteller.core.service.SummaryManager;
import nl.llm.storyteller.core.service.SummaryPromptBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DerivedMemoryManagerTest {
    @Test
    @DisplayName("""
        Given a canonical-state manager whose queue has been closed,
        When an update submission is rejected,
        Then a subsequent update should also attempt submission instead of remaining marked as running
        """)
    void shouldResetRunningAfterRejectedSubmission() throws Exception {
        TestContext context = createContext("canonicalState.batchMessages=1");
        context.historyStore().appendTurn("Turn one", "Reply one");
        CanonicalStateManager manager = createCanonicalStateManager(context, new NoOpChatClient());
        try {
            manager.shutdown();

            assertThrows(RejectedExecutionException.class, manager::startUpdateIfNeeded);
            assertThrows(RejectedExecutionException.class, manager::startUpdateIfNeeded);
            assertEquals(0, context.historyStore().load().canonicalStateCursor());
            assertEquals("", manager.loadCanonicalState());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    @DisplayName("""
        Given one completed story turn and a canonical-state batch size of one message,
        When the canonical-state refresh job is prepared,
        Then the complete new turn should be included immediately
        """)
    void shouldPrepareCanonicalStateRefreshAfterEveryCompletedTurn() throws Exception {
        TestContext context = createContext(
            """
                chat.maxRecentTurns=6
                canonicalState.batchMessages=1
                """
        );
        context.historyStore().save(
            new HistoryState(
                List.of(
                    new Message("user", "Turn one"),
                    new Message("assistant", "Reply one")
                ),
                0,
                0,
                0
            )
        );

        CanonicalStateManager canonicalStateManager = createCanonicalStateManager(context, new NoOpChatClient());
        try {
            Object job = prepareJob(canonicalStateManager);

            assertNotNull(job);
            assertEquals(
                List.of(
                    new Message("user", "Turn one"),
                    new Message("assistant", "Reply one")
                ),
                pendingMessages(job)
            );
        } finally {
            canonicalStateManager.shutdown();
        }
    }

    @Test
    @DisplayName("""
        Given a canonical-state refresh is running when another completed turn is persisted,
        When another canonical-state refresh is requested,
        Then the new turn should be processed immediately after the running refresh
        """)
    void shouldProcessCanonicalStateRefreshRequestedWhilePreviousRefreshIsRunning() throws Exception {
        TestContext context = createContext("canonicalState.batchMessages=1");
        context.historyStore().save(
            new HistoryState(
                List.of(
                    new Message("user", "Turn one"),
                    new Message("assistant", "Reply one")
                ),
                0,
                0,
                0
            )
        );
        SequencedChatClient client = new SequencedChatClient();
        CanonicalStateManager canonicalStateManager = createCanonicalStateManager(context, client);
        try {
            canonicalStateManager.startUpdateIfNeeded();
            assertTrue(client.firstStarted().await(5, TimeUnit.SECONDS));

            context.historyStore().appendTurn("Turn two", "Reply two");
            canonicalStateManager.startUpdateIfNeeded();
            client.allowFirstToFinish().countDown();

            assertTrue(client.secondFinished().await(5, TimeUnit.SECONDS));
            awaitWorkerIdle(canonicalStateManager);
            assertEquals(4, context.historyStore().load().canonicalStateCursor());
            assertEquals("Canonical state 2", canonicalStateManager.loadCanonicalState());
        } finally {
            canonicalStateManager.shutdown();
        }
    }

    @Test
    @DisplayName("""
        Given older story messages below the summary batch threshold,
        When the summary refresh job is prepared,
        Then no background summary update should be scheduled
        """)
    void shouldSkipSummaryRefreshUntilEnoughOlderMessagesExist() throws Exception {
        TestContext context = createContext(
            """
                recentSummary.maxRecentTurns=2
                summary.batchMessages=3
                """
        );
        context.historyStore().save(
            new HistoryState(
                List.of(
                    new Message("user", "Turn one"),
                    new Message("assistant", "Reply one"),
                    new Message("user", "Turn two"),
                    new Message("assistant", "Reply two"),
                    new Message("user", "Turn three"),
                    new Message("assistant", "Reply three")
                ),
                0,
                0,
                0
            )
        );

        SummaryManager summaryManager = createSummaryManager(context, new NoOpChatClient());
        try {
            assertNull(prepareJob(summaryManager));
        } finally {
            summaryManager.shutdown();
        }
    }

    @Test
    @DisplayName("""
        Given enough older turns outside the trailing raw-turn window,
        When the recent-summary refresh job is prepared,
        Then only the middle recent window should be sent to the summarizer
        """)
    void shouldPrepareRecentSummaryFromMiddleWindow() throws Exception {
        TestContext context = createContext(
            """
                chat.maxRecentTurns=2
                recentSummary.maxRecentTurns=4
                recentSummary.batchMessages=4
                """
        );
        context.historyStore().save(
            new HistoryState(
                List.of(
                    new Message("user", "Turn one"),
                    new Message("assistant", "Reply one"),
                    new Message("user", "Turn two"),
                    new Message("assistant", "Reply two"),
                    new Message("user", "Turn three"),
                    new Message("assistant", "Reply three"),
                    new Message("user", "Turn four"),
                    new Message("assistant", "Reply four")
                ),
                0,
                0,
                0
            )
        );

        RecentSummaryManager recentSummaryManager = createRecentSummaryManager(context, new NoOpChatClient());
        try {
            Object job = prepareJob(recentSummaryManager);

            assertNotNull(job);
            assertEquals(
                List.of(
                    new Message("user", "Turn one"),
                    new Message("assistant", "Reply one"),
                    new Message("user", "Turn two"),
                    new Message("assistant", "Reply two")
                ),
                pendingMessages(job)
            );
        } finally {
            recentSummaryManager.shutdown();
        }
    }

    @Test
    @DisplayName("""
        Given a running summary refresh whose cursor becomes stale before write-back,
        When the background update completes,
        Then it should not overwrite the summary file or advance the summary cursor
        """)
    void shouldSkipWriteBackWhenSummaryCursorChangedDuringBackgroundRefresh() throws Exception {
        TestContext context = createContext(
            """
                recentSummary.maxRecentTurns=2
                summary.batchMessages=2
                """
        );
        context.historyStore().save(
            new HistoryState(
                List.of(
                    new Message("user", "Turn one"),
                    new Message("assistant", "Reply one"),
                    new Message("user", "Turn two"),
                    new Message("assistant", "Reply two"),
                    new Message("user", "Turn three"),
                    new Message("assistant", "Reply three")
                ),
                0,
                0,
                0
            )
        );
        FileSupport.writeTextFile(context.config().summaryFile(), "Original summary");

        BlockingChatClient client = new BlockingChatClient("Updated summary");
        SummaryManager summaryManager = createSummaryManager(context, client);
        try {
            summaryManager.startUpdateSummaryIfNeeded();
            assertTrue(client.started().await(5, TimeUnit.SECONDS));

            context.historyStore().markSummarized(1);
            client.allowFinish().countDown();
            assertTrue(client.finished().await(5, TimeUnit.SECONDS));

            awaitWorkerIdle(summaryManager);

            assertEquals("Original summary", FileSupport.readTextFile(context.config().summaryFile()));
            assertEquals(1, context.historyStore().load().summaryCursor());
        } finally {
            summaryManager.shutdown();
        }
    }

    @Test
    @DisplayName("""
        Given derived-memory prompt builders and fixed protagonists context,
        When background summary prompts are assembled,
        Then each request should contain one combined system message followed by one user message
        """)
    void shouldBuildDerivedMemoryPromptsWithSingleSystemMessage() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-derived-prompts");
        writeOverride(baseDirectory, "systemprompts/fixed_protagonists.yml", "FIXED DATA");
        writeOverride(baseDirectory, "systemprompts/fixedprotagonistscontext.md", "FIXED PROTAGONISTS:%n%s");
        writeOverride(baseDirectory, "systemprompts/summarysystemprompt.md", "SUMMARY SYSTEM");
        writeOverride(baseDirectory, "systemprompts/recentsummarysystemprompt.md", "RECENT SYSTEM");
        writeOverride(baseDirectory, "systemprompts/canonicalstatesystemprompt.md", "CANONICAL SYSTEM");

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);
        PromptLoader promptResourceLoader = new PromptLoader(config);
        PromptTemplateService promptTemplateService = new PromptTemplateService(promptResourceLoader);

        assertDerivedPromptShape(
            new SummaryPromptBuilder(promptResourceLoader, promptTemplateService)
                .build(new SummaryPromptInput("Old summary", "USER: Turn one")),
            "SUMMARY SYSTEM"
        );
        assertDerivedPromptShape(
            new RecentSummaryPromptBuilder(promptResourceLoader, promptTemplateService)
                .build(new RecentSummaryPromptInput("Old recent", "USER: Turn two")),
            "RECENT SYSTEM"
        );
        assertDerivedPromptShape(
            new CanonicalStatePromptBuilder(promptResourceLoader, promptTemplateService)
                .build(new CanonicalStatePromptInput("Old canonical", "USER: Turn three")),
            "CANONICAL SYSTEM"
        );
    }

    private TestContext createContext(String overrides) throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-derived-memory");
        Path configFile = baseDirectory.resolve("systemprompts/application.config");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, overrides);

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);
        HistoryStore historyStore = new HistoryStore(config.historyFile(), config.legacyHistoryFile());
        PromptLoader promptResourceLoader = new PromptLoader(config);
        PromptTemplateService promptTemplateService = new PromptTemplateService(promptResourceLoader);
        return new TestContext(config, historyStore, promptResourceLoader, promptTemplateService);
    }

    private SummaryManager createSummaryManager(TestContext context, ChatClient client) {
        return new SummaryManager(
            context.historyStore(),
            client,
            context.config(),
            context.promptResourceLoader(),
            context.promptTemplateService(),
            new SummaryPromptBuilder(context.promptResourceLoader(), context.promptTemplateService())
        );
    }

    private RecentSummaryManager createRecentSummaryManager(TestContext context, ChatClient client) {
        return new RecentSummaryManager(
            context.historyStore(),
            client,
            context.config(),
            context.promptResourceLoader(),
            context.promptTemplateService(),
            new RecentSummaryPromptBuilder(context.promptResourceLoader(), context.promptTemplateService())
        );
    }

    private CanonicalStateManager createCanonicalStateManager(TestContext context, ChatClient client) {
        return new CanonicalStateManager(
            context.historyStore(),
            client,
            context.config(),
            context.promptResourceLoader(),
            context.promptTemplateService(),
            new CanonicalStatePromptBuilder(context.promptResourceLoader(), context.promptTemplateService())
        );
    }

    private void awaitWorkerIdle(Object manager) {
        try {
            var runningField = manager.getClass().getSuperclass().getDeclaredField("running");
            runningField.setAccessible(true);
            AtomicBoolean running = (AtomicBoolean) runningField.get(manager);

            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (running.get()) {
                if (System.nanoTime() >= deadlineNanos) {
                    throw new AssertionError("Background worker did not become idle within the timeout.");
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private Object prepareJob(Object manager) {
        try {
            Method method = manager.getClass().getDeclaredMethod("prepareJob");
            method.setAccessible(true);
            return method.invoke(manager);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Message> pendingMessages(Object job) {
        try {
            Method method = job.getClass().getDeclaredMethod("pendingMessages");
            method.setAccessible(true);
            return (List<Message>) method.invoke(job);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private void assertDerivedPromptShape(List<Message> messages, String expectedSystemHeader) {
        assertEquals(2, messages.size());
        assertEquals("system", messages.getFirst().role());
        assertTrue(messages.getFirst().content().startsWith(expectedSystemHeader));
        assertTrue(messages.getFirst().content().contains("FIXED PROTAGONISTS:\nFIXED DATA"));
        assertEquals("user", messages.getLast().role());
    }

    private void writeOverride(Path baseDirectory, String relativePath, String content) {
        Path path = baseDirectory.resolve(relativePath);
        try {
            Files.createDirectories(path.getParent());
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        FileSupport.writeTextFile(path, content);
    }

    private record TestContext(
        nl.llm.storyteller.core.config.AppConfig config,
        HistoryStore historyStore,
        PromptLoader promptResourceLoader,
        PromptTemplateService promptTemplateService
    ) {}

    private record NoOpChatClient() implements ChatClient {
        @Override
        public String chat(List<Message> messages, Map<String, Object> options, int timeoutSeconds) {
            return "";
        }
    }

    private static final class BlockingChatClient implements ChatClient {
        private final String response;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch allowFinish = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        private BlockingChatClient(String response) {
            this.response = response;
        }

        @Override
        public String chat(List<Message> messages, Map<String, Object> options, int timeoutSeconds) throws InterruptedException {
            started.countDown();
            if (!allowFinish.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out while waiting for the test to release the blocking chat client.");
            }
            finished.countDown();
            return response;
        }

        CountDownLatch started() {
            return started;
        }

        CountDownLatch allowFinish() {
            return allowFinish;
        }

        CountDownLatch finished() {
            return finished;
        }
    }

    private static final class SequencedChatClient implements ChatClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        private final CountDownLatch secondFinished = new CountDownLatch(1);

        @Override
        public String chat(List<Message> messages, Map<String, Object> options, int timeoutSeconds) throws InterruptedException {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstStarted.countDown();
                if (!allowFirstToFinish.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out while waiting to finish the first canonical-state refresh.");
                }
            } else if (call == 2) {
                secondFinished.countDown();
            }
            return "Canonical state " + call;
        }

        CountDownLatch firstStarted() {
            return firstStarted;
        }

        CountDownLatch allowFirstToFinish() {
            return allowFirstToFinish;
        }

        CountDownLatch secondFinished() {
            return secondFinished;
        }
    }
}
