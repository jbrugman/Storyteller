package nl.llm.storyteller.core;

import nl.llm.storyteller.core.config.AppConfigLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class StreamingConfigTest {
  @TempDir Path directory;

  @Test
  @DisplayName("Given the default configuration, When loaded, Then streaming is off and validation is on")
  void shouldPreserveDefaults() throws Exception {
    var config = AppConfigLoader.load(directory, null);
    assertFalse(config.streamingEnabled());
    assertTrue(config.validationEnabled());
  }

  @Test
  @DisplayName("Given streaming enabled in config, When validation is also enabled, Then startup rejects the conflict")
  void shouldRejectConflictingDefaults() throws Exception {
    Path override = directory.resolve("override.config");
    Files.writeString(override, "streaming.enabled=true\nvalidation.enabled=true\n");
    assertThrows(IllegalArgumentException.class, () -> AppConfigLoader.load(directory, override));
    Files.writeString(override, "streaming.enabled=true\nvalidation.enabled=false\n");
    assertTrue(AppConfigLoader.load(directory, override).streamingEnabled());
  }
}
