# Storyteller React example

A small React + TypeScript example showing how to build an independent interface for the Storyteller API.

This is also a learning project for me: my frontend experience is with Angular, and I am using this example to learn React. 
The code deliberately stays small and explicit so others can learn from it and build their own interface.

## Run locally

Use Node.js 22.12 or newer (a current supported LTS version is recommended) and npm.
Start the Java API from the repository root using the existing application configuration:

```sh
mvn clean verify
java -jar storyteller-api/target/storyteller-api-*-all.jar
```

In a second terminal:

```sh
cd storyteller-frontend-react
npm ci
npm run dev
```

Open http://localhost:5173. Vite proxies `/v1` to `http://localhost:7070`, so browser requests and the session cookie use the frontend origin. 
No backend CORS changes are needed. If the backend uses another address, change `server.proxy` in `vite.config.ts`.

The backend needs its normal model and database configuration; this example does not contain a mock model. 
Sending a prompt invokes the configured model.

## What to try

1. Start a session with an optional title, resume an infinite session by ID, or import a CLI session ZIP.
2. Send a prompt and read the persisted history.
3. Open Settings to edit the system prompt, protagonists YAML, rules, and temperature slider (0–2). Save to apply them to the next turn.
4. Select “Use server default” and save to restore the default temperature.
5. In Settings, choose Default, Dark sci-fi, Nature, or Ocean. Themes apply immediately across all screens, independently of Save settings. A separate `storyteller_theme` cookie remembers the choice for one year; stopping a story does not clear it. Missing or unknown values use Default. If cookies are blocked, the choice lasts for the current visit.
6. Reload the page: `GET /v1/session` restores the session through its cookie.

Starting, resuming, or importing a session switches the active cookie; it does not delete the previous session.
“Infinite” preserves the session for later resume and displays its resume ID; “Use timeout” restores finite lifetime.
“Stop story” clears the active session cookie and returns to the start screen. Finite sessions are permanently deleted after confirmation; infinite sessions remain stored and can be resumed by ID.
“History” shows mid-term and long-term summaries and canonical state with processed-message counts. Each reset button clears only its corresponding memory layer, then reloads the stored state. Derived memory updates run asynchronously, so the displayed data can lag behind story turns. Only infinite sessions can be resumed by ID; this matches the bundled web interface.
The proxy does not share cookies already stored for a different hostname: use `localhost` consistently.

## Where to look

| File | Responsibility |
| --- | --- |
| `src/api.ts` | Typed API calls, JSON request bodies, and API errors using ordinary `fetch`. |
| `src/App.tsx` | Active session, screen selection, API operations, and shared loading/error handling. |
| `src/StartPage.tsx` | Create, resume, and ZIP import forms. |
| `src/StoryPage.tsx` | Paired prompt/response history, composer, and layout toggles. |
| `src/SettingsPage.tsx` | Theme selector, prompt settings, and temperature slider. |
| `src/theme.ts` | Theme preference cookie and document theme attribute. |
| `src/HistoryPage.tsx` | Read-only memory layers, cursors, and reset buttons. |
| `src/main.tsx` | Mount the application into the HTML root element. |
| `src/style.css` | Layout and component rules, plus small React notice/default-control additions; imports the theme files. |
| `src/themes/*.css` | Default color palette and the Dark sci-fi, Nature, and Ocean overrides, selected by `data-theme`. |
| `vite.config.ts` | React build tooling and the local API proxy. |

The example follows the start, story, and settings layouts of the bundled JTE web interface. It calls create/current/resume/stop session, lifetime PATCH, ZIP import, history, state/memory resets, config GET/PATCH, and turns. For request/response contracts and additional endpoints, read [the API design](../docs/api_design.md#implemented-json-api). Image controls, graph editing, undo, response editing, and export are left for extensions.

The copied stylesheet keeps the example self-contained; later styling changes in the Java module are not automatically synchronized. React components use the same CSS class names, but do not execute the JTE templates or their scripts. Screen selection uses local React state, so there are no separate browser routes.

## React concepts from an Angular perspective

- A function component returns JSX: markup written alongside TypeScript rather than in a separate Angular template. `className` corresponds to an HTML `class`.
- `useState` holds component state. Call its setter to update the value and trigger rendering; do not mutate it directly.
- Props pass data to a child component, similar to Angular inputs. `StoryPage` receives the session and messages from `App`.
- `onChange` updates a controlled input; the input's `value` comes from state. This is explicit rather than `[(ngModel)]`.
- `onSubmit` handles a form. `preventDefault()` avoids the browser's page navigation.
- `useEffect` synchronizes with external systems. Here it loads the active session when the component mounts; cleanup prevents late results from updating an unmounted component.
- `exchanges.map(...)` renders paired prompt/response messages. Each item has a stable `key`, analogous to tracking items by identity in Angular.
- `api.ts` is an ordinary module rather than an injectable service. No DI, router, or extra state library is needed for this example.

Start with [React Quick Start](https://react.dev/learn), then [Thinking in React](https://react.dev/learn/thinking-in-react). Build tooling follows the [Vite guide](https://vite.dev/guide/).

## Check and build

```sh
npm run build
```

This checks TypeScript and creates `dist/`. This example is separate from Maven; Maven does not build it. 
`npm run preview` previews the built assets, but does not provide the development API proxy.
For a deployed build, serve `dist/` and reverse-proxy `/v1` on the same origin, or use your own backend. 
Opening `dist/index.html` directly is not a deployment.

This is an API learning example, not a complete replacement for the bundled web UI. 
A session ID grants access to that session; the current API has no user-account authorization layer. 
Errors are shown without automatic retries because retrying a turn can create another exchange.
