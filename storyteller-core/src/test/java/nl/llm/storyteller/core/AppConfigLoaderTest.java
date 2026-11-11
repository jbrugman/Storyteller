package nl.llm.storyteller.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigLoaderTest {
    @ParameterizedTest
    @ValueSource(strings = {"graph.enabled", "validation.enabled"})
    @DisplayName("""
        Given a disabled core processing feature,
        When the application config is loaded,
        Then the corresponding core switch should be off
        """)
    void shouldDisableCoreProcessingFeatures(String key) throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-config-switch");
        Path configFile = baseDirectory.resolve("systemprompts/application.config");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, key + "=false\n");

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);

        boolean enabled = switch (key) {
            case "graph.enabled" -> config.graphEnabled();
            case "validation.enabled" -> config.validationEnabled();
            default -> throw new IllegalArgumentException(key);
        };
        assertEquals(false, enabled);
    }

    @Test
    @DisplayName("""
        Given deterministic chat options in an override,
        When the application config is loaded,
        Then seed and maximum output tokens should be sent only when explicitly configured
        """)
    void shouldLoadOptionalDeterministicChatOptions() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-config-deterministic");
        Path configFile = baseDirectory.resolve("systemprompts/application.config");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
            chat.seed=42
            chat.maxTokens=128
            summary.maxTokens=2048
            validation.maxTokens=64
            """);

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);

        assertEquals(42, config.chatOptions().get("seed"));
        assertEquals(128, config.chatOptions().get("max_tokens"));
        assertEquals(42, config.summaryOptions().get("seed"));
        assertEquals(2048, config.summaryOptions().get("max_tokens"));
        assertEquals(64, config.validationOptions().get("max_tokens"));
    }

    @Test
    @DisplayName("""
        Given a turn-based graph batch size below one,
        When the application config is loaded,
        Then the invalid update interval should be rejected
        """)
    void shouldRejectInvalidTurnBasedGraphBatchSize() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-config-graph-turn-batch");
        Path configFile = baseDirectory.resolve("systemprompts/application.config");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, "graph.turnBased.batchTurns=0");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null)
        );

        assertTrue(exception.getMessage().contains("Batch sizes must all be at least 1"));
    }

    @Test
    @DisplayName("""
        Given no local configuration override,
        When the application config is loaded for a base directory,
        Then bundled defaults should still resolve file paths relative to that base directory
        """)
    void shouldResolveBundledFileDefaultsRelativeToBaseDirectory() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-config-base");

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);

        assertEquals(baseDirectory.toAbsolutePath().normalize(), config.baseDir());
        assertTrue(config.chatModel().isBlank());
        assertTrue(config.validatorModel().isBlank());
        assertTrue(config.openAiCompatibleApiKey().isBlank());
        assertTrue(config.commandHelpText().contains("/image <instruction>"));
        assertTrue(config.commandHelpText().contains("/graph -reset"));
    assertEquals(3, config.graphTurnBasedBatchTurns());
        assertTrue(config.graphEnabled());
        assertEquals("auto", config.validationOutputMode());
        assertEquals(
            baseDirectory.resolve("systemprompts/systemprompt.md").normalize(),
            config.systemPromptFile()
        );
        assertEquals(
            baseDirectory.resolve("memory/history.json").normalize(),
            config.historyFile()
        );
    }

    @Test
    @DisplayName("""
        Given a local application.config override with relative file paths,
        When the application config is loaded,
        Then override values should win and relative file paths should resolve from the application base directory
        """)
    void shouldPreferLocalOverrideAndResolveItsRelativePathsFromBaseDirectory() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-config-override");
        Path configFile = baseDirectory.resolve("systemprompts/application.config");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
            lmstudio.url=http://localhost:9999/v1/chat/completions
            backend.http.apiKey=test-api-key
            model.chat=test-chat-model
            file.systemPrompt=overrides/story.md
            chat.maxRecentTurns=3
            """);

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);

        assertEquals("http://localhost:9999/v1/chat/completions", config.openAiCompatibleUrl());
        assertEquals("test-api-key", config.openAiCompatibleApiKey());
        assertEquals("test-chat-model", config.chatModel());
        assertEquals(3, config.maxRecentTurns());
        assertEquals(
            baseDirectory.resolve("overrides/story.md").normalize(),
            config.systemPromptFile()
        );
    }

    @Test
    @DisplayName("""
        Given a runtime override file next to the executable with relative file paths,
        When the application config is loaded,
        Then the runtime override should take precedence and resolve relative file paths from the executable directory
        """)
    void shouldPreferRuntimeOverrideAndResolveItsRelativePathsFromExecutableDirectory() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-config-runtime-base");
        Path runtimeDirectory = Files.createTempDirectory("storyteller-config-runtime-dir");
        Path runtimeOverride = runtimeDirectory.resolve("application.config");
        Files.writeString(runtimeOverride, """
            model.validator=test-validator-model
            file.rules=runtime/rules.md
            """);

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, runtimeOverride);

        assertEquals("test-validator-model", config.validatorModel());
        assertEquals(
            runtimeDirectory.resolve("runtime/rules.md").normalize(),
            config.rulesFile()
        );
    }

    @Test
    @DisplayName("Memory model access should override and otherwise fall back to the main backend")
    void shouldLoadMemoryModelAccessWithBackendFallbacks() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-config-memory-access");
        Path configFile = baseDirectory.resolve("systemprompts/application.config");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
            backend.http.url=http://main.test/v1/chat/completions
            backend.http.apiKey=main-key
            model.chat=main-model
            """);

        nl.llm.storyteller.core.config.AppConfig fallback =
            nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);

        assertEquals("http://main.test/v1/chat/completions", fallback.memoryHttpUrl());
        assertEquals("main-key", fallback.memoryHttpApiKey());
        assertEquals("main-model", fallback.memoryHttpModel());

        Files.writeString(configFile, """
            backend.http.url=http://main.test/v1/chat/completions
            backend.http.apiKey=main-key
            model.chat=main-model
            memory.http.url=http://memory.test/v1/chat/completions
            memory.http.apikey=memory-key
            memory.http.model=memory-model
            """);

        nl.llm.storyteller.core.config.AppConfig overridden =
            nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);

        assertEquals("http://memory.test/v1/chat/completions", overridden.memoryHttpUrl());
        assertEquals("memory-key", overridden.memoryHttpApiKey());
        assertEquals("memory-model", overridden.memoryHttpModel());
        assertEquals("http://main.test/v1/chat/completions", overridden.openAiCompatibleUrl());
        assertEquals("main-key", overridden.openAiCompatibleApiKey());
        assertEquals("main-model", overridden.chatModel());
    }

    @Test
    @DisplayName("""
        Given a local application.config override that leaves model settings blank,
        When the application config is loaded,
        Then the blank values should be preserved so the backend can choose the active default model
        """)
    void shouldAllowBlankModelConfiguration() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-config-blank-model");
        Path configFile = baseDirectory.resolve("systemprompts/application.config");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
            model.chat=
            model.validator=
            """);

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);

        assertTrue(config.chatModel().isBlank());
        assertTrue(config.validatorModel().isBlank());
    }

    @Test
    @DisplayName("""
        Given a managed llama-server configuration with a relative model path,
        When the application config is loaded,
        Then it should select the managed backend and resolve the model path from the base directory
        """)
    void shouldLoadManagedLlamaServerConfiguration() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-config-llama-server");
        Path configFile = baseDirectory.resolve("systemprompts/application.config");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
            backend.type=managed-llama-server
            backend.llama.command=/opt/llama.cpp/llama-server
            backend.llama.modelPath=models/story.gguf
            backend.llama.port=8080
            backend.llama.startupTimeoutSeconds=90
            backend.llama.arguments=--ctx-size 32768
            """);

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);

        assertTrue(config.usesManagedLlamaServer());
        assertEquals("/opt/llama.cpp/llama-server", config.llamaServerConfig().command());
        assertEquals(baseDirectory.resolve("models/story.gguf").normalize(), config.llamaServerConfig().modelPath());
        assertEquals(8080, config.llamaServerConfig().port());
        assertEquals(90, config.llamaServerConfig().startupTimeoutSeconds());
    }

    @Test
    @DisplayName("""
        Given a managed MLX server configuration with a relative model path,
        When the application config is loaded,
        Then it should select the managed backend and resolve the model path from the base directory
        """)
    void shouldLoadManagedMlxServerConfiguration() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-config-mlx-server");
        Path configFile = baseDirectory.resolve("systemprompts/application.config");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
            backend.type=managed-mlx-server
            backend.mlx.command=/opt/mlx/bin/mlx_vlm.server
            backend.mlx.modelPath=models/story-mlx
            backend.mlx.port=8080
            backend.mlx.startupTimeoutSeconds=180
            backend.mlx.arguments=-m mlx_vlm.server --max-kv-size 32768 --max-tokens 32768
            """);

        nl.llm.storyteller.core.config.AppConfig config = nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);

        assertTrue(config.usesManagedMlxServer());
        assertEquals("/opt/mlx/bin/mlx_vlm.server", config.mlxServerConfig().command());
        assertEquals(baseDirectory.resolve("models/story-mlx").normalize(), config.mlxServerConfig().modelPath());
        assertEquals(baseDirectory.resolve("models/story-mlx").normalize().toString(), config.chatModel());
        assertEquals(baseDirectory.resolve("models/story-mlx").normalize().toString(), config.validatorModel());
        assertEquals(8080, config.mlxServerConfig().port());
        assertEquals(180, config.mlxServerConfig().startupTimeoutSeconds());
    }

    @Test
    @DisplayName("""
        Given no context mode configuration,
        When the application config is loaded,
        Then turns mode with an unknown context window should be the default
        """)
    void shouldDefaultToTurnsModeWithAnUnknownContextWindow() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-context-defaults");

        nl.llm.storyteller.core.config.AppConfig config =
            nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);

        assertEquals("turns", config.contextMode());
        assertEquals(0, config.contextWindow());
        assertEquals(1024, config.responseReserveTokens());
        assertEquals(0, config.chatMaxTokens());
    }

    @Test
    @DisplayName("""
        Given a rolling context mode override with a context window,
        When the application config is loaded,
        Then the rolling context settings should be exposed to callers
        """)
    void shouldLoadRollingContextSettingsFromAnOverride() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-context-rolling");
        Path configFile = baseDirectory.resolve("systemprompts/application.config");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
            chat.contextMode=rolling
            chat.contextWindow=8192
            chat.responseReserveTokens=2048
            """);

        nl.llm.storyteller.core.config.AppConfig config =
            nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null);

        assertEquals("rolling", config.contextMode());
        assertEquals(8192, config.contextWindow());
        assertEquals(2048, config.responseReserveTokens());
    }

    @Test
    @DisplayName("""
        Given an unknown context mode,
        When the application config is loaded,
        Then the invalid mode should be rejected
        """)
    void shouldRejectAnUnknownContextMode() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-context-mode");
        Path configFile = baseDirectory.resolve("systemprompts/application.config");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, "chat.contextMode=sliding");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null)
        );

        assertTrue(exception.getMessage().contains("chat.contextMode must be turns or rolling"));
    }

    @Test
    @DisplayName("""
        Given a negative context window,
        When the application config is loaded,
        Then the invalid window should be rejected
        """)
    void shouldRejectANegativeContextWindow() throws Exception {
        Path baseDirectory = Files.createTempDirectory("storyteller-context-window");
        Path configFile = baseDirectory.resolve("systemprompts/application.config");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, "chat.contextWindow=-1");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> nl.llm.storyteller.core.config.AppConfigLoader.load(baseDirectory, null)
        );

        assertTrue(exception.getMessage().contains("chat.contextWindow must be 0 for unknown"));
    }
}
