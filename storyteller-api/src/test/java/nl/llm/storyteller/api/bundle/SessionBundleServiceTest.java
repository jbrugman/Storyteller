package nl.llm.storyteller.api.bundle;

import nl.llm.storyteller.db.bundle.SessionBundle;
import nl.llm.storyteller.db.bundle.SessionBundleRepository;
import nl.llm.storyteller.db.SessionPrompts;
import nl.llm.storyteller.db.SessionRecord;
import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;
import nl.llm.storyteller.core.model.HistoryState;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.model.TurnState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionBundleServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-06T16:00:00Z");
  private static final String PNG_DATA_URL = "data:image/png;base64,iVBORw0KGgo=";

  @Test
  @DisplayName("""
    Given a complete CLI-compatible session bundle,
    When it is exported and imported,
    Then every memory file and history cursor should survive the round trip
    """)
  void shouldRoundTripSessionBundle() throws Exception {
    SessionBundle original = new SessionBundle(
      new HistoryState(
        List.of(
          Message.withImage("user", "Open the door", PNG_DATA_URL),
          new Message("assistant", "It opens.")
        ),
        2,
        1,
        2
      ),
      "Long-term summary",
      "Recent summary",
      "currentLocation: library",
      TurnState.inactive(),
      KnowledgeGraphDocument.empty(),
      new SessionPrompts("Story system", "fixed_protagonists: []", "Story rules")
    );
    RecordingRepository repository = new RecordingRepository(original);
    SessionBundleService service = service(repository);

    byte[] archive = service.exportArchive("existing-session", "The Library");
    SessionRecord imported = service.importArchive(
      new ByteArrayInputStream(archive), archive.length, "The Library.zip"
    );

    assertEquals("The Library", imported.title());
    assertEquals("imported-session", imported.sessionId());
    assertFalse(imported.infinite());
    assertEquals(original, repository.importedBundle);
    assertTrue(archiveContains(archive, "memory/images/000.png"));
  }

  private boolean archiveContains(byte[] archive, String expectedName) throws Exception {
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (expectedName.equals(entry.getName())) {
          return true;
        }
      }
    }
    return false;
  }

  @Test
  @DisplayName("""
    Given an older CLI session ZIP without prompt files and with macOS Finder metadata,
    When the session is imported,
    Then metadata should be ignored and missing prompts should use the current defaults
    """)
  void shouldImportLegacyBundleUsingDefaultPrompts() throws Exception {
    RecordingRepository repository = new RecordingRepository(null);
    SessionBundleService service = service(repository);
    byte[] archive = archiveWithMacOsMetadata();

    service.importArchive(new ByteArrayInputStream(archive), archive.length, "Story.zip");

    assertEquals(2, repository.importedBundle.history().messages().size());
    assertEquals(defaultPrompts(), repository.importedBundle.prompts());
  }

  @Test
  @DisplayName("""
    Given a session folder that Finder compressed with its own wrapper folder and metadata,
    When the session is imported,
    Then the wrapper folder should be unwrapped and every bundle file should be read
    """)
  void shouldImportBundleWrappedInFinderFolder() throws Exception {
    RecordingRepository repository = new RecordingRepository(null);
    SessionBundleService service = service(repository);
    byte[] archive = archiveInsideFinderFolder();

    service.importArchive(new ByteArrayInputStream(archive), archive.length, "story-session-finder.zip");

    assertEquals(2, repository.importedBundle.history().messages().size());
    assertEquals("Finder rules", repository.importedBundle.prompts().rules());
    assertEquals("Default system", repository.importedBundle.prompts().systemPrompt());
  }

  @Test
  @DisplayName("""
    Given a compressed session folder containing an entry that escapes that folder,
    When the session is imported,
    Then the entry should still be rejected as unsafe
    """)
  void shouldRejectEscapingEntryInsideWrappedFolder() throws Exception {
    SessionBundleService service = service(new RecordingRepository(null));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
      writeEntry(zip, "story-session-finder/history.json", """
        {"messages":[
          {"role":"user","content":"Begin"},
          {"role":"assistant","content":"Started"}
        ]}
        """);
      writeEntry(zip, "story-session-finder/../escape.json", "escape attempt");
    }
    byte[] archive = output.toByteArray();

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
      () -> service.importArchive(new ByteArrayInputStream(archive), archive.length, "wrapped.zip"));
    assertEquals("Unsafe session ZIP entry: story-session-finder/../escape.json", error.getMessage());
  }

  private byte[] archiveInsideFinderFolder() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
      writeDirectory(zip, "story-session-finder/");
      writeEntry(zip, "story-session-finder/history.json", """
        {"messages":[
          {"role":"user","content":"Begin"},
          {"role":"assistant","content":"Started"}
        ]}
        """);
      writeDirectory(zip, "story-session-finder/systemprompts/");
      writeEntry(zip, "story-session-finder/systemprompts/rules.md", "Finder rules");
      writeEntry(zip, "story-session-finder/.DS_Store", "finder metadata");
      writeEntry(zip, "story-session-finder/._history.json", "finder metadata");
      writeEntry(zip, "__MACOSX/._story-session-finder", "finder metadata");
      writeEntry(zip, "__MACOSX/story-session-finder/._history.json", "finder metadata");
    }
    return output.toByteArray();
  }

  private byte[] archiveWithMacOsMetadata() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
      writeEntry(zip, "history.json", """
        {"messages":[
          {"role":"user","content":"Begin"},
          {"role":"assistant","content":"Started"}
        ]}
        """);
      writeEntry(zip, "__MACOSX/._history.json", "finder metadata");
      writeEntry(zip, ".DS_Store", "finder metadata");
    }
    return output.toByteArray();
  }

  private void writeDirectory(ZipOutputStream zip, String name) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.closeEntry();
  }

  private void writeEntry(ZipOutputStream zip, String name, String content) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private SessionBundleService service(SessionBundleRepository repository) {
    return new SessionBundleService(
      repository,
      Duration.ofHours(1),
      Clock.fixed(NOW, ZoneOffset.UTC),
      () -> "imported-session",
      defaultPrompts()
    );
  }

  private SessionPrompts defaultPrompts() {
    return new SessionPrompts("Default system", "fixed_protagonists: []", "Default rules");
  }

  private static final class RecordingRepository implements SessionBundleRepository {
    private final SessionBundle exportedBundle;
    private SessionBundle importedBundle;

    private RecordingRepository(SessionBundle exportedBundle) {
      this.exportedBundle = exportedBundle;
    }

    @Override
    public SessionBundle load(String sessionId) {
      return exportedBundle;
    }

    @Override
    public void create(SessionRecord session, SessionBundle bundle) {
      importedBundle = bundle;
    }
  }
}
