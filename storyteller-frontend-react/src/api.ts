export interface Session {
  sessionId: string;
  title: string | null;
  createdAt: string;
  updatedAt: string;
  lastAccessedAt: string;
  expiresAt: string;
  infinite: boolean;
}

export interface StoryMessage {
  messageIndex: number;
  role: string;
  content: string;
  hasImage: boolean;
}

// The exclusive cutoff for "use as past context": only exchanges with a lower messageIndex can be referenced.
// hasOlder reports whether older pages remain, so the conversation can page back through a long story.
export interface SessionHistory {
  sessionId: string;
  messages: StoryMessage[];
  referenceBeforeMessageIndex: number;
  hasOlder: boolean;
}

// Optional window for the session history endpoint, mirroring the bundled interface's message-index cursor.
export interface HistoryPage {
  before?: number;
  limit?: number;
}

export interface SessionConfig {
  sessionId: string;
  systemPrompt: string;
  fixedProtagonists: string;
  rules: string;
  temperature: number | null;
  effectiveTemperature: number | null;
  streamingEnabled: boolean | null;
  validationEnabled: boolean | null;
  graphEnabled: boolean | null;
  effectiveStreamingEnabled: boolean;
  effectiveValidationEnabled: boolean;
  effectiveGraphEnabled: boolean;
  maxTokens: number | null;
  effectiveMaxTokens: number | null;
  contextMode: string | null;
  effectiveContextMode: 'turns' | 'rolling';
  contextWindow: number | null;
  effectiveContextWindow: number;
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

// The backend serves the graph as a JSON document; the settings editor works with the same document as text.
export type KnowledgeGraph = Record<string, unknown>;

export interface SessionGraph {
  sessionId: string;
  knowledgeGraph: KnowledgeGraph;
}

export interface SessionState {
  sessionId: string;
  summary: string;
  recentSummary: string;
  canonicalState: string;
  summaryCursor: number;
  recentSummaryCursor: number;
  canonicalStateCursor: number;
  knowledgeGraph: KnowledgeGraph;
}

export interface AtlasRelation {
  id: string;
  text: string;
  subjectId: string;
  objectId: string;
  status: 'ACTIVE' | 'PROPOSED' | 'RETRACTED' | 'SUPERSEDED';
  source: string;
  sourceTurn: number | null;
  hard: boolean;
}

export interface AtlasEntry {
  id: string;
  type: 'CHARACTER' | 'LOCATION' | 'ITEM' | 'SKILL';
  name: string;
  aliases: string[];
  source: string;
  relations: AtlasRelation[];
  profile: AtlasProfileField[];
  profileNotice: string;
}

export interface AtlasProfileField {
  label: string;
  value: string;
  children: AtlasProfileField[];
}

export interface SessionAtlas {
  sessionId: string;
  revision: number;
  entries: AtlasEntry[];
  profileWarning: string;
}

export type MemoryLayer = 'mid-term' | 'long-term' | 'canonical-state';

export interface TurnOptions {
  image?: File;
  signal?: AbortSignal;
  pastMessageIndexes?: number[];
}

// Set by Vite from VITE_API_BASE_URL (empty by default); see vite.config.ts.
declare const __STORYTELLER_API_BASE_URL__: string;
// Default base is relative, so the SPA talks to the very origin that served it (any host/IP/port).
const apiBaseUrl = __STORYTELLER_API_BASE_URL__;
const apiUrl = (path: string) => `${apiBaseUrl.replace(/\/+$/, '')}/v1${path}`;

// A string body is sent unchanged so raw JSON, such as a knowledge graph, is not encoded twice.
async function request<T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
  const json = body !== undefined && !(body instanceof FormData);
  const response = await fetch(apiUrl(path), {
    method,
    credentials: 'include',
    signal,
    headers: json ? { 'Content-Type': 'application/json' } : undefined,
    body: body === undefined ? undefined
      : body instanceof FormData ? body : typeof body === 'string' ? body : JSON.stringify(body),
  });
  if (!response.ok) throw await apiFailure(response);
  return await responseBody<T>(response);
}

// Every v1 failure answers with {code, message}; a stream can already fail before its first event.
async function apiFailure(response: Response): Promise<ApiError> {
  const error = await response.json().catch(() => null);
  return new ApiError(response.status, error?.code ?? 'http_error',
    error?.message ?? `Request failed (${response.status}).`);
}

// Endpoints without a body must not be parsed as JSON, which would report a syntax error.
async function responseBody<T>(response: Response): Promise<T> {
  const text = await response.text();
  return (response.status === 204 || text === '' ? undefined : JSON.parse(text)) as T;
}

const sessionPath = (id: string) => `/sessions/${encodeURIComponent(id)}`;

// A windowed history read only appends the query parameters the caller actually supplied.
function historyQuery(page?: HistoryPage): string {
  const query = new URLSearchParams();
  if (page?.before !== undefined) query.set('before', String(page.before));
  if (page?.limit !== undefined) query.set('limit', String(page.limit));
  const value = query.toString();
  return value ? `?${value}` : '';
}

// Turns accept multipart so an image can accompany the prompt.
function turnForm(prompt: string, image: File, pastMessageIndexes: number[] = []): FormData {
  const form = new FormData();
  form.append('prompt', prompt);
  form.append('image', image, image.name);
  // Repeated pastExchange fields mirror the bundled form so older scenes can join the prompt context.
  for (const index of pastMessageIndexes) form.append('pastExchange', String(index));
  return form;
}

export const api = {
  currentSession: () => request<Session>('/session'),
  stopSession: () => request<void>('/session/stop', 'POST'),
  setInfinite: (id: string, infinite: boolean) => request<Session>(sessionPath(id), 'PATCH', { infinite }),
  atlas: (id: string) => request<SessionAtlas>(`${sessionPath(id)}/atlas`),
  state: (id: string) => request<SessionState>(`${sessionPath(id)}/state`),
  resetMemory: (id: string, layer: MemoryLayer) => request<void>(`${sessionPath(id)}/memory/reset/${layer}`, 'POST'),
  createSession: (title: string) => request<Session>('/sessions', 'POST', { title }),
  history: (id: string, page?: HistoryPage) => request<SessionHistory>(`${sessionPath(id)}/history${historyQuery(page)}`),
  config: (id: string) => request<SessionConfig>(`${sessionPath(id)}/config`),
  saveConfig: (id: string, settings: Pick<SessionConfig, 'systemPrompt' | 'fixedProtagonists' | 'rules' | 'temperature' | 'streamingEnabled' | 'validationEnabled' | 'graphEnabled' | 'maxTokens' | 'contextMode' | 'contextWindow'>) =>
    request<SessionConfig>(`${sessionPath(id)}/config`, 'PATCH', settings),
  resumeSession: (id: string) => request<Session>(`${sessionPath(id)}/resume`, 'POST'),
  importSession: (bundle: File) => {
    const body = new FormData();
    body.append('bundle', bundle);
    return request<Session>('/session-imports', 'POST', body);
  },
  undo: (id: string) => request<void>(`${sessionPath(id)}/undo`, 'POST'),
  editMessage: (id: string, messageIndex: number, content: string) =>
    request<{ sessionId: string; messageIndex: number; content: string }>(
      `${sessionPath(id)}/messages/${messageIndex}/edit`, 'POST', { content }),
  saveGraph: (id: string, knowledgeGraph: string) =>
    request<SessionGraph>(`${sessionPath(id)}/graph`, 'PUT', knowledgeGraph),
  generateEmptyGraph: (id: string) => request<SessionGraph>(`${sessionPath(id)}/graph/generate-empty`, 'POST'),
  fillGraph: (id: string) => request<SessionGraph>(`${sessionPath(id)}/graph/fill-fixed-protagonists`, 'POST'),
  resetGraph: (id: string) => request<SessionGraph>(`${sessionPath(id)}/graph/reset-turn-based`, 'POST'),
  // Binary endpoints stay plain URLs so the browser can load or download them itself.
  imageUrl: (id: string, messageIndex: number) => apiUrl(`${sessionPath(id)}/images/${messageIndex}`),
  exportUrl: (id: string) => apiUrl(`${sessionPath(id)}/export`),
  turn: (id: string, prompt: string, options: TurnOptions = {}) => request<{
    sessionId: string; userMessageIndex: number; assistantMessageIndex: number; response: string;
  }>(`${sessionPath(id)}/turns`, 'POST',
    options.image
      ? turnForm(prompt, options.image, options.pastMessageIndexes)
      : { prompt, pastMessageIndexes: options.pastMessageIndexes ?? [] },
    options.signal),
  streamTurn: async (id: string, prompt: string, onText: (text: string) => void, options: TurnOptions = {}) => {
    const form = options.image ? turnForm(prompt, options.image, options.pastMessageIndexes) : null;
    const response = await fetch(apiUrl(`${sessionPath(id)}/turns/stream`), {
      method: 'POST', credentials: 'include', signal: options.signal,
      headers: form ? undefined : { 'Content-Type': 'application/json' },
      body: form ?? JSON.stringify({ prompt, pastMessageIndexes: options.pastMessageIndexes ?? [] }),
    });
    if (!response.ok) throw await apiFailure(response);
    if (!response.body) throw new Error('Generation could not start.');
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let completed = false;
    try {
      while (true) {
        const { value, done } = await reader.read();
        buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
        let end: number;
        while ((end = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, end); buffer = buffer.slice(end + 2);
          const event = frame.split('\n').find(line => line.startsWith('event: '))?.slice(7);
          const data = frame.split('\n').filter(line => line.startsWith('data: ')).map(line => line.slice(6)).join('\n');
          if (!data) continue;
          const payload = JSON.parse(data);
          if (event === 'text') onText(payload.text);
          if (event === 'error') throw new Error(payload.message);
          if (event === 'done') { onText(payload.response); completed = true; }
        }
        if (done) break;
      }
      if (!completed) throw new Error('The stream ended before completion.');
    } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
  },
};
