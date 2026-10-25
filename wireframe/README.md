# Frontend demo

A standalone static mock of the Java/JTE frontend, including Start, Story,
Settings, History, and Atlas. Open `index.html` or serve this directory:

```sh
python3 -m http.server 8000 --directory wireframe
```

Visit http://localhost:8000. No Java, database, API, or model is required.
Every submitted prompt receives the same example scene about Valerie Thorne,
Merel, and Roos at the beach house on the L’Horizon estate. The scene follows
the characters in the bundled default `fixed_protagonists.yml`.
Story input, image previews, undo, response layout controls, Atlas navigation
and filtering, and all seven themes work locally. Settings and History action
buttons, session resume/import, Infinite, and Export are disabled.
Example settings can be edited for preview but are not saved.
Settings and Atlas read `demo-data.js`: the bundled standard prompts,
protagonist YAML and its parsed profile data, default chat temperature, predicate
catalog, and a single example knowledge graph. Atlas derives its entities and
relations from the same graph shown as JSON in Settings. The sample graph is
demo data, not output from an AI model. Mike and Chris appear as profiles without
graph facts, matching the production behavior for profiles absent from the graph.

The theme is stored in local storage under `storyteller_wireframe_theme`.
The demo story is stored in session storage under `storyteller_wireframe_story`
so navigation preserves it within the current tab. Starting a story resets the
demo conversation. Storage failure does not prevent interaction on the current
page. These keys are independent of the real application's session and theme.

## GitHub Pages

In the repository's **Settings → Pages → Build and deployment**, select
**GitHub Actions** as the source. After merging, a push to `main` changing the
demo or its workflow publishes this directory; the workflow can also be run
manually from Actions. The deployment's URL appears in its GitHub environment.
See [GitHub's Pages workflow documentation](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).

All links are relative so the demo supports repository subpaths.
Only this folder is uploaded to Pages.

## Keeping the mock aligned

The HTML follows `storyteller-api/src/main/resources/templates/` with fictional
data and local demo interactions replacing server actions. `storyteller.css`
and `themes/` are copies of the API's public assets. After production UI changes,
update the corresponding mock HTML and recopy those assets as needed.
`story-theme.js` follows the production theme selector, using separate local
storage instead of the production cookie.
When the bundled defaults change, refresh their copies in `demo-data.js` from
`storyteller-core/src/main/resources/systemprompts/`. Keep `protagonists` equal
to the parsed `fixed_protagonists` mapping in `fixedProtagonistsYaml` and retain
the production graph schema. Atlas presentation follows `SessionAtlasService`
and the JTE templates; edits to Settings are deliberately not applied to Atlas.
