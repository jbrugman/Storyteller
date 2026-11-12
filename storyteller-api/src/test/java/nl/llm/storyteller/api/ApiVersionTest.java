package nl.llm.storyteller.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiVersionTest {
  @Test
  @DisplayName("Given the packaged version resource, When the version is read, then it is the filtered build version")
  void shouldReportTheBuiltVersion() {
    String version = ApiVersion.read();

    assertTrue(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+.*"),
      "An unfiltered or missing resource would leave a placeholder here, got: " + version);
    assertEquals(version, version.strip(), "The stamp carries no surrounding whitespace");
  }
}
