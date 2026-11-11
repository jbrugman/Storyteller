package nl.llm.storyteller.core;

import nl.llm.storyteller.core.model.HistoryState;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.service.CanonicalStateManager;
import nl.llm.storyteller.core.service.CanonicalStatePromptBuilder;
import nl.llm.storyteller.core.service.ChatClient;
import nl.llm.storyteller.core.service.GameModeDefinitionParser;
import nl.llm.storyteller.core.service.HistoryStore;
import nl.llm.storyteller.core.service.PromptAssemblyService;
import nl.llm.storyteller.core.service.PromptLoader;
import nl.llm.storyteller.core.service.PromptTemplateService;
import nl.llm.storyteller.core.service.RollingContextWindow;
import nl.llm.storyteller.core.service.RecentSummaryManager;
import nl.llm.storyteller.core.service.RecentSummaryPromptBuilder;
import nl.llm.storyteller.core.service.StoryChatPromptBuilder;
import nl.llm.storyteller.core.service.SummaryManager;
import nl.llm.storyteller.core.service.SummaryPromptBuilder;
import nl.llm.storyteller.core.service.TurnManager;
import nl.llm.storyteller.core.service.TurnStateStore;
import nl.llm.storyteller.core.service.ValidationPromptBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAssemblyServiceTest {
    @Test
    @DisplayName("""
        Given fixed protagonists, canonical state, summaries, and two recent turns,
        When the storyteller prompt stack is assembled,
        Then system context should come first, followed by the recent raw turns and the latest user message
        """)
    void shouldAssemblePromptInExpectedOrder() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-prompt-assembly");
        writeOverride(baseDirectory, "systemprompts/systemprompt.md", "SYSTEM PROMPT");
        writeOverride(baseDirectory, "systemprompts/fixed_protagonists.yml", "FIXED DATA");
        writeOverride(baseDirectory, "systemprompts/fixedprotagonistscontext.md", "FIXED PROTAGONISTS:%n%s");
        writeOverride(baseDirectory, "systemprompts/summarycontext.md", "SUMMARY CONTEXT:%n%s");
        writeOverride(baseDirectory, "systemprompts/recentsummarycontext.md", "RECENT CONTEXT:%n%s");
        writeOverride(baseDirectory, "systemprompts/canonicalstatecontext.md", "CANONICAL CONTEXT:%n%s");
        Files.createDirectories(baseDirectory.resolve("memory"));

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);
        HistoryStore historyStore = getHistoryStore(config);

        FileSupport.writeTextFile(config.summaryFile(), "LONG SUMMARY");
        FileSupport.writeTextFile(config.recentSummaryFile(), "RECENT SUMMARY");
        FileSupport.writeTextFile(config.canonicalStateFile(), "CANONICAL STATE");

        PromptLoader promptResourceLoader = new PromptLoader(config);
        PromptTemplateService promptTemplateService = new PromptTemplateService(promptResourceLoader);
        StoryChatPromptBuilder storyChatPromptBuilder = new StoryChatPromptBuilder(
            promptResourceLoader,
            promptTemplateService
        );
        ValidationPromptBuilder validationPromptBuilder = new ValidationPromptBuilder(
            promptResourceLoader,
            promptTemplateService
        );
        SummaryManager summaryManager = new SummaryManager(
            historyStore,
            new NoOpChatClient(),
            config,
            promptResourceLoader,
            promptTemplateService,
            new SummaryPromptBuilder(promptResourceLoader, promptTemplateService)
        );
        RecentSummaryManager recentSummaryManager = new RecentSummaryManager(
            historyStore,
            new NoOpChatClient(),
            config,
            promptResourceLoader,
            promptTemplateService,
            new RecentSummaryPromptBuilder(promptResourceLoader, promptTemplateService)
        );
        CanonicalStateManager canonicalStateManager = new CanonicalStateManager(
            historyStore,
            new NoOpChatClient(),
            config,
            promptResourceLoader,
            promptTemplateService,
            new CanonicalStatePromptBuilder(promptResourceLoader, promptTemplateService)
        );
        TurnManager turnManager = new TurnManager(
            config,
            promptResourceLoader,
            promptTemplateService,
            new GameModeDefinitionParser(),
            new TurnStateStore(config.turnStateFile())
        );

        try {
            PromptAssemblyService promptAssemblyService = new PromptAssemblyService(
                historyStore,
                summaryManager,
                recentSummaryManager,
                canonicalStateManager,
                turnManager,
                storyChatPromptBuilder,
                validationPromptBuilder
            );

            List<Message> messages = promptAssemblyService.buildChatMessages("Newest user input");

            assertEquals(
                List.of(
                    new Message("system", """
                        SYSTEM PROMPT

                        FIXED PROTAGONISTS:
                        FIXED DATA

                        CANONICAL CONTEXT:
                        CANONICAL STATE

                        SUMMARY CONTEXT:
                        LONG SUMMARY

                        RECENT CONTEXT:
                        RECENT SUMMARY
                        """.stripIndent().trim()),
                    new Message("user", "Recent user 1"),
                    new Message("assistant", "Recent assistant 1"),
                    new Message("user", "Recent user 2"),
                    new Message("assistant", "Recent assistant 2"),
                    new Message("user", "Newest user input")
                ),
                messages
            );
            assertEquals(
                1L,
                messages.stream().filter(message -> "system".equals(message.role())).count()
            );
        } finally {
            summaryManager.shutdown();
            recentSummaryManager.shutdown();
            canonicalStateManager.shutdown();
        }
    }

    @Test
    @DisplayName("""
        Given turn-based mode is enabled for a fixed party,
        When the same protagonist tries to act twice before the round is complete,
        Then the assembled prompt should append the turn violation rule to the latest user turn
        """)
    void shouldInjectTurnViolationInstructionForExtraMove() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-turn-based-prompt");
        writeOverride(baseDirectory, "systemprompts/systemprompt.md", "SYSTEM PROMPT");
        writeOverride(baseDirectory, "systemprompts/fixedprotagonistscontext.md", "FIXED PROTAGONISTS:%n%s");
        writeOverride(baseDirectory, "systemprompts/summarycontext.md", "SUMMARY CONTEXT:%n%s");
        writeOverride(baseDirectory, "systemprompts/recentsummarycontext.md", "RECENT CONTEXT:%n%s");
        writeOverride(baseDirectory, "systemprompts/canonicalstatecontext.md", "CANONICAL CONTEXT:%n%s");
        writeOverride(baseDirectory, "systemprompts/fixed_protagonists.yml", """
            game:
              trigger_word: "start"

            fixed_protagonist:
              - name: "Eldrin"
              - name: "Thorin"
            """);
        writeOverride(baseDirectory, "systemprompts/application.config", """
            game.turnBasedModeEnabled=true
            game.turnPenaltySingleLowHp=5
            game.turnPenaltySingleHighHp=10
            """);
        Files.createDirectories(baseDirectory.resolve("memory"));

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);
        HistoryStore historyStore = new HistoryStore(config.historyFile(), config.legacyHistoryFile());
        PromptLoader promptResourceLoader = new PromptLoader(config);
        PromptTemplateService promptTemplateService = new PromptTemplateService(promptResourceLoader);
        StoryChatPromptBuilder storyChatPromptBuilder = new StoryChatPromptBuilder(promptResourceLoader, promptTemplateService);
        ValidationPromptBuilder validationPromptBuilder = new ValidationPromptBuilder(promptResourceLoader, promptTemplateService);
        SummaryManager summaryManager = new SummaryManager(
            historyStore,
            new NoOpChatClient(),
            config,
            promptResourceLoader,
            promptTemplateService,
            new SummaryPromptBuilder(promptResourceLoader, promptTemplateService)
        );
        RecentSummaryManager recentSummaryManager = new RecentSummaryManager(
            historyStore,
            new NoOpChatClient(),
            config,
            promptResourceLoader,
            promptTemplateService,
            new RecentSummaryPromptBuilder(promptResourceLoader, promptTemplateService)
        );
        CanonicalStateManager canonicalStateManager = new CanonicalStateManager(
            historyStore,
            new NoOpChatClient(),
            config,
            promptResourceLoader,
            promptTemplateService,
            new CanonicalStatePromptBuilder(promptResourceLoader, promptTemplateService)
        );
        TurnManager turnManager = new TurnManager(
            config,
            promptResourceLoader,
            promptTemplateService,
            new GameModeDefinitionParser(),
            new TurnStateStore(config.turnStateFile())
        );

        try {
            PromptAssemblyService promptAssemblyService = new PromptAssemblyService(
                historyStore,
                summaryManager,
                recentSummaryManager,
                canonicalStateManager,
                turnManager,
                storyChatPromptBuilder,
                validationPromptBuilder
            );

            promptAssemblyService.buildChatMessages("start");
            promptAssemblyService.buildChatMessages("(Eldrin) I open the stone door.");
            List<Message> messages = promptAssemblyService.buildChatMessages("(Eldrin) I cast another spell immediately.");

            assertEquals(
                1L,
                messages.stream().filter(message -> "system".equals(message.role())).count()
            );
            Message latestUserInput = messages.getLast();

            assertEquals("user", latestUserInput.role());
            assertTrue(latestUserInput.content().startsWith("(Eldrin) I cast another spell immediately."));
            assertTrue(latestUserInput.content().contains("(this action is attempted out of turn."));
            assertTrue(latestUserInput.content().contains("must lose 5 or 10 health points"));
        } finally {
            summaryManager.shutdown();
            recentSummaryManager.shutdown();
            canonicalStateManager.shutdown();
        }
    }

    @Test
    @DisplayName("""
        Given recent history exists,
        When a reset control prompt is assembled,
        Then the reset request should keep the combined system context but omit raw recent turns
        """)
    void shouldAssembleResetPromptWithoutRecentHistory() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-reset-prompt-assembly");
        writeOverride(baseDirectory, "systemprompts/systemprompt.md", "SYSTEM PROMPT");
        writeOverride(baseDirectory, "systemprompts/fixed_protagonists.yml", "FIXED DATA");
        writeOverride(baseDirectory, "systemprompts/fixedprotagonistscontext.md", "FIXED PROTAGONISTS:%n%s");
        writeOverride(baseDirectory, "systemprompts/summarycontext.md", "SUMMARY CONTEXT:%n%s");
        writeOverride(baseDirectory, "systemprompts/recentsummarycontext.md", "RECENT CONTEXT:%n%s");
        writeOverride(baseDirectory, "systemprompts/canonicalstatecontext.md", "CANONICAL CONTEXT:%n%s");
        Files.createDirectories(baseDirectory.resolve("memory"));

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);
        HistoryStore historyStore = getHistoryStore(config);
        FileSupport.writeTextFile(config.summaryFile(), "LONG SUMMARY");
        FileSupport.writeTextFile(config.recentSummaryFile(), "RECENT SUMMARY");
        FileSupport.writeTextFile(config.canonicalStateFile(), "CANONICAL STATE");

        PromptLoader promptResourceLoader = new PromptLoader(config);
        PromptTemplateService promptTemplateService = new PromptTemplateService(promptResourceLoader);
        StoryChatPromptBuilder storyChatPromptBuilder = new StoryChatPromptBuilder(promptResourceLoader, promptTemplateService);
        ValidationPromptBuilder validationPromptBuilder = new ValidationPromptBuilder(promptResourceLoader, promptTemplateService);
        SummaryManager summaryManager = new SummaryManager(
            historyStore,
            new NoOpChatClient(),
            config,
            promptResourceLoader,
            promptTemplateService,
            new SummaryPromptBuilder(promptResourceLoader, promptTemplateService)
        );
        RecentSummaryManager recentSummaryManager = new RecentSummaryManager(
            historyStore,
            new NoOpChatClient(),
            config,
            promptResourceLoader,
            promptTemplateService,
            new RecentSummaryPromptBuilder(promptResourceLoader, promptTemplateService)
        );
        CanonicalStateManager canonicalStateManager = new CanonicalStateManager(
            historyStore,
            new NoOpChatClient(),
            config,
            promptResourceLoader,
            promptTemplateService,
            new CanonicalStatePromptBuilder(promptResourceLoader, promptTemplateService)
        );
        TurnManager turnManager = new TurnManager(
            config,
            promptResourceLoader,
            promptTemplateService,
            new GameModeDefinitionParser(),
            new TurnStateStore(config.turnStateFile())
        );

        try {
            PromptAssemblyService promptAssemblyService = new PromptAssemblyService(
                historyStore,
                summaryManager,
                recentSummaryManager,
                canonicalStateManager,
                turnManager,
                storyChatPromptBuilder,
                validationPromptBuilder
            );

            List<Message> messages = promptAssemblyService.buildResetMessages(config.resetStoryCommand());

            assertEquals(2, messages.size());
            assertEquals("system", messages.getFirst().role());
            assertEquals("user", messages.getLast().role());
            assertEquals(config.resetStoryCommand(), messages.getLast().content());
        } finally {
            summaryManager.shutdown();
            recentSummaryManager.shutdown();
            canonicalStateManager.shutdown();
        }
    }

    @Test
    @DisplayName("""
        Given turns-mode context with stored history,
        When a chat request is assembled,
        Then it should record no rolling frontier and send the configured recent turns unchanged
        """)
    void shouldKeepTheTurnsModeFrontierAtZero() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-assembly-turns");
        writeMinimalPrompts(baseDirectory);
        Files.createDirectories(baseDirectory.resolve("memory"));
        nl.llm.storyteller.core.config.AppConfig config =
            nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);
        FileSupport.writeTextFile(config.summaryFile(), "S");
        FileSupport.writeTextFile(config.recentSummaryFile(), "R");
        FileSupport.writeTextFile(config.canonicalStateFile(), "C");
        HistoryStore historyStore = getHistoryStore(config);

        try (Wiring wiring = wire(config, historyStore)) {
            PromptAssemblyService.AssembledRequest request = wiring.service.buildChatRequest("Newest user input");

            assertEquals(0, request.contextTarget());
            assertTrue(request.messages().stream().anyMatch(message -> "Recent user 2".equals(message.content())));
            assertTrue(request.messages().stream().noneMatch(message -> "Older user".equals(message.content())));
        }
    }

    @Test
    @DisplayName("""
        Given rolling-mode context whose window cannot hold the stored story,
        When a chat request is assembled with and without an attached picture,
        Then the recorded frontier should be the absolute index of the first verbatim message, and the
            picture should be charged against the budget before history is trimmed
        """)
    void shouldRecordAnAbsoluteRollingFrontierAndChargeImagesBeforeTrimming() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-assembly-rolling");
        writeMinimalPrompts(baseDirectory);
        writeOverride(baseDirectory, "systemprompts/application.config", """
            chat.contextMode=rolling
            chat.contextWindow=3400
            chat.responseReserveTokens=0
            """);
        Files.createDirectories(baseDirectory.resolve("memory"));
        nl.llm.storyteller.core.config.AppConfig config =
            nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);
        FileSupport.writeTextFile(config.summaryFile(), "S");
        FileSupport.writeTextFile(config.recentSummaryFile(), "R");
        FileSupport.writeTextFile(config.canonicalStateFile(), "C");
        HistoryStore historyStore = rollingHistory(config);
        int totalMessages = 16;

        try (Wiring wiring = wire(config, historyStore)) {
            PromptAssemblyService.AssembledRequest text = wiring.service.buildChatRequest("Newest user input");
            int verbatimText = text.messages().size() - 2; // minus the system message and the current prompt
            assertEquals(totalMessages - verbatimText, text.contextTarget());
            assertTrue(text.contextTarget() > 0,
                "the window cannot hold the whole story, so older turns must be trimmed");

            PromptAssemblyService.AssembledRequest withImage = wiring.service.buildChatRequest(
                "Newest user input", "data:image/png;base64,IMG");
            int verbatimImage = withImage.messages().size() - 2;
            assertEquals(totalMessages - verbatimImage, withImage.contextTarget());
            assertTrue(withImage.contextTarget() > text.contextTarget(),
                "a picture costs context, so it must trim at least one more whole turn than the same prompt without one");
        }
    }

    @Test
    @DisplayName("""
        Given an active rolling window that leaves room for the story but not for the story plus a turn
            rule,
        When a turn is assembled that needs an out-of-turn instruction,
        Then the instruction should be paid for out of the window before history is trimmed instead of
            being added on top of an already full request
        """)
    void shouldChargeTheTurnRuleAgainstTheRollingWindowBeforeTrimming() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-assembly-turn-rule");
        writeOverride(baseDirectory, "systemprompts/systemprompt.md", "SYS");
        writeOverride(baseDirectory, "systemprompts/fixedprotagonistscontext.md", "FP:%n%s");
        writeOverride(baseDirectory, "systemprompts/summarycontext.md", "SUM:%n%s");
        writeOverride(baseDirectory, "systemprompts/recentsummarycontext.md", "REC:%n%s");
        writeOverride(baseDirectory, "systemprompts/canonicalstatecontext.md", "CAN:%n%s");
        writeOverride(baseDirectory, "systemprompts/fixed_protagonists.yml", """
            game:
              trigger_word: "start"

            fixed_protagonist:
              - name: "Eldrin"
              - name: "Thorin"
            """);
        writeOverride(baseDirectory, "systemprompts/application.config", """
            chat.contextMode=rolling
            chat.contextWindow=200
            chat.responseReserveTokens=0
            game.turnBasedModeEnabled=true
            game.turnPenaltySingleLowHp=5
            game.turnPenaltySingleHighHp=10
            """);
        Files.createDirectories(baseDirectory.resolve("memory"));
        nl.llm.storyteller.core.config.AppConfig config =
            nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);
        FileSupport.writeTextFile(config.summaryFile(), "S");
        FileSupport.writeTextFile(config.recentSummaryFile(), "R");
        FileSupport.writeTextFile(config.canonicalStateFile(), "C");
        HistoryStore historyStore = new HistoryStore(config.historyFile(), config.legacyHistoryFile());
        List<Message> history = new java.util.ArrayList<>();
        for (int turn = 1; turn <= 8; turn++) {
            history.add(new Message("user", "user-" + turn));
            history.add(new Message("assistant", "assistant-" + turn));
        }
        historyStore.save(new HistoryState(history, 0, 0, 0));

        try (Wiring wiring = wire(config, historyStore)) {
            wiring.service.buildChatRequest("start");
            wiring.service.buildChatRequest("(Eldrin) I open the first door.");

            PromptAssemblyService.AssembledRequest ruled =
                wiring.service.buildChatRequest("(Eldrin) I charge ahead again.");
            int sentTokens = RollingContextWindow.estimateMessages(ruled.messages());

            assertTrue(ruled.messages().getLast().content().contains("(this action is attempted out of turn."),
                "the out-of-turn rule still has to reach the model, however tight the window is");
            assertTrue(sentTokens <= 200,
                "the rule costs context, so it must be paid for before history is trimmed instead of added"
                    + " to an already full request: sent " + sentTokens + " tokens in a 200 token window");
            assertTrue(ruled.messages().size() > 2,
                "the window should still hold recent story, not only prompts and the current turn");
            assertTrue(ruled.contextTarget() > 0,
                "the rule costs context, so older turns must fall out of the request");
        }
    }

    private void writeMinimalPrompts(Path baseDirectory) {
        writeOverride(baseDirectory, "systemprompts/systemprompt.md", "SYS");
        writeOverride(baseDirectory, "systemprompts/fixed_protagonists.yml", "P");
        writeOverride(baseDirectory, "systemprompts/fixedprotagonistscontext.md", "FP:%n%s");
        writeOverride(baseDirectory, "systemprompts/summarycontext.md", "SUM:%n%s");
        writeOverride(baseDirectory, "systemprompts/recentsummarycontext.md", "REC:%n%s");
        writeOverride(baseDirectory, "systemprompts/canonicalstatecontext.md", "CAN:%n%s");
    }

    private static HistoryStore rollingHistory(nl.llm.storyteller.core.config.AppConfig config) {
        HistoryStore historyStore = new HistoryStore(config.historyFile(), config.legacyHistoryFile());
        List<Message> messages = new java.util.ArrayList<>();
        String filler = "x".repeat(900);
        for (int turn = 1; turn <= 8; turn++) {
            messages.add(new Message("user", "user-" + turn + filler));
            messages.add(new Message("assistant", "assistant-" + turn + filler));
        }
        historyStore.save(new HistoryState(messages, 0, 0, 0));
        return historyStore;
    }

    private static final class Wiring implements AutoCloseable {
        final PromptAssemblyService service;
        private final SummaryManager summary;
        private final RecentSummaryManager recent;
        private final CanonicalStateManager canonical;

        Wiring(PromptAssemblyService service, SummaryManager summary, RecentSummaryManager recent,
               CanonicalStateManager canonical) {
            this.service = service;
            this.summary = summary;
            this.recent = recent;
            this.canonical = canonical;
        }

        @Override
        public void close() {
            summary.shutdown();
            recent.shutdown();
            canonical.shutdown();
        }
    }

    private static Wiring wire(nl.llm.storyteller.core.config.AppConfig config, HistoryStore historyStore) {
        PromptLoader promptResourceLoader = new PromptLoader(config);
        PromptTemplateService promptTemplateService = new PromptTemplateService(promptResourceLoader);
        StoryChatPromptBuilder storyChatPromptBuilder = new StoryChatPromptBuilder(promptResourceLoader, promptTemplateService);
        ValidationPromptBuilder validationPromptBuilder = new ValidationPromptBuilder(promptResourceLoader, promptTemplateService);
        SummaryManager summaryManager = new SummaryManager(
            historyStore, new NoOpChatClient(), config, promptResourceLoader, promptTemplateService,
            new SummaryPromptBuilder(promptResourceLoader, promptTemplateService));
        RecentSummaryManager recentSummaryManager = new RecentSummaryManager(
            historyStore, new NoOpChatClient(), config, promptResourceLoader, promptTemplateService,
            new RecentSummaryPromptBuilder(promptResourceLoader, promptTemplateService));
        CanonicalStateManager canonicalStateManager = new CanonicalStateManager(
            historyStore, new NoOpChatClient(), config, promptResourceLoader, promptTemplateService,
            new CanonicalStatePromptBuilder(promptResourceLoader, promptTemplateService));
        TurnManager turnManager = new TurnManager(
            config, promptResourceLoader, promptTemplateService, new GameModeDefinitionParser(),
            new TurnStateStore(config.turnStateFile()));
        PromptAssemblyService service = new PromptAssemblyService(
            historyStore, summaryManager, recentSummaryManager, canonicalStateManager, turnManager,
            storyChatPromptBuilder, validationPromptBuilder);
        return new Wiring(service, summaryManager, recentSummaryManager, canonicalStateManager);
    }

    private static HistoryStore getHistoryStore(nl.llm.storyteller.core.config.AppConfig config) {
        HistoryStore historyStore = new HistoryStore(config.historyFile(), config.legacyHistoryFile());
        historyStore.save(
            new HistoryState(
                List.of(
                    new Message("user", "Older user"),
                    new Message("assistant", "Older assistant"),
                    new Message("user", "Recent user 1"),
                    new Message("assistant", "Recent assistant 1"),
                    new Message("user", "Recent user 2"),
                    new Message("assistant", "Recent assistant 2")
                ),
                0,
                0,
                0
            )
        );
        return historyStore;
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

    private static final class NoOpChatClient implements ChatClient {
        @Override
        public String chat(List<Message> messages, Map<String, Object> options, int timeoutSeconds) {
            return "";
        }
    }
}
