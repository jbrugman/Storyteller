package nl.llm.storyteller.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The version this server was built from, read from the resource Maven filters at build time.
 * A manifest lookup comes up empty in both supported packagings, the shaded jar drops the manifest and a
 * native image has none, which is exactly where knowing the running build matters most.
 */
public final class ApiVersion {
  private static final String RESOURCE = "/public/version.txt";
  private static final String UNKNOWN = "unknown";
  private static final String VERSION = readVersion();

  private ApiVersion() {
  }

  public static String read() {
    return VERSION;
  }

  private static String readVersion() {
    try (InputStream version = ApiVersion.class.getResourceAsStream(RESOURCE)) {
      if (version == null) {
        return UNKNOWN;
      }
      String value = new String(version.readAllBytes(), StandardCharsets.UTF_8).trim();
      return value.isEmpty() ? UNKNOWN : value;
    } catch (IOException _) {
      return UNKNOWN;
    }
  }
}
