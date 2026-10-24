# Storyteller API Design

## Purpose

The Storyteller API provides an HTTP interface and a default server-rendered web interface for Storyteller. It keeps session state on the server so clients send commands and content instead of resending the complete story state.

The API is one interface to the application. The CLI remains supported, and other clients can be built against the JSON API.

Related diagrams:

- [API component design](architecture/api/component-design.puml)
- [API flow design](architecture/api/flow-design.puml)
- [Database component design](architecture/db/component-design.puml)
- [Database schema design](architecture/db/schema-design.puml)
- [Web interface design](webpage-design.md)

## Module Boundaries

The Maven modules have separate responsibilities:

| Module | Responsibility |
|---|---|
| `storyteller-core` | Story generation, prompt assembly, validation, derived memory, knowledge-graph behavior, and domain models. |
| `storyteller-db` | JDBC repositories, H2 schema, and database-backed session persistence shared by the CLI and API. |
| `storyteller-cli` | Terminal interface and JLine integration. Depends on Core and DB. |
| `storyteller-api` | Javalin controllers, JTE pages, API configuration, HTTP/TLS lifecycle, and web assets. Depends on Core and DB. |

Core must not depend on CLI, Javalin, JTE, JDBC, or H2. Controllers remain thin and delegate application behavior to services. Database code remains outside both interface modules.

The API uses Core's prompt construction, model client, backend guard, validation, and memory components. It does not route requests through the terminal-oriented Core `StorySessionService`; the API has its own database-backed session orchestration.

## Runtime Design

### Backend

The API talks to an already running OpenAI-compatible backend, such as LM Studio. It does not start or stop `llama-server`, MLX, or another inference process. Backend selection and lifecycle are external to the API process.

### HTTP and server-side rendering

Javalin owns HTTP routing and server lifecycle. JTE renders the bundled default web interface on the server. This keeps the browser client small and avoids requiring a separate JavaScript framework or frontend deployment.

The default web interface is optional from an architectural perspective: another client can use the JSON API. The rationale for SSR and JTE is documented in the [web interface design](webpage-design.md).

### Configuration

The API owns its configuration and bundled defaults in:

```text
storyteller-api/src/main/resources/application.config
```

A runtime `application.config` overrides those defaults. API configuration is not stored in the Core configuration file.

Current settings include:

- `api.host`
- `api.port`
- `api.database.path`
- `api.database.username`
- `api.database.password`
- `api.sessionTimeoutMinutes`
- `api.tls.enabled`
- `api.tls.port`
- `api.tls.directory`
- `api.tls.subjectAlternativeNames`

The default HTTP listener uses port `7070`. When TLS is enabled, HTTPS uses port `7443`; both connectors belong to the same API application. The generated local certificate authority is available at `GET /storyteller-ca.crt`. Session cookies are marked `Secure` when TLS is enabled.

### Packaging

The API is packaged separately from the CLI so the CLI distribution does not include Javalin, Jetty, JTE, H2 web dependencies, or API resources. Both remain modules of the same Maven project.

## Sessions

The server owns session history, prompts, canonical state, summaries, knowledge-graph data, images, activity timestamps, and lifetime policy.

For a new session, `systemprompt.md`, `fixed_protagonists.yml`, and `rules.md` are resolved from local overrides or
bundled resources and copied into H2 as initial defaults. The stored session values are authoritative afterward;
restarting the application or changing the source files does not overwrite an existing session. Internal validation,
summary, canonical-state, and graph templates remain application resources and are not editable session data.

The browser stores only the active session identifier in a cookie. An infinite session can be reopened in another browser when its session ID is known. Non-infinite sessions expire after the configured inactivity period.

The CLI uses a fixed database session ID:

```text
00000000-0000-0000-0000-000000000001
```

This permits the same persisted story to be used through CLI and API interfaces. Embedded H2 file access still permits only one owning process at a time; the CLI and API must not open the same embedded database concurrently.

Stopping a non-infinite session permanently removes the session and its dependent records. Infinite sessions are
protected from deletion until their lifetime is changed back to finite. Undo removes the most recent persisted
exchange and its derived data through the repository transaction designed for that operation.

## Persistence

H2 is the current embedded database, accessed through portable JDBC and standard SQL where practical. There is no ORM and no Flyway dependency.

The canonical schema lives in [schema.sql](../storyteller-db/src/main/resources/db/schema.sql). The [database schema diagram](architecture/db/schema-design.puml) provides a readable overview. This document deliberately does not duplicate column definitions because the executable schema is authoritative.

Repository implementations own transaction boundaries for multi-table operations such as import, deletion, and undo. A
normal story turn is persisted before long-term history, recent history, canonical state, and the turn-based knowledge
graph are updated asynchronously; those operations are not falsely presented as one database transaction.

Database-backed state includes:

- sessions and activity/lifetime settings
- ordered story messages
- optional image payloads and media metadata
- system prompt, fixed protagonist, and rules
- canonical state
- medium- and long-term history
- knowledge-graph documents
- selected older exchanges used as additional context

The settings page can edit the system prompt, fixed protagonist, rules, and validated knowledge graph. Medium- and
long-term history and canonical state are read-only in the web interface.

### Knowledge graph

Knowledge-graph data is persisted and included in session import/export. The API story-turn path injects relevant
session graph facts into model context and schedules turn-based extraction after a persisted turn. Extraction runs when
the configured `graph.turnBased.batchTurns` threshold is reached and stores the resulting revision in the same session.
The graph update uses the same Core extraction and validation behavior as the CLI.

### Story atlas

Both web frontends expose a read-only atlas combining the session’s saved Fixed protagonists YAML with its knowledge graph. The bundled interface serves
`GET /story/atlas`; React uses `GET /v1/sessions/{sessionId}/atlas`. A shared service builds the entry names, aliases,
and readable fact sentences using the configured predicate catalog, including negative predicates. Character profiles
retain arbitrary nested YAML fields and lists in a separate section, including profiles not present in the graph.
Only unambiguous one-to-one name or alias matches are attached to graph characters; ambiguous profiles are kept separate.
Profile values and graph facts never overwrite each other. Category changes immediately update the list and details
and clear the previous search term. Facts are attached
to both endpoints for navigation while their original subject/object direction is preserved. Active facts are shown
separately from proposed, retracted, and superseded facts. No model requests or graph writes occur when opening or
refreshing the atlas. Empty categories and entities without active facts remain browsable. Graph updates are
asynchronous; the displayed revision identifies the loaded snapshot, not a processed-turn count.

### Images

Prompt images are stored as BLOB data with their message record, not as ordinary files beside the database. The web interface accepts pasted images, shows a clickable thumbnail beside the associated prompt, and presents the full image in a dismissible overlay.

Exports write images to `memory/images/NNN.ext`, where the number corresponds to the message index. Imports restore supported images and their association with the prompt.

## Story Context

The regular context window contains the configured number of recent exchanges. The web interface can additionally select a small number of older exchanges that fall outside this window. These are explicitly labelled as relevant past events when sent to the model.

Selection is limited so this feature cannot silently replace normal context management or inflate requests without bound.

## Session Bundles

ZIP import/export is the portable backup and migration format. A bundle can contain:

- `history.json`
- prompt override files under `systemprompts/`
- canonical and derived memory
- knowledge-graph data
- prompt images under `memory/images/`

Newer bundle entries are optional during import so older CLI exports remain usable. Import validates entry names, ignores platform metadata such as macOS `__MACOSX` entries, limits uncompressed content, validates supported JSON/YAML structures, and persists the imported session in a repository transaction.

## Implemented JSON API

The public API exposes the same operations as the bundled web interface. Responses are JSON except for image and ZIP downloads:

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/sessions` | Create a database-backed story session. |
| `GET` | `/v1/session` | Resolve the active browser session. |
| `POST` | `/v1/session/stop` | Stop the active session and clear its cookie; preserve infinite sessions. |
| `GET` | `/v1/sessions/{sessionId}` | Read a session by ID. |
| `POST` | `/v1/sessions/{sessionId}/turns` | Submit a story turn and return the generated reply. |
| `POST` | `/v1/sessions/{sessionId}/messages/{messageIndex}/edit` | Edit an existing assistant response. |
| `GET` | `/v1/sessions/{sessionId}/history` | Read the complete ordered message history. |
| `GET` | `/v1/sessions/{sessionId}/config` | Read editable prompts and the session temperature. |
| `PATCH` | `/v1/sessions/{sessionId}/config` | Update only the supplied configuration fields. |
| `POST` | `/v1/sessions/{sessionId}/undo` | Undo the latest persisted exchange. |
| `GET` | `/v1/sessions/{sessionId}/atlas` | Read named graph entries and readable, directed facts with statuses and sources. |
| `GET` | `/v1/sessions/{sessionId}/state` | Read derived memory, its cursors, and the knowledge graph. |
| `PATCH` | `/v1/sessions/{sessionId}` | Set the session's infinite lifetime explicitly. |
| `DELETE` | `/v1/sessions/{sessionId}` | Delete a non-infinite session and its dependent records. |
| `POST` | `/v1/sessions/{sessionId}/resume` | Make an infinite session the active cookie session. |
| `POST` | `/v1/session-imports` | Import a session ZIP and return the new session. |
| `GET` | `/v1/sessions/{sessionId}/export` | Download the session ZIP. |
| `GET` | `/v1/sessions/{sessionId}/images/{messageIndex}` | Download a stored prompt image. |
| `PUT` | `/v1/sessions/{sessionId}/graph` | Replace the validated knowledge graph. |
| `POST` | `/v1/sessions/{sessionId}/graph/reset-turn-based` | Remove turn-based graph facts and entities. |
| `POST` | `/v1/sessions/{sessionId}/graph/fill-fixed-protagonists` | Generate a graph from the saved protagonist definitions. |
| `POST` | `/v1/sessions/{sessionId}/graph/generate-empty` | Replace the graph with an empty document. |
| `POST` | `/v1/sessions/{sessionId}/memory/reset/mid-term` | Clear the recent summary and its cursor. |
| `POST` | `/v1/sessions/{sessionId}/memory/reset/long-term` | Clear the long-term summary and its cursor. |
| `POST` | `/v1/sessions/{sessionId}/memory/reset/canonical-state` | Clear canonical state and its cursor. |

These JSON endpoints are independent of the JTE templates. Routes containing `{sessionId}` resolve the session by
the path ID and do not require an active-session cookie, matching the existing turns endpoint. The cookie is needed
only to resolve `GET /v1/session`. A session ID acts as access to that session; there is no user-account authorization
layer. Browser clients can use the API on the same origin or through their own backend. Cross-origin browser access
is not enabled by default.

### History

`GET /v1/sessions/{sessionId}/history` returns `200` with:

```json
{
  "sessionId": "<session-id>",
  "messages": [
    {"messageIndex": 0, "role": "user", "content": "Start the story", "hasImage": false},
    {"messageIndex": 1, "role": "assistant", "content": "The story begins...", "hasImage": false}
  ]
}
```

Messages are ordered by ascending message index. A new session returns an empty array. This initial JSON endpoint
returns the complete history; paging remains a future addition. `hasImage` indicates stored image data, but this
response does not embed the image. Retrieve its bytes through `GET /v1/sessions/{sessionId}/images/{messageIndex}`.

### Session configuration

`GET /v1/sessions/{sessionId}/config` returns `200` with `sessionId`, `systemPrompt`, `fixedProtagonists`, `rules`,
`temperature`, and `effectiveTemperature`. Prompt fields contain their stored text; `fixedProtagonists` contains YAML.
`temperature` is the nullable session override; `effectiveTemperature` resolves a null override to the configured
`chat.temperature`.

`PATCH /v1/sessions/{sessionId}/config` accepts a JSON object containing any subset of `systemPrompt`,
`fixedProtagonists`, `rules`, and `temperature`, for example:

```json
{"temperature": 0.8, "rules": "Keep the story consistent."}
```

Omitted fields retain their stored values. An empty object is a no-op. Prompt fields must be nonblank strings and use
the same normalization and YAML validation as the settings page. Explicit null is rejected for prompt fields.
Temperature must be a JSON number between `0` and `2`, inclusive, or null. Setting `temperature` to null restores the
application default. Database storage rounds temperature to three decimal places; the response contains the stored
value. Unknown fields and malformed JSON are rejected with `400` and the existing `invalid_request` error shape.

All supplied fields are validated before persistence and updated in one repository transaction. The patch does not
rewrite omitted prompts, the knowledge graph, or memory. It returns `200` with the same shape as `GET /config`.
Updated settings apply to subsequent story turns, including the next model request; already-running turns retain
the settings they loaded when they started.

### Undo

`POST /v1/sessions/{sessionId}/undo` accepts no request payload and returns `204` with no response body. It delegates
to the same operation as the web interface: delete the last complete user/assistant exchange, clamp memory cursors,
and remove turn-based facts belonging to the undone turn in the existing repository transaction. With no complete
exchange, it is a no-op and still returns `204`. Clients can retrieve the remaining history afterward. Undo does not
invoke the model or introduce a cache-buster reset or retry operation.

### State

`GET /v1/sessions/{sessionId}/state` returns `200` with `sessionId`, `summary`, `recentSummary`, `canonicalState`,
`summaryCursor`, `recentSummaryCursor`, `canonicalStateCursor`, and `knowledgeGraph`. Memory fields contain stored
text; cursors are the processed message counts used by the existing memory implementation. `knowledgeGraph` is a JSON
object in the existing graph codec format (`schemaVersion`, `revision`, `entities`, `facts`).

This is read-only inspection. Derived updates run asynchronously after a turn, so memory and graph can lag behind
history and are read independently; this response is not an atomic snapshot or a guarantee that background work has
completed. A cursor reports processing position, not a pending/running status. Graph replacement and memory resets
use the separate mutation endpoints below.

The history, configuration, undo and state endpoints return `404` with `{"code":"session_not_found","message":"Session was not found."}` for
unknown, deleted, or expired sessions. They use the existing session lookup and inactivity renewal behavior.

### Session lifetime, resume and deletion

`POST /v1/session/stop` stops the active cookie session using the same logic as the web interface. It deletes a
finite session and its dependent data, preserves an infinite session for later resume, and clears the active cookie.
It returns `204` with no body. Missing or stale cookies are a no-op for data and still clear the cookie.
Unlike path-based deletion, this endpoint resolves its target exclusively from the active session cookie.


`PATCH /v1/sessions/{sessionId}` accepts exactly `{"infinite":true}` or `{"infinite":false}`. The field must be a JSON
boolean; unknown fields are rejected. Repeating the same value leaves the lifetime policy unchanged instead of
toggling it. The operation returns `200` with the existing `SessionResponse` shape. If the target is also the active
cookie session, the cookie lifetime is refreshed to match the policy.

`POST /v1/sessions/{sessionId}/resume` returns `200` with `SessionResponse` and writes the active-session cookie.
Only infinite sessions can be resumed this way, matching the web interface. Unknown or non-infinite sessions return
`404` with code `session_not_resumable`. Clients retaining their own session ID can continue using path-based routes
without this cookie operation.

`DELETE /v1/sessions/{sessionId}` returns `204` with no body and clears the active cookie if it points to the deleted
session. As in the existing session service, infinite sessions are protected: deletion returns `409` with code
`session_infinite`. Set `infinite` to false first to delete one deliberately. Unknown or expired sessions return
`404` with code `session_not_found`.

### Turns with historical context and images

`POST /v1/sessions/{sessionId}/turns` retains its existing JSON prompt contract and adds an optional list of message
indexes referring to older complete exchanges:

```json
{"prompt":"Return to the earlier encounter.","pastMessageIndexes":[0,4]}
```

Each index identifies the user message at the start of the exchange. Existing service validation requires the
exchange to exist in that session and fall outside the normal recent-context window; at most three distinct
exchanges are allowed. Omitting the list or supplying null selects no additional context. Invalid references
return `400` before invoking the model or persisting a turn.

For images, the same endpoint accepts `multipart/form-data` with a text `prompt`, an optional `image` file, and
optional repeated `pastExchange` fields containing integer indexes. This uses the same upload validation as the web
interface: PNG or JPEG, at most 5 MiB, dimensions no greater than 1024 by 768 pixels. Multipart total size is limited
to 6 MiB. Parser size-limit failures return `413` with JSON code `payload_too_large`; malformed multipart returns
`400 invalid_request`. An absent or empty image part is treated as no image. Both request formats return the existing JSON
`StoryTurnResponse`; image bytes are stored with the user message and passed to the model as image content.

`GET /v1/sessions/{sessionId}/images/{messageIndex}` returns the original stored bytes with their image media type
and `Cache-Control: private, no-store`. It uses the path session ID, not the active cookie. Missing images return
`404` with code `image_not_found`; invalid negative or noninteger indexes return `400`. Existing imported images
remain readable under the storage format's older limits and media types.

### Session ZIP import/export

`GET /v1/sessions/{sessionId}/export` returns `200`, `Content-Type: application/zip`, and an attachment filename
`story-session-<session-id>.zip`. It delegates to the existing bundle service and includes the same prompts, history,
memory, graph and images as web export.

`POST /v1/session-imports` accepts multipart form data with a `bundle` ZIP file. It applies the existing 32 MiB archive
limit and bundle validation, with a 33 MiB multipart total limit. Missing or invalid bundles return `400`; oversized
multipart uploads return `413`. Import creates a new finite session, returns `201` with `SessionResponse`, and sets
the active-session cookie. There is no overwrite operation. ZIP validation and persistence retain their existing
service and repository transaction boundaries.

### Knowledge graph mutation

All four graph mutation routes return `200` with:

```json
{"sessionId":"<session-id>","knowledgeGraph":{"schemaVersion":1,"revision":1,"entities":{},"facts":[]}}
```

`PUT /v1/sessions/{sessionId}/graph` accepts a complete graph document in the codec format used by `GET /state`.
Read the current graph first and include its revision. The document is validated with the existing predicate and
graph validators. Replacement is serialized with graph generation and extraction through the existing graph task
queue, checks that the submitted revision still matches, and advances the stored revision by one. A stale revision
returns `409` with code `graph_conflict`; reload before editing again. Invalid graph JSON or schema returns `400`.
Only the graph is replaced; prompts and memory are not rewritten.

`POST /graph/reset-turn-based` removes turn-based facts and entities using the existing management service and
forces extraction on the next eligible update. `POST /graph/fill-fixed-protagonists` generates and replaces the
graph from the saved protagonist YAML, normalizing generated data to fixed-protagonist sources. It invokes the
model. Save any protagonist edits through `PATCH /config` first. `POST /graph/generate-empty` replaces the graph
with an empty document and advances its revision. These suffixes are relative to `/v1/sessions/{sessionId}` and
accept no request payload. They preserve the corresponding web actions' service behavior.

### Memory resets

The three `/memory/reset/...` routes accept no payload and return `204`. Each clears only the named memory layer
and resets its processing cursor to zero. They use the existing reset methods, without a model call or deletion of
story history, prompts, graph data or the other memory layers. Subsequent asynchronous updates can regenerate memory
from the retained history, matching the web interface. Read the result through `GET /state`.

The session-scoped graph, memory, export and image routes return the same JSON `session_not_found` error for invalid
session IDs. Model failures use the existing `502 model_backend_error` contract; interruptions use
`503 request_interrupted`.

Unknown sessions, invalid input, conflicts, backend failures, and persistence failures are mapped to stable HTTP error responses. SQL is always parameterized; input normalization complements validation but is not treated as a substitute for prepared statements.

## Implemented Web Routes

The bundled web interface currently uses these server-rendered routes:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | Start or landing page. |
| `POST` | `/web/sessions` | Create a web session. |
| `POST` | `/web/sessions/resume` | Resume by session ID. |
| `GET` | `/story/resume/{sessionId}` | Open a resumable session. |
| `POST` | `/import` | Import a session ZIP. |
| `GET` | `/export` | Download the current session ZIP. |
| `GET` | `/story` | Render the current story. |
| `GET` | `/story/history` | Load an older page of exchanges. |
| `GET` | `/story/images/{messageIndex}` | Return a stored prompt image. |
| `GET`, `POST` | `/story/settings` | View or update editable session state. |
| `GET` | `/story/memory` | View derived history. |
| `POST` | `/story/turns` | Submit a text/image turn. |
| `POST` | `/story/undo` | Undo the latest exchange. |
| `POST` | `/story/infinite` | Change session lifetime policy. |
| `POST` | `/story/stop` | Stop the active session; delete finite sessions and preserve infinite sessions. |

History is paged for the web view rather than loading the complete story on every request. Browser actions that mutate a session are disabled while their request is in progress to prevent duplicate submissions.

## Planned JSON API

The following capabilities may receive public JSON endpoints, but are not part of the implemented contract yet:

- paged history and session listing
- reset
- canonical-state management and additional configuration fields
- health/model inspection
- OpenAPI/Swagger description

Web routes are not automatically a stable public JSON contract. A planned endpoint must receive explicit DTOs, validation, error semantics, tests, and documentation before it is listed as implemented.

## Implementation Conventions

- Use two-space indentation in Java and SQL.
- Prefer records for immutable data carriers.
- Keep SQL in dedicated query classes and use prepared statements.
- Keep Javalin `Context` objects inside the controller layer.
- Keep route handlers small and move orchestration into services.
- Use Given/When/Then structure in tests and parameterized tests where appropriate.
- Preserve Core behavior across CLI and API unless an interface-specific difference is explicitly documented.
