# Technical Information

## Requirements

- Java 25+
- Maven
- An OpenAI-compatible chat server with a loaded or selectable model, such as LM Studio, Jan.ai, a self-hosted compatible endpoint, or a hosted compatible API

## Benchmarking

The app includes a fixed 50-turn benchmark that measures both long-term fact retention and validation of binding
fixed-protagonist constraints. It can be repeated with different models, quantizations, and feature combinations.
Specify a model after `/benchmark`, or omit it to use the model already loaded by the backend. Validation and
knowledge-graph injection can be enabled independently so their effects can be compared.

The initial results below include the official Gemma 4 26B A4B model available through LM Studio using its 6-bit MLX
variant, and Gemma 4 12B QAT running on an RTX 3080 Ti with speculative decoding enabled. These results are preliminary
observations from individual runs rather than definitive performance claims.

| Model and runtime | Validation | Cache-buster | Knowledge graph | Facts retained | Validation probes | Total time |
|:---|:---:|:---:|:---:|---:|---:|---:|
| Gemma 4 26B A4B, MLX 6-bit | Off | Off | Off | 20% (1/5) | — | 1m 09s |
| Gemma 4 26B A4B, MLX 6-bit | Off | Off | On | 100% (5/5) | — | 3m 19s |
| Gemma 4 26B A4B, MLX 6-bit | Off | On | Off | 20% (1/5) | — | 1m 48s |
| Gemma 4 26B A4B, MLX 6-bit | On | Off | On | 100% (5/5) | 4/4 | 4m 30s |
| Gemma 4 12B QAT, RTX 3080 Ti with speculative decoding | Off | Off | Off | 0% (0/5) | — | 0m 18s |
| Gemma 4 12B QAT, RTX 3080 Ti with speculative decoding | Off | Off | On | 100% (5/5) | — | 1m 16s |
| Gemma 4 12B QAT, RTX 3080 Ti with speculative decoding | Off | On | Off | 0% (0/5) | — | 0m 24s |
| Gemma 4 12B QAT, RTX 3080 Ti with speculative decoding | On | Off | Off | 0% (0/5) | 2/4 | 0m 52s |
| Gemma 4 12B QAT, RTX 3080 Ti with speculative decoding | On | Off | On | 100% (5/5) | 4/4 | 1m 44s |

In this fixed scenario, knowledge-graph injection was associated with substantially better fact retention. Retention
increased from 20% to 100% for Gemma 4 26B A4B and from 0% to 100% for Gemma 4 12B QAT. These individual runs do not
establish that the same improvement will occur with other models, stories, context sizes, hardware, or repeated runs.

Validation checks explicit rules and fixed-protagonist constraints; it does not recover historical facts that are no
longer present in the model context. It therefore did not improve fact retention when the knowledge graph was disabled.
With knowledge-graph injection enabled, validation corrected all four deliberate constraint violations for both models.
In the separate Gemma 4 12B run without knowledge-graph injection, only two of four validation probes produced usable
corrections. More repeated tests are required before attributing that difference to the knowledge-graph context.

The historical matched cache-buster comparisons showed no quality improvement. For Gemma 4 26B A4B, retention remained at 20%
while total time increased from 1m 09s to 1m 48s. For Gemma 4 12B QAT, retention remained at 0% while total time
increased from 18 to 24 seconds. The cache-buster feature was subsequently removed; the table retains these measurements
to document that decision.

The Gemma 4 12B QAT runs on the RTX 3080 Ti were also considerably faster than the Gemma 4 26B A4B MLX runs on the
Mac. For example, the configuration with validation disabled and knowledge-graph injection
enabled completed in 1m 16s on the RTX 3080 Ti versus 3m 19s on the Mac. This is not a direct hardware benchmark:
the model sizes, quantizations, inference runtimes, and use of speculative decoding differ. It does show that the tested
12B setup on the RTX 3080 Ti delivered substantially shorter benchmark times in practice.

The benchmark reports total wall-clock time and average turn time, but not model generation speed. The generic
OpenAI-compatible response does not provide enough portable timing information to distinguish prompt evaluation,
time to first token, token generation, and request overhead reliably. Use the backend's own generation timing for a
hardware tok/s measurement.

## Recommended setup
See: https://github.com/jbrugman/Assistant/wiki/Configuration-&-Hardware-Guide

Hosted OpenAI-compatible endpoints can use an optional bearer API key in `application.config`:

```properties
backend.type=openai-compatible
backend.http.url=https://example.test/v1/chat/completions
backend.http.apiKey=your-api-key
```

Leave `backend.http.apiKey` blank for local endpoints that do not require authentication. The key is sent as `Authorization: Bearer <key>` and applies to chat and validation, and is also the fallback for memory requests when `memory.http.apikey` is empty. Because configuration values are stored as plain text, keep local configuration files containing real keys out of version control.

For an oMLX server using its default port and required API-key verification, use:

```properties
backend.type=openai-compatible
backend.http.url=http://localhost:8000/v1/chat/completions
backend.http.apiKey=your-omlx-api-key
```

## Used Tools / Hardware

- [Aider](https://github.com/Aider-AI/aider) for code generation and refactoring using a local llm-server
- [LM Studio](https://lmstudio.ai/docs/developer) for model management, local serving, and OpenAI-compatible API access
- [Jan](https://www.jan.ai/docs) for compatibility testing
- [ChatGPT](https://chatgpt.com/) for discussing design decisions and improving system prompts
- [mlx-community/gemma-4-26B-A4B-it-qat-6bit](https://huggingface.co/mlx-community/gemma-4-26B-A4B-it-qat-6bit) for local story-model testing
- [Qwen/Qwen3-Coder-30B-A3B-Instruct](https://huggingface.co/Qwen/Qwen3-Coder-30B-A3B-Instruct) as a local coding model during development
- [llama.cpp](https://github.com/ggml-org/llama.cpp) for running GGUF models through the managed local llama-server backend
- [mlx-vlm](https://github.com/Blaizzy/mlx-vlm) for running MLX models through the managed local `mlx_vlm.server` backend
- MacBook Pro with M1 Max and 64 GB unified memory

## Packaging Behavior

The app ships with working built-in defaults.

Bundled defaults live in:
- `storyteller-core/src/main/resources/systemprompts/`

Those files are compiled into:
- the runnable jar
- the native executable

The native build explicitly includes `systemprompts/**` as classpath resources, so the bundled defaults remain available even when no local override files exist.

At initialization, local filesystem overrides take precedence when present:
- `./systemprompts/application.config`
- `./systemprompts/*.md`
- `./systemprompts/*.yml`
- `./systemprompts/*.json`

So the behavior is:
1. use bundled defaults from the build artifact
2. override them with local files in `./systemprompts/` when they exist
3. initialize the CLI's fixed default database session from those values
4. persist live story state through `storyteller-db` in H2

For `systemprompt.md`, `fixed_protagonists.yml`, and `rules.md`, the resolved resource/file values seed a newly created
session only. After initialization, the database session is the live source for those prompts and its memory. Changes
to these three files do not overwrite values already stored for an existing session. Other internal prompt templates
continue to be read from the configured files or bundled resources because they are application templates rather than
editable session state. Files under `memory/` remain
the default location for the H2 database, migration input, TLS material, and explicit export artifacts; individual
Markdown, YAML, and JSON memory files are no longer the CLI's live persistence mechanism.

## Run Locally

### Recommended

```bash
cd ~/Assistant
mvn -q package
java -jar storyteller-cli/target/storyteller-cli-2.0.9-all.jar
```

The CLI jar does not contain Javalin, Jetty, or the API implementation. It does include H2 and the shared JDBC
repositories through the `storyteller-db` module. Run the independent API application with:

```bash
java -jar storyteller-api/target/storyteller-api-2.0.9-all.jar
```

Alternatively, start the API directly through Maven from the project root:

```bash
mvn -pl storyteller-api -am exec:java \
  -Dexec.mainClass=nl.llm.storyteller.api.ApiApplication
```

The CLI and API are separate applications with separate entry points. Both depend on `storyteller-core`; neither
application contains the other. The API additionally uses `storyteller-db` and owns its own `application.config`.
API and web session history, prompts, derived memory, turn state, knowledge graph, and images are stored in H2; ZIP
bundles are portable imports, exports, and backups rather than live session storage.

Screenshots of the server-rendered web interface are available in [`docs/webpages`](webpages-screenshots/).
The web interface can disable inactivity expiration for the active story with **Infinite** and restore the configured
session timeout with **Use timeout**.
For a vision-capable model, paste an image directly into the story input or select **Add image**, add the accompanying
instruction, and submit the turn. A preview is shown before submission and a thumbnail remains attached to the stored
prompt afterward. PNG and JPEG uploads up to 5 MiB are accepted. The browser automatically
resizes PNG and JPEG images to fit within 1024 × 768 pixels, retaining their
aspect ratio. JPEG encoding starts at 80% quality and may decrease to 60% to aim for
500 KiB; PNG screenshots retain lossless encoding. The target is not a hard storage limit.
The server also checks upload dimensions. Desktop clipboard images fit within the same bounds and remain PNG.
New GIF and WebP uploads are not accepted; existing images in these formats remain readable.
Existing stored images and session bundles retain the previous 10 MiB compatibility limit.
For details that have fallen outside the normal recent-turn context, the web interface can attach up to three older
prompt-and-response exchanges to the next turn as explicitly marked past story context. Only exchanges outside the
configured recent context are selectable. The selection is used once and is not added to the stored story history.
The start page can import a CLI-compatible session ZIP containing `history.json` and any available summary,
canonical-state, turn-state, knowledge-graph, and session prompt files. **Export** downloads the active web session in
the same portable format, including a versioned manifest that preserves the story title. **Settings** edits the active
session's system prompt, fixed protagonists, rules, and validated knowledge graph. These values are stored in H2 and
used from the next turn onward.
The **History** page shows the read-only mid-term and long-term summaries and canonical state. After eligible story
turns, the API refreshes those values in the background and stores their content and processing cursors in H2.

#### Session ZIP import and export

Use `/export -zip` in the CLI to create a portable `story-session-<timestamp>.zip`. The same format can also be
downloaded from an active story in the web interface by selecting **Export**. The archive contains:

- `manifest.json` with the bundle format version and story title
- `history.json` with all user and assistant messages and the three memory cursors
- `memory/images/<message-index>.<extension>` for images attached to prompts, referenced from `history.json`
- `turn-state.json`
- `knowledge-graph.json`
- `summary.md`, `recent-summary.md`, and `canonical-state.yaml` when those values exist
- `systemprompts/systemprompt.md`, `systemprompts/fixed_protagonists.yml`, and `systemprompts/rules.md`

To restore it, stop or leave the current web session, select the ZIP under **Import CLI session (ZIP)** on the start
page, and submit the form. Import validates the complete archive, creates a new database session with a new ID, and
then opens that session. It does not overwrite an existing session. The three prompt files are optional during import;
missing files use the current server defaults. The other CLI `/export` modes continue to produce Markdown files.

After creating a session, submit a story prompt through `POST /v1/sessions/{sessionId}/turns` with a JSON body such as
`{"prompt":"Continue into the forest."}`. The response contains the generated story text and the persisted user and
assistant message indices.

The local default build version is `2.0.9`.
GitHub releases use automatic patch versioning on every eligible push to `main` within the active `v2.0.x` release line,
starting with `v2.0.0` and incrementing the patch number for later releases.
Eligible pushes to `main`, including normal merges from pull requests, automatically build a release jar and publish it to GitHub Releases.
Merges of Dependabot pull requests and pull requests whose source branch is `norelease` or starts with `norelease/` still run CI, but intentionally skip release publication.

### Native Build

```bash
cd ~/Assistant
mvn -pl storyteller-cli -am -Pnative -DskipTests package
```

The native binary is normally written to:

```text
storyteller-cli/target/storyteller
```

The native build enables GraalVM shared-arena support for JLine's Java FFM terminal provider.

Build the independent API native executable with:

```bash
mvn -pl storyteller-api -am -Pnative-api -DskipTests package
```

That executable is written to `storyteller-api/target/storyteller-api` and starts the HTTP server automatically.

Run either native application from the project root:

```bash
cd ~/Assistant
./storyteller-cli/target/storyteller
# or
./storyteller-api/target/storyteller-api
```

If an `application.config` file exists next to the native executable, it is loaded as an additional runtime override.

### Development Run

```bash
cd ~/Assistant
mvn -q -pl storyteller-cli -am package
java -jar storyteller-cli/target/storyteller-cli-2.0.9-all.jar
```

## Terminal Shortcuts

- `Ctrl-G`: sends `(continue the story)`
- `Ctrl-W`: sends a transient reset instruction that tells the model to strictly follow the active story rules again
- `Ctrl-U`: removes the last persisted turn, sends a transient reset instruction, and restores your last prompt in the input buffer so you can edit and retry it
- `Ctrl-L`: shows the last persisted user prompt and assistant reply without sending anything to the model

On macOS, `Cmd-G`, `Cmd-W`, `Cmd-U`, and `Cmd-L` only work if the terminal forwards those key combinations as meta or escape input.

The `Ctrl-W` / reset turn is treated as a control action rather than as a normal story turn:
- its prompt and response are not appended to the H2 story history
- it does not trigger summary, recent-summary, or canonical-state refreshes

Set `graph.enabled=false` to disable both graph injection and automatic turn-based graph extraction. This is the same
core switch used by the benchmark; `validation.enabled` controls validation in the same way.

The `Ctrl-U` / undo-and-retry action builds on that reset flow:
- it removes the last user+assistant turn from the H2 story history
- it clamps the summary, recent-summary, and canonical-state cursors to the shortened history
- it sends the normal transient reset instruction after the removal
- it restores your previous user prompt in the terminal input buffer so you can revise it before sending it again

The `Ctrl-L` action is read-only:
- it does not contact the model
- it does not modify the H2 story history
- it simply prints the latest persisted user+assistant turn so you can quickly see where you left off

## Commands

- `/exit` or `/quit`: leave the application
- `/image <instruction>`: read a copied image from the macOS or Windows desktop clipboard, persist it with the prompt
  in the current H2 session, and send it with the instruction to a vision-capable backend
- `/export`: export the story as Markdown with user prompts in italic
- `/export -intro`: same as `/export`, with user prompts in italic between story sections
- `/export -clean`: export only assistant story output
- `/export -all`: export user prompts and assistant output chronologically with explicit headings
- `/graph`: display the current knowledge graph without calling the model
- `/graph -generate`: create and immediately load a minimal empty graph without calling the model
- `/graph -fill`: generate, validate, persist, and immediately load a graph from the configured fixed protagonists using the model
- `/graph -reset`: remove only entities and facts whose source is `TURNBASED`
- `/benchmark [-<model>]`: run the fixed, isolated 50-turn retention benchmark with the requested model, or the model already loaded by the backend when omitted

The benchmark never reads or changes the normal story history or knowledge graph. Its defaults are fixed at seed `42`, temperature `0`, top-k `1`, top-p `1`, two recent turns, at most 128 generated tokens for story and validation requests, and at most 2048 tokens for graph JSON extraction. The English-only scenario tests `LIVES`, `TRUSTS`, and changing `WEARS` facts supported by the graph ontology. When supplied, the model name after `/benchmark` is sent as the chat and validator model. Without it, no model override is sent and the backend uses its already loaded model; `model.chat` from `application.config` is not substituted. A managed MLX benchmark reuses the already running server, so an explicitly selected model must already be the configured managed MLX model. Start it with `--max-kv-size 4096` and a server output ceiling of at least `--max-tokens 2048`; individual story requests remain capped at 128 tokens. An OpenAI-compatible remote endpoint controls its own context and server output limits.

Every run writes an English audit report under `benchmark-results/`. It records each probe, the original draft, the final response after the normal validator, expected or forbidden assertions, pass/fail status, whether validation replaced the draft, and graph revision/entity/fact counts. Fact-retention probes remain separate from adversarial validation probes. The latter use a binding fixed-protagonist constraint that Alice is romantically and sexually attracted only to women, plus an explicit rule that fixed-protagonist world state must be preserved, and then attempt to introduce attraction toward named men. The real validator receives those normal rules and fixed-protagonist data. The summary counts replacements across all turns; improvements and regressions count replacements on scored probes where correctness can be determined. A validation-probe `REPLACE` counts as useful when it retains Alice and removes the forbidden assertion; it does not need to retain the named man from that forbidden assertion. Rejected graph updates are also reported instead of silently presenting an empty graph as a model retention failure.

`Graph updates rejected` counts candidate graph updates that Java refused to persist. A rejection is atomic: the complete candidate update is discarded and the previously stored graph remains unchanged. Typical reasons include invalid JSON, missing required fields, invalid identifiers, unknown entity references, type-incompatible relationships, contradictions, and predicates that are not present in the configured predicate catalog. For example, when a model emits an unsupported `DESIRE` predicate, that predicate is never added to the graph; the rejected-update counter and audit reason record the blocked attempt.

Each feature can be measured independently:

```text
/benchmark --validation=off --knowledge-graph=on
```

```text
/benchmark -google/gemma-4-26b-a4b --validation=off --knowledge-graph=on
```


Use `--turns=10` through `--turns=100` to change the fixed run length. Validation and knowledge-graph injection default
to `on`. Run the command repeatedly with one switch changed at a time for comparable results.

Exports are written as Markdown files in the application working directory.

## Runtime Files

### Bundled defaults

These are compiled into the app from `storyteller-core/src/main/resources/systemprompts/`:
- `application.config`
- `systemprompt.md`
- `rules.md`
- `fixed_protagonists.yml`
- `fixedprotagonistscontext.md`
- `summarysystemprompt.md`
- `summarycontext.md`
- `recentsummarysystemprompt.md`
- `recentsummarycontext.md`
- `canonicalstatesystemprompt.md`
- `canonicalstatecontext.md`
- `validationsystemprompt.md`
- `validationrequesttemplate.md`
- `turnviolationsingletemplate.md`
- `turnviolationpartytemplate.md`
- `graph-predicates.json`

### Optional local overrides

If you create a local `systemprompts/` folder in the working directory with files of the same names, those files override the bundled defaults.

Example override folders are included under [`configs.example/`](../configs.example), including mode-specific examples such as [`cowriter_story`](../configs.example/cowriter_story) and [`dungeons_dragons`](../configs.example/dungeons_dragons).
Those examples show complete prompt sets, fixed protagonists, and runtime defaults for different storytelling modes.

The Dungeons & Dragons example also demonstrates the optional engine-level turn-based game mode.
When enabled, the app itself tracks round participation and can inject penalty instructions into the prompt when a protagonist or the whole party tries to take an extra move before the round is complete.

### Runtime memory

The CLI, API, and web interface persist live story state through `storyteller-db` in the shared H2 database configured
by `database.path` / `api.database.path`. This includes messages, summary cursors and contents, canonical state, turn
state, and the knowledge graph. Without a session argument, the CLI uses infinite session ID
`00000000-0000-0000-0000-000000000001`, which can be resumed from the web start page. Passing
`--session <session-id>` opens an existing API or web session from the same configured database instead. The API must
be stopped first because embedded H2 permits only one process to own the database.

When that CLI database session is created for the first time, existing `memory/history.json`, summary, canonical-state,
turn-state, and knowledge-graph files are imported transactionally. They are retained as migration input but are no
longer updated as live CLI state. Session ZIP and Markdown files remain explicit export artifacts.

### Knowledge graph MVP

LM Studio and oMLX memory requests use the configured OpenAI-compatible endpoint with reasoning disabled. oMLX is
detected from `/v1/models` and additionally receives `chat_template_kwargs.enable_thinking=false`. Story generation
and validation retain their configured reasoning behavior.

Version 1.2.0 introduced a small knowledge graph for mitigation of entity contagion and feature bleeding.
The active graph is validated and stored in H2. For the CLI, a legacy `memory/knowledge-graph.json` configured through
`file.knowledgeGraph` is read only during the one-time migration into its database session.
When the current user input or candidate response mentions an entity name or alias, its active facts are injected into both the story and validation prompts. Hard manual and fixed-protagonist facts are labeled authoritative. Model-generated turn-based facts are labeled as lower-confidence context and may never override authoritative facts.
Missing files and turns without matching entities preserve the existing behavior.

The graph ontology is closed at runtime but configurable before startup. The bundled catalog supports:

- entity types `CHARACTER`, `ITEM`, `SKILL`, and `LOCATION`;
- bundled predicates for possessions, worn clothing (`WEARS`), skills, interpersonal relationships, cohabitation (`LIVES_WITH`), and residence (`LIVES`, `CHARACTER` to `LOCATION`);
- positive and negative active facts, with absent facts resolving to `UNKNOWN`;
- source metadata on both entities and facts, plus statuses, aliases, revision and schema metadata;
- strict entity-reference, predicate-type, duplicate, and contradiction validation;
- immutable in-memory indexes for entity, alias, subject, object, and truth lookup;
- validated persistence through the database-backed `KnowledgeGraphRepository` abstraction;
- reflection-free graph JSON encoding and decoding for GraalVM native images;
- bounded name/alias retrieval and prompt grounding for active hard facts.

Predicate definitions are data-driven through `systemprompts/graph-predicates.json`. A local file with that name replaces the bundled catalog, so it must contain every predicate that should remain available. Each predicate configures its permitted subject and object entity types, temporal flag, and positive and negative prompt text. Facts store the stable predicate ID as a string, so adding a predicate requires no Java enum or formatter change. The same catalog drives graph validation, prompt rendering, and the allowed-predicate instructions for `/graph -fill`.

The bundled relationship predicates are `LOVES`, `TRUSTS`, `HATES`, `PROTECTIVE_OF`, `FRIENDS_WITH`, `TRAINS_WITH`, `FEELS_SAFE_WITH`, and `LIVES_WITH`. `LIVES_WITH` connects two characters; `LIVES` connects a character to a `LOCATION`, such as a villa, penthouse, or house. Relationship facts are directional unless both directions are explicitly present.

`WEARS` connects a `CHARACTER` to an `ITEM` and is temporal. Multiple garments are represented as separate item entities and facts, rather than an array-valued fact. For example, a blue pair of jeans and a shirt produce two `ITEM` entities and two `WEARS` facts. On a clothing change, automatic extraction returns the character's complete resulting outfit. That snapshot replaces only the character's previous `TURNBASED` `WEARS` facts; manual and fixed-protagonist clothing remains protected. Automatically generated garment entities are removed when the replacement leaves them unreferenced.

Example custom predicate:

```json
{
  "predicates": {
    "MENTORS": {
      "subjectType": "CHARACTER",
      "objectType": "CHARACTER",
      "temporal": false,
      "positiveText": "mentors",
      "negativeText": "does not mentor"
    }
  }
}
```

Facts reference the configured predicate by its string ID. `sourceTurn` is optional for fixed-protagonist facts and is only meaningful when a fact originates from a numbered user or assistant turn:

```json
{
  "id": "valerie-lives-lhorizon",
  "subject": "valerie_thorne",
  "predicate": "LIVES",
  "object": "villa_lhorizon",
  "polarity": "POSITIVE",
  "status": "ACTIVE",
  "source": "FIXED_PROTAGONIST",
  "hard": true
}
```

Every entity also has a `source`. Fixed-protagonist generation assigns `FIXED_PROTAGONIST`; automatic extraction from completed story turns assigns `TURNBASED` to every generated entity and fact. TURNBASED facts are always normalized to `hard=false` and therefore carry less weight than hard manual or fixed-protagonist facts.

Automatic graph extraction runs after every `graph.turnBased.batchTurns` completed turns. The default is `3`. Only that latest batch of complete user-assistant turns is sent for extraction, and the resulting candidate is merged atomically. Existing non-TURNBASED entities and facts are protected from replacement or contradiction.

Turn-based relationship extraction is deliberately conservative. An interaction such as talking, flirting, kissing, sex, cooperation, shared time, or momentary affection is not sufficient evidence for an enduring relationship such as `FRIENDS_WITH`, `LOVES`, or `TRUSTS`. The completed turns must explicitly establish the relationship; otherwise the extractor omits it.

The CLI provides four graph management commands. They are local control commands and are never recorded as story turns:

- `/graph` displays the current graph without calling the model;
- `/graph -generate` creates, persists, and immediately publishes a minimal empty graph document without calling the model;
- `/graph -fill` sends only the complete configured `fixed_protagonists.yml` content to the model, validates and normalizes the returned closed-ontology graph in Java, persists it through the active repository, and immediately publishes the new snapshot;
- `/graph -reset` atomically removes only TURNBASED entities and facts while preserving fixed-protagonist, manual, and other sourced data.

`/graph -generate` and a successful `/graph -fill` replace the existing graph in the current database session.
`/graph -fill` forces extracted entities and facts to `FIXED_PROTAGONIST`, with facts set to `ACTIVE` and `hard=true`;
Java controls schema version and revision. Invalid model JSON or a graph validation failure leaves the persisted graph and
active snapshot unchanged. Fill generation and automatic turn-based extraction use the same sequential derived-memory
queue, preventing either operation from overwriting a graph update produced concurrently by the other. Because
`/graph -fill` waits for its queued result, earlier summary, canonical-state, or graph work may delay the command.
Automatic turn-based extraction is best-effort and never fails the completed foreground story turn; a candidate based
on a stale graph revision is skipped and is not reported as successfully persisted.

## Configuration
See: https://github.com/jbrugman/Assistant/wiki/Configuration-&-Hardware-Guide

Set `graph.turnBased.batchTurns` to the number of completed story turns per automatic graph update. It must be at least `1` and defaults to `3`.

## Prompt Assembly

The app does not send the full history back to the model. It sends:
- one combined first `system` message containing:
  the main system prompt
  fixed protagonists
  canonical state
  long-term summary
  recent summary
  relevant knowledge-graph facts
- the last `chat.maxRecentTurns` raw turns
- the latest user message

This single-system-message layout improves compatibility with stricter OpenAI-compatible chat templates, including LM Studio model templates that require the system message to appear only at the beginning of the conversation.

The same compatibility rule is also applied to the three background derived-memory updates:
- long-term summary refresh
- recent summary refresh
- canonical state refresh

Those background requests now also use one combined first `system` message followed by one `user` message.

## Memory Layers

The storyteller uses three database-backed derived memory layers beside the latest raw turns:

- recent summary: the middle history layer
- long-term summary: background and continuity
- canonical state: compact confirmed canon

These values and their processing cursors are stored in the active H2 session. Files named `recent-summary.md`,
`summary.md`, and `canonical-state.yaml` are used only as legacy migration input or as entries in a session ZIP.

This keeps prompt size down while preserving continuity.

Important runtime detail:
- the foreground story turn stays synchronous for prompt assembly, model response, validation, and history append
- the three derived-memory refreshes are triggered asynchronously afterward
- the three memory refreshes, automatic turn-based graph extraction, and manual `/graph -fill` generation share one single-threaded task queue, so their LLM calls run sequentially; background work does not block the current story response, while `/graph -fill` deliberately waits for earlier queued work and its own result

## Runtime Structure

This remains one Maven project with separate CLI and API distributions. Its modules enforce the main runtime boundaries:

- `storyteller-core`: configuration, story behavior, prompting, validation, derived-memory logic, backend integration,
  domain models, and repository abstractions
- `storyteller-db`: H2 schema, JDBC repositories, database transactions, and portable session bundle records
- `storyteller-cli`: JLine, terminal input, shortcuts, command handling, and terminal rendering
- `storyteller-api`: Javalin controllers, JTE pages, HTTP/TLS lifecycle, API configuration, and web assets

Both interface modules depend on Core and DB; neither contains the other. Core has no dependency on JLine, Javalin,
JTE, JDBC, or H2. This keeps story behavior reusable while allowing the CLI and API to be built and distributed
independently.

The runtime responsibilities are now split more explicitly:
- [`AssistantApp.java`](../storyteller-cli/src/main/java/nl/llm/storyteller/cli/AssistantApp.java): minimal CLI entrypoint and resource lifecycle
- [`ApplicationFactory.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/ApplicationFactory.java): assembles the reusable core dependency graph
- [`OpenAiCompatibleClientFacade.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/OpenAiCompatibleClientFacade.java): Responses-first facade with cached Chat Completions fallback
- [`ManagedLlamaServer.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/ManagedLlamaServer.java): optional local llama-server process lifecycle and readiness handling
- [`ManagedMlxServer.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/ManagedMlxServer.java): optional local mlx-vlm process lifecycle and readiness handling
- [`TerminalStoryteller.java`](../storyteller-cli/src/main/java/nl/llm/storyteller/cli/TerminalStoryteller.java): JLine input loop, shortcuts, command handling, and UI error policy
- [`TerminalRenderer.java`](../storyteller-cli/src/main/java/nl/llm/storyteller/cli/TerminalRenderer.java): terminal formatting, wrapping, banners, and user-visible messages
- [`StorySessionService.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/StorySessionService.java): prompt assembly, model call, validation, history append, and derived-memory refresh triggering
- [`PromptAssemblyService.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/PromptAssemblyService.java): coordinates prompt building from prompts, memory, and recent turns

Prompt responsibilities are now split more explicitly:
- [`PromptLoader.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/PromptLoader.java): loads session prompts and fixed prompt resources
- [`PromptTemplateService.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/PromptTemplateService.java): formats reusable prompt fragments
- [`StoryChatPromptBuilder.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/StoryChatPromptBuilder.java): builds the main storyteller chat stack
- [`ValidationPromptBuilder.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/ValidationPromptBuilder.java): builds validator system and user payloads
- [`SummaryPromptBuilder.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/SummaryPromptBuilder.java), [`RecentSummaryPromptBuilder.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/RecentSummaryPromptBuilder.java), and [`CanonicalStatePromptBuilder.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/CanonicalStatePromptBuilder.java): build the three derived-memory update prompts

Those builders now take small prompt-input records from [`storyteller-core/src/main/java/nl/llm/storyteller/core/model`](../storyteller-core/src/main/java/nl/llm/storyteller/core/model), so prompt inputs are explicit instead of being passed around as long ordered `String` argument lists.

Configuration follows the same separation:
- [`AppConfigLoader.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/config/AppConfigLoader.java) and [`AppConfigSource.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/config/AppConfigSource.java): loading, merging, and path resolution
- [`AppConfig.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/config/AppConfig.java): validated runtime settings only

Graph responsibilities are separated as well:
- [`PredicateCatalog.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/graph/PredicateCatalog.java): immutable, configuration-driven predicate definitions
- [`KnowledgeGraphValidator.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/graph/KnowledgeGraphValidator.java): entity, predicate-type, reference, duplicate, and contradiction validation
- [`ReadOnlyKnowledgeGraphService.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/graph/service/ReadOnlyKnowledgeGraphService.java): automatic snapshot refresh, entity resolution, and authority-aware fact rendering
- [`KnowledgeGraphManagementService.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/graph/service/KnowledgeGraphManagementService.java): selective TURNBASED reset
- [`TurnBasedKnowledgeGraphService.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/graph/turnbasedservice/TurnBasedKnowledgeGraphService.java): configured turn batching, extraction, source normalization, and protected merge
- [`KnowledgeGraphRepository.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/graph/persistence/KnowledgeGraphRepository.java): storage-independent Core contract
- [`KnowledgeGraphStore.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/graph/persistence/KnowledgeGraphStore.java): crash-safe file-backed implementation used for legacy and isolated file-based contexts
- [`JdbcKnowledgeGraphRepository.java`](../storyteller-db/src/main/java/nl/llm/storyteller/db/JdbcKnowledgeGraphRepository.java): active H2-backed implementation used by persisted CLI and API sessions
- [`KnowledgeGraphJsonCodec.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/graph/persistence/KnowledgeGraphJsonCodec.java): reflection-free JSON I/O for JVM and native-image builds

The architecture is documented with simplified system diagrams and focused diagrams per module:

- [system component design](architecture/component-design.puml)
- [simplified system flow](architecture/flow-design.puml)
- [CLI component design](architecture/cli/component-design.puml)
- [CLI flow design](architecture/cli/flow-design.puml)
- [API and web component design](architecture/api/component-design.puml)
- [API and web flow design](architecture/api/flow-design.puml)
- [Core component design](architecture/core/component-design.puml)
- [Core flow design](architecture/core/flow-design.puml)
- [Database component design](architecture/db/component-design.puml)
- [Database persistence flow](architecture/db/flow-design.puml)
- [Database relational schema](architecture/db/schema-design.puml)

Validation is also split into focused parts:
- [`ValidationClient.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/ValidationClient.java): sends the validator prompt to the configured model
- [`ValidationDecisionParser.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/ValidationDecisionParser.java): extracts `ALLOW` or `REPLACE` from structured or plain-text validator output
- [`ValidationOutcome.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/model/ValidationOutcome.java): compact validation decision model with small decision helpers
- [`ResponseSanitizer.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/ResponseSanitizer.java): cleans visible JSON-style escapes before terminal output
- [`ResponseGuard.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/ResponseGuard.java): coordinates those parts, including validator-provided replacement text when a rewrite is needed

LLM backend resilience is handled separately:
- [`ResilientChatClient.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/ResilientChatClient.java): wraps a `ChatClient` with fail-fast cooldown behavior
- [`LlmBackendGuard.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/LlmBackendGuard.java): tracks repeated failures and temporarily opens a cooldown window after the configured threshold

The three derived-memory updaters now share one common infrastructure layer:
- [`DerivedMemoryTaskQueue.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/DerivedMemoryTaskQueue.java): shared sequential execution and worker lifecycle
- [`DerivedMemoryManager.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/DerivedMemoryManager.java): per-manager concurrency guard, model-call flow, and safe write-back coordination
- [`SummaryManager.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/SummaryManager.java), [`RecentSummaryManager.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/RecentSummaryManager.java), and [`CanonicalStateManager.java`](../storyteller-core/src/main/java/nl/llm/storyteller/core/service/CanonicalStateManager.java): their own cutoff rules and prompt contents

Those background memory refreshes are asynchronous by design:
- `StorySessionService` triggers them after the current turn has already been appended to history
- all managers submit to one daemon-backed single-thread queue, preventing concurrent background calls to the LLM backend
- if a background refresh fails, the current user-facing turn still completes normally

## Example usage

The most effective way to use the app is to steer the story with compact, concrete scene instructions. The system prompt, fixed protagonists, canonical state, and validator then help keep the output aligned with the world and character constraints you defined.

```text
  > It is Saturday morning. Mark wakes up after another restless night. The past week was filled with difficult customer complaints. He feels mentally exhausted. Write the next scene from Mark's perspective. His mild headache subtly influences his observations, thoughts and reactions.
```

or:

```text
  > It is Saturday morning. Mark wakes up after another restless night. The past week has been mentally exhausting. The scene is experienced entirely from Mark's perspective. His headache should naturally influence his mood and concentration.
```

That kind of prompt might return something like:

```text
The light behind the curtains is thin, but it still finds me. It presses straight through my eyelids and into the dull ache behind my forehead. For a moment I stay flat on my back, one arm over my eyes, hoping the headache will settle if I refuse to move.

It doesn't.

My phone is on the nightstand. I don't need to pick it up to know what's waiting there. More messages. More follow-up questions. More people treating a complicated system like a vending machine that failed to drop the right snack. Even thinking about the inbox makes my jaw tighten.

I turn my head toward the window too quickly and regret it at once. The room tilts for half a second, just enough to make me close my eyes again. Great. Excellent start.

Somewhere deeper in the house, a door closes softly. Pipes murmur in the walls. The estate is awake in that careful, muted way it always is, as if the building has agreed never to embarrass itself before noon. Normally I like that. Normally it feels controlled. This morning it just makes me aware of how noisy my own head is.

I sit up slowly. The edge of the bed is cold against my legs. My laptop is still open on the desk where I left it, dark screen tilted toward the chair, charger trailing across the floor like an accusation. I try to remember whether I actually solved anything yesterday or just kept moving tasks from one column to another until midnight felt like progress.

The answer comes with another pulse behind my eyes.

Not yet.
```

## Changelog
### 2.1.2
- Renamed Dark sci-fi to Ski-fi and added Passion, Summer, and Evening themes in both web frontends.
- Added a searchable story atlas combining Fixed protagonists profiles with knowledge-graph facts and linked entries in both frontends.

### 2.1.1
- Added themes. Choose default (brown), Dark ski-fi (black-neon), nature (green) or ocean (blue).

### 2.1.0
- Added remaining missing API endpoints so external clients can fully manage story sessions, including memory and graph.
- Added a small react-based front-end web interface example to demonstrate the API endpoints and provide a simple web-based story interface.

### 2.0.13
- Dependency updates.
- Some code cleanup. (No functional changes).

### 2.0.12
- Added the option to use MySQL as database backend instead of the default H2. 
- Added a more elaborated example in the fixed_protagonists.yml file to show how to define a more complex story world with multiple characters, locations, and items.
- Optional encrypt your database content using  `database.encryption.key` in the `application.config` file.

### 2.0.11
- Improved mid-term memory and canonical state so the data is not repeated over the knowledge base and summary.
- Added a new dial to set the chat temperature for your story, so the LLM gets more or less creative in its responses. 
  The dial is available in the web interface and the API, and can be set in the `application.config` file with `chat.temperature`

### 2.0.10
- Added reset buttons for mid-term memory, long-term memory, and canonical state to the memory screen.
  Each button clears the respective memory section and resets its cursor to zero, allowing the user
  to start fresh with derived memory generation.

### 2.0.9
- Added a simple web/API action to edit a persisted assistant response text directly in H2. The edit is a direct user
  change: it does not call the model, run validation, apply story rules, or refresh derived memory/knowledge graph data.

### 2.0.8
- Background-memory clients now detect oMLX through the configured server's `/v1/models` response and add
  `chat_template_kwargs.enable_thinking=false`; `reasoning_effort=none` alone does not disable Gemma 4 thinking in
  oMLX. Detection uses the independently configured memory server.
- Added optional per-request model-usage logging for chat, validation, long-term memory, short-term memory, canonical
  state, turn-based knowledge-graph extraction, and manual knowledge-graph fill. Enable it with
  `logging.modelUsage=true`; each completed request reports the output-token and thinking-token counts returned by the
  model server without logging story content.

### 2.0.7
- Added compact Story settings actions to generate a minimal empty graph, rebuild the graph from the saved
  **Fixed protagonists**, or remove only turn-based facts and entities. Graph actions first validate and save the
  submitted settings, and warn before replacing or removing existing graph data.
- Fixed-protagonist graph generation now identifies the saved definitions as the complete source to process, preventing
  models from incorrectly waiting for separate story context.
- Resetting turn-based graph data now forces one graph extraction after the next completed turn, including when undo
  leaves fewer turns than the configured extraction batch size.
- Renamed `PromptResourceLoader` to `PromptLoader` to reflect that story prompts may come from the active H2 session,
  while fixed prompt templates may still come from configured files or bundled resources.

### 2.0.6
- Added a Responses-first OpenAI-compatible facade with separate route packages and a per-endpoint cached Chat
  Completions fallback for backends that return HTTP 404, 405, or 501 from `/v1/responses`. Chat and memory backends
  are detected independently when they use different URLs.
- Prevent turn-based graph extraction from treating travel, visits, or temporary location as `LIVES` or `LIVES_WITH`;
  residence remains fixed/manual graph data, while current location remains canonical state.

### 2.0.5
- LM Studio background-memory requests now use the normal OpenAI-compatible `/v1/chat/completions` endpoint with
  `reasoning_effort: "none"`; the separate native client and memory-transport configuration have been removed, while
  story generation and validation remain unchanged.

### 2.0.4
- Added Google Gemini Cloud support through Gemini's OpenAI-compatible endpoint. Storyteller automatically detects
  Google endpoints and omits the unsupported `top_k`, `min_p`, and `repeat_penalty` sampler fields. For a custom
  gateway hostname, set `google.backend=true` explicitly.
- Keep failed web prompts out of story history and restore them in the composer when Gemini or another model backend
  is temporarily unavailable. A prominent notification modal explains the failure and closes automatically.

Example Gemini Cloud configuration:

```properties
backend.type=openai-compatible
backend.http.url=https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
backend.http.apiKey=Your_Google_Developer_Api_Key
model.chat=gemini-3.6-flash
```
Read more: https://github.com/jbrugman/Assistant/wiki/Changelog
