package nl.llm.storyteller.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a browser {@code Origin} may reach the same-origin {@code /v1} API.
 *
 * <p>A request is allowed when it carries no {@code Origin}, comes from loopback, or is
 * same-origin: the authority (host and effective port) of the {@code Origin} equals the
 * authority the request was addressed to. The {@code Origin}'s scheme is deliberately not
 * part of that comparison. The request has already arrived on this connection, and behind a
 * TLS-terminating proxy the advertised (public) scheme differs from the forwarded one, so
 * demanding it would reject genuine same-origin traffic without stopping a cross-site
 * attacker, whose host always differs.
 *
 * <p>The request authority is read from the request's server name and port (populated from
 * HTTP/2 {@code :authority} or the HTTP/1.1 {@code Host} header) rather than from the raw
 * {@code Host} header, which HTTP/2 omits. That is what makes same-origin work over HTTP/2,
 * on any port, and over both http and https.
 */
final class OriginGuard {
  private static final Pattern LOOPBACK =
    Pattern.compile("https?://(localhost|127\\.0\\.0\\.1|\\[::1])(:[0-9]{1,5})?");

  private OriginGuard() {
  }

  /**
   * @param origin        the {@code Origin} header, may be {@code null}
   * @param requestScheme the scheme the request arrived on, e.g. {@code https}
   * @param requestHost   the authority host the request was addressed to
   * @param requestPort   the authority port the request was addressed to
   * @return whether the request may be handled
   */
  static boolean isAllowed(String origin, String requestScheme, String requestHost, int requestPort) {
    if (origin == null || origin.isBlank()) {
      return true;
    }
    if (LOOPBACK.matcher(origin).matches()) {
      return true;
    }
    Authority requested = parse(origin);
    Authority addressed = requestHost == null ? null : new Authority(requestHost, effectivePort(requestScheme, requestPort));
    return requested != null
      && addressed != null
      && requested.port() == addressed.port()
      && requested.host().equalsIgnoreCase(addressed.host());
  }

  private static Authority parse(String origin) {
    try {
      URI uri = new URI(origin);
      String host = uri.getHost();
      int port = effectivePort(uri.getScheme(), uri.getPort());
      return host == null || port < 0 ? null : new Authority(host, port);
    } catch (URISyntaxException _) {
      return null;
    }
  }

  private static int effectivePort(String scheme, int port) {
    if (port > 0) {
      return port;
    }
    return switch (scheme == null ? "" : scheme.toLowerCase(Locale.ROOT)) {
      case "https" -> 443;
      case "http" -> 80;
      default -> -1;
    };
  }

  private record Authority(String host, int port) {
  }
}
