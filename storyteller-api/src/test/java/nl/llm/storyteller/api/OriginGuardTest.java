package nl.llm.storyteller.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OriginGuardTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  @DisplayName("given no or blank Origin, when guarded, then the request is allowed")
  void allowsMissingOrigin(String origin) {
    assertTrue(OriginGuard.isAllowed(origin, "https", "192.168.1.2", 7443));
    assertTrue(OriginGuard.isAllowed(null, "https", "192.168.1.2", 7443));
  }

  @ParameterizedTest
  @CsvSource({
    "http://localhost:7090, http,  localhost, 7090",
    "https://127.0.0.1,     https, 127.0.0.1, 443",
    "https://[::1]:7443,    https, ::1,       7443",
  })
  @DisplayName("given a loopback Origin, when guarded, then the request is allowed")
  void allowsLoopback(String origin, String scheme, String host, int port) {
    assertTrue(OriginGuard.isAllowed(origin, scheme, host, port));
  }

  @ParameterizedTest
  @CsvSource({
    // The reported case: HTTP/2 sends no Host header; same-origin must still be recognised.
    "https://192.168.1.2:7443,   https, 192.168.1.2, 7443",
    // Default ports: an explicit 443 matches an Origin that omits the port, and vice versa.
    "https://story.example,      https, story.example, 443",
    "https://story.example:443,  https, story.example, 443",
    // Same host on the default http port, and case-insensitive host comparison.
    "http://192.168.1.3:80,      http,  192.168.1.3,   80",
    "https://Story.Example:8443, https, story.example, 8443",
    // The scheme is deliberately ignored, so a TLS-terminating proxy still matches same-origin.
    "http://intranet.example:7443, https, intranet.example, 7443",
  })
  @DisplayName("given a same-origin request, when guarded, then it is allowed regardless of scheme, http version or port")
  void allowsSameOrigin(String origin, String scheme, String host, int port) {
    assertTrue(OriginGuard.isAllowed(origin, scheme, host, port));
  }

  @ParameterizedTest
  @CsvSource({
    // Different host: a cross-origin attacker is always rejected, whatever the scheme.
    "https://evil.example,                https, 192.168.1.2, 7443",
    "http://localhost.evil.example:3000,  http,  127.0.0.1,   3000",
    "http://127.0.0.1.evil.example:3000,  http,  127.0.0.1,   3000",
    // Same host but a different effective port (443 vs 7443).
    "https://192.168.1.2:8443,            https, 192.168.1.2, 7443",
    "https://192.168.1.2,                 https, 192.168.1.2, 7443",
  })
  @DisplayName("given a foreign host or a different port, when guarded, then the request is rejected")
  void rejectsForeignOrigin(String origin, String scheme, String host, int port) {
    assertFalse(OriginGuard.isAllowed(origin, scheme, host, port));
  }

  @ParameterizedTest
  @ValueSource(strings = {"not a uri", "://missing-scheme", "https://", "https://:8443"})
  @DisplayName("given an Origin without a usable host, when guarded, then the request is rejected")
  void rejectsUnparseableOrigin(String origin) {
    assertFalse(OriginGuard.isAllowed(origin, "https", "192.168.1.2", 7443));
  }
}
