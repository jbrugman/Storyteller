@import nl.llm.storyteller.api.web.StorySettingsPage

@param StorySettingsPage page

<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Story settings</title>
    <script src="/story-theme.js"></script>
    <link rel="stylesheet" href="/storyteller.css">
  </head>
  <body>
    <main class="settings-page">
      <header class="story-header">
        <div>
          <p class="eyebrow">Storyteller</p>
          <h1>Story settings</h1>
        </div>
        <a class="header-action" href="/story">Back to story</a>
      </header>

      <section class="panel theme-settings" aria-label="Appearance">
        <label for="theme">Theme</label>
        <select id="theme">
          <option value="default">Default</option>
          <option value="dark-sci-fi">Ski-fi</option>
          <option value="nature">Nature</option>
          <option value="ocean">Ocean</option>
          <option value="passion">Passion</option>
          <option value="summer">Summer</option>
          <option value="evening">Evening</option>
        </select>
        <p class="setting-hint">Applies immediately and is remembered in this browser for one year when cookies are enabled.</p>
      </section>

      <form method="post" action="/story/settings" class="panel settings-form">
        <p class="intro">
          These prompts belong to this session and are used for subsequent story turns and validation.
        </p>

        <label for="systemPrompt">System prompt</label>
        <textarea id="systemPrompt" name="systemPrompt" rows="12" maxlength="1000000"
                  required>${page.prompts().systemPrompt()}</textarea>

        <label for="fixedProtagonists">Fixed protagonists (YAML)</label>
        @if(page.hasYamlError())
          <p id="fixed-protagonists-error" class="settings-error" role="alert"
             data-line="${page.yamlErrorLine()}" data-column="${page.yamlErrorColumn()}">
            Invalid YAML at line ${page.yamlErrorLine()}, column ${page.yamlErrorColumn()}: ${page.yamlError()}
          </p>
        @endif
        <textarea id="fixedProtagonists" name="fixedProtagonists" rows="12" maxlength="1000000"
                  aria-invalid="${page.hasYamlError()}"
                  aria-describedby="${page.hasYamlError() ? "fixed-protagonists-error" : null}"
                  required>${page.prompts().fixedProtagonists()}</textarea>

        <label for="rules">Rules</label>
        <textarea id="rules" name="rules" rows="12" maxlength="1000000"
                  required>${page.prompts().rules()}</textarea>

        <label for="knowledgeGraph">Knowledge graph (JSON)</label>
        @if(page.hasKnowledgeGraphError())
          <p id="knowledge-graph-error" class="settings-error" role="alert">
            ${page.knowledgeGraphError()}
          </p>
        @endif
        <textarea id="knowledgeGraph" name="knowledgeGraph" rows="18" maxlength="1000000"
                  aria-invalid="${page.hasKnowledgeGraphError()}"
                  aria-describedby="${page.hasKnowledgeGraphError() ? "knowledge-graph-error" : null}"
                  required>${page.knowledgeGraph()}</textarea>

        <fieldset class="temperature-fieldset">
          <label for="temperature">Chat temperature</label>
          <p class="setting-hint">
            Controls randomness in the story. Lower values are more deterministic, higher values are more creative.
            Range: 0 (exact) to 2 (maximally diverse).
          </p>
          <div class="temperature-control">
            @if(page.temperature() != null)
              <input type="range" id="temperature" name="temperature" min="0" max="2" step="0.01"
                     value="${page.temperature()}" />
            @else
              <input type="range" id="temperature" name="temperature" min="0" max="2" step="0.01" />
            @endif
            <output id="temperatureValue" for="temperature">
              @if(page.temperature() != null)
                ${String.format("%.2f", page.temperature())}
              @else
                default
              @endif
            </output>
          </div>
        </fieldset>

        <div class="graph-tools" aria-label="Knowledge graph actions">
          <span>Graph tools</span>
          <button class="compact-button" type="submit"
                  formaction="/story/settings/graph/generate-empty"
                  data-graph-action="generate">Generate empty</button>
          <button class="compact-button" type="submit"
                  formaction="/story/settings/graph/fill-fixed-protagonists"
                  data-graph-action="fill">Fill from protagonists</button>
          <button class="compact-button danger-button" type="submit"
                  formaction="/story/settings/graph/reset-turn-based"
                  data-graph-action="reset">Reset turn-based</button>
        </div>

        <div class="settings-actions">
          <button type="submit">Save settings</button>
        </div>
      </form>
    </main>
    @if(page.hasNotification())
      <div id="settings-notification" class="notification-modal" role="alertdialog" aria-modal="true"
           aria-labelledby="settings-notification-title"
           aria-describedby="settings-notification-message">
        <div class="notification-modal-content">
          <p id="settings-notification-title" class="notification-modal-title">${page.notificationTitle()}</p>
          <p id="settings-notification-message">${page.notificationMessage()}</p>
          <button id="settings-notification-close" type="button">Close</button>
        </div>
      </div>
    @endif
    @if(page.hasYamlError())
      <script>
        const yamlEditor = document.getElementById("fixedProtagonists");
        const yamlError = document.getElementById("fixed-protagonists-error");
        const targetLine = Number(yamlError.dataset.line);
        const targetColumn = Number(yamlError.dataset.column);
        const lines = yamlEditor.value.split("\n");
        let offset = 0;
        for (let line = 1; line < targetLine && line <= lines.length; line += 1) {
          offset += lines[line - 1].length + 1;
        }
        const position = Math.min(offset + Math.max(0, targetColumn - 1), yamlEditor.value.length);
        yamlEditor.focus();
        yamlEditor.setSelectionRange(position, Math.min(position + 1, yamlEditor.value.length));
      </script>
    @endif
    <script>
      const settingsNotification = document.getElementById("settings-notification");
      const settingsNotificationClose = document.getElementById("settings-notification-close");
      const closeSettingsNotification = () => {
        if (settingsNotification) {
          settingsNotification.hidden = true;
          window.history.replaceState({}, "", "/story/settings");
        }
      };
      settingsNotificationClose?.addEventListener("click", closeSettingsNotification);
      if (settingsNotification) {
        window.setTimeout(closeSettingsNotification, 8000);
      }
      const settingsForm = document.querySelector(".settings-form");
      const knowledgeGraphEditor = document.getElementById("knowledgeGraph");
      const graphHasData = () => {
        try {
          const graph = JSON.parse(knowledgeGraphEditor.value);
          return Object.keys(graph.entities || {}).length > 0 || (graph.facts || []).length > 0;
        } catch (_) {
          return true;
        }
      };
      settingsForm.addEventListener("submit", event => {
        const action = event.submitter?.dataset.graphAction;
        let warning = "";
        if (action === "reset" && graphHasData()) {
          warning = "Remove all turn-based facts and entities? Fixed and manual graph data will be kept.";
        } else if (action === "fill" && graphHasData()) {
          warning = "The knowledge graph already contains data. Replace it with facts generated from Fixed protagonists?";
        } else if (action === "generate" && graphHasData()) {
          warning = "The knowledge graph already contains data. Replace it with a minimal empty graph?";
        }
        if (warning && !window.confirm(warning)) {
          event.preventDefault();
          return;
        }
        if (event.submitter) {
          event.submitter.disabled = true;
        }
      });
    </script>
    <script>
      const temperatureSlider = document.getElementById("temperature");
      const temperatureValue = document.getElementById("temperatureValue");
      if (temperatureSlider) {
        temperatureSlider.addEventListener("input", () => {
          const val = parseFloat(temperatureSlider.value);
          temperatureValue.textContent = isNaN(val) ? "default" : val.toFixed(2);
        });
      }
    </script>
  </body>
</html>
