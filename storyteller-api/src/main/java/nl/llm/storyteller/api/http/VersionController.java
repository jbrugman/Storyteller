package nl.llm.storyteller.api.http;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.util.Map;
import nl.llm.storyteller.api.ApiVersion;

/** Says which build is answering, so a page can be matched to the artefact serving it. */
public final class VersionController {
  public void register(JavalinConfig config) {
    config.routes.get("/v1/version", this::version);
  }

  private void version(Context context) {
    context.json(Map.of("version", ApiVersion.read()));
  }
}
