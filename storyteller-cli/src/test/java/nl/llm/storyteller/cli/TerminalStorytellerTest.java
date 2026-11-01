package nl.llm.storyteller.cli;

import nl.llm.storyteller.core.ApplicationContext;
import nl.llm.storyteller.core.TestAppConfigFactory;
import nl.llm.storyteller.core.config.AppConfig;
import nl.llm.storyteller.core.graph.persistence.KnowledgeGraphStore;
import nl.llm.storyteller.core.graph.service.KnowledgeGraphInitializer;
import nl.llm.storyteller.core.graph.service.KnowledgeGraphManagementService;
import nl.llm.storyteller.core.graph.service.ReadOnlyKnowledgeGraphService;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalStorytellerTest {
  @TempDir
  Path tempDir;

  @Test
  @DisplayName("""
    Given a terminal session with an exit command,
    When the storyteller runs,
    Then the banner should be printed and no command error should be shown
    """)
  void exitsCleanlyAfterPrintingTheBanner() throws Exception {
    TerminalResult result = runTerminal("/exit\n");

    assertTrue(result.output().contains(result.config().bannerStartText()));
    assertFalse(result.output().contains("error"));
  }

  @Test
  @DisplayName("""
    Given graph, image, and export commands with invalid arguments,
    When the storyteller handles the commands,
    Then a specific usage error should be printed for every command
    """)
  void reportsUsageErrorsForInvalidCommands() throws Exception {
    TerminalResult result = runTerminal("/graph unexpected\n/image\n/export unexpected\n/quit\n");
    String output = result.output().replaceAll("\\s+", " ");

    assertTrue(output.contains("Graph command error"));
    assertTrue(output.contains("Use /graph, /graph -generate, /graph -fill, or /graph -reset."));
    assertTrue(output.contains("Image command error"));
    assertTrue(output.contains("Use /image <instruction> after copying an image to the clipboard."));
    assertTrue(output.contains("Export command error"));
    assertTrue(output.contains("Use /export, /export -intro, /export -clean, /export -all, or /export -zip."));
  }

  @Test
  @DisplayName("""
    Given a terminal session that reaches end-of-file without an exit command,
    When the storyteller waits for another input line,
    Then the session should end cleanly after printing the banner
    """)
  void exitsCleanlyAtEndOfFile() throws Exception {
    TerminalResult result = runTerminal("");

    assertTrue(result.output().contains(result.config().bannerStartText()));
  }

  @Test
  @DisplayName("""
    Given an empty knowledge graph store,
    When the graph is displayed, generated, and displayed again,
    Then the terminal should show the empty graph and its incremented revision
    """)
  void handlesGraphDisplayAndGenerationCommands() throws Exception {
    AppConfig config = TestAppConfigFactory.load(tempDir);
    KnowledgeGraphStore store = new KnowledgeGraphStore(config.knowledgeGraphFile());
    ReadOnlyKnowledgeGraphService graphService = new ReadOnlyKnowledgeGraphService(store);
    ApplicationContext context = context(
      config,
      graphService,
      new KnowledgeGraphInitializer(store, graphService)
    );

    TerminalResult result = runTerminal("/graph\n/graph -generate\n/graph\n/exit\n", context);
    String output = result.output().replaceAll("\\s+", " ");

    assertTrue(output.contains("The graph is empty."));
    assertTrue(output.contains("Empty knowledge graph generated locally (revision 1)."));
    assertTrue(output.indexOf("Knowledge graph:") != output.lastIndexOf("Knowledge graph:"));
  }

  @Test
  @DisplayName("""
    Given a configured graph management service,
    When the graph reset command is entered,
    Then the terminal should report the selective turn-based reset result
    """)
  void handlesTurnBasedGraphResetCommand() throws Exception {
    AppConfig config = TestAppConfigFactory.load(tempDir);
    KnowledgeGraphStore store = new KnowledgeGraphStore(config.knowledgeGraphFile());
    ReadOnlyKnowledgeGraphService graphService = new ReadOnlyKnowledgeGraphService(store);
    KnowledgeGraphManagementService managementService = new KnowledgeGraphManagementService(store, graphService);
    ApplicationContext context = context(config, graphService, null, managementService);

    TerminalResult result = runTerminal("/graph -reset\n/exit\n", context);

    assertTrue(result.output().contains("Turn-based graph data reset: 0 entities and 0 facts removed"));
  }

  @Test
  @DisplayName("""
    Given streaming enabled for the web interfaces and validation disabled in configuration,
    When the CLI submits a story prompt,
    Then it uses a normal model request and displays the complete answer once without failing
    """)
  void shouldKeepCliWorkingWhenStreamingIsEnabled() throws Exception {
    var backend = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var request = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
    backend.createContext("/v1/chat/completions", exchange -> {
      request.set(nl.llm.storyteller.core.JsonSupport.OBJECT_MAPPER.readTree(exchange.getRequestBody()));
      byte[] response = "{\"choices\":[{\"message\":{\"content\":\"Complete CLI answer\"}}]}"
        .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      try (var output = exchange.getResponseBody()) { output.write(response); }
    });
    backend.start();
    try {
      var override = tempDir.resolve("streaming.config");
      java.nio.file.Files.writeString(override,
        "streaming.enabled=true\nvalidation.enabled=false\ngraph.enabled=false\nbackend.type=openai-compatible\n"
          + "model.chat=\nbackend.http.url=http://127.0.0.1:" + backend.getAddress().getPort() + "/v1/chat/completions\n");
      var config = nl.llm.storyteller.core.config.AppConfigLoader.load(tempDir, override);
      try (var context = nl.llm.storyteller.core.ApplicationFactory.create(config)) {
        TerminalResult result = runTerminal("Hello\n/exit\n", context);
        assertTrue(result.output().contains("Complete CLI answer"));
        org.junit.jupiter.api.Assertions.assertEquals(result.output().indexOf("Complete CLI answer"),
          result.output().lastIndexOf("Complete CLI answer"));
        org.junit.jupiter.api.Assertions.assertNotNull(request.get());
        org.junit.jupiter.api.Assertions.assertFalse(request.get().path("stream").asBoolean());
      }
    } finally { backend.stop(0); }
  }

  private TerminalResult runTerminal(String input) throws Exception {
    AppConfig config = TestAppConfigFactory.load(tempDir);
    return runTerminal(input, context(config, null, null));
  }

  private ApplicationContext context(
    AppConfig config,
    ReadOnlyKnowledgeGraphService graphService,
    KnowledgeGraphInitializer graphInitializer
  ) {
    return context(config, graphService, graphInitializer, null);
  }

  private ApplicationContext context(
    AppConfig config,
    ReadOnlyKnowledgeGraphService graphService,
    KnowledgeGraphInitializer graphInitializer,
    KnowledgeGraphManagementService graphManagementService
  ) {
    return new ApplicationContext(
      config,
      null,
      null,
      null,
      graphService,
      graphInitializer,
      null,
      graphManagementService,
      null,
      null
    );
  }

  private TerminalResult runTerminal(String input, ApplicationContext context) throws Exception {
    AppConfig config = context.config();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (Terminal terminal = new DumbTerminal(
      new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
      output
    )) {
      new TerminalStoryteller(terminal, context).run();
    }
    return new TerminalResult(config, output.toString(StandardCharsets.UTF_8));
  }

  private record TerminalResult(AppConfig config, String output) {
  }
}
