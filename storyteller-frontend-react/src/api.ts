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

export interface SessionConfig {
  sessionId: string;
  systemPrompt: string;
  fixedProtagonists: string;
  rules: string;
  temperature: number | null;
  effectiveTemperature: number | null;
  streamingEnabled: boolean | null;
  validationEnabled: boolean | null;
  effectiveStreamingEnabled: boolean;
  effectiveValidationEnabled: boolean;
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

export interface SessionState {
  sessionId: string;
  summary: string;
  recentSummary: string;
  canonicalState: string;
  summaryCursor: number;
  recentSummaryCursor: number;
  canonicalStateCursor: number;
  knowledgeGraph: unknown;
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

// Relative URLs work through Vite's development proxy or a same-origin deployment.
async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch(`/v1${path}`, {
    method,
    credentials: 'same-origin',
    headers: body === undefined || body instanceof FormData ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new ApiError(response.status, error?.code ?? 'http_error',
      error?.message ?? `Request failed (${response.status}).`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

const sessionPath = (id: string) => `/sessions/${encodeURIComponent(id)}`;

export const api = {
  currentSession: () => request<Session>('/session'),
  stopSession: () => request<void>('/session/stop', 'POST'),
  setInfinite: (id: string, infinite: boolean) => request<Session>(sessionPath(id), 'PATCH', { infinite }),
  atlas: (id: string) => request<SessionAtlas>(`${sessionPath(id)}/atlas`),
  state: (id: string) => request<SessionState>(`${sessionPath(id)}/state`),
  resetMemory: (id: string, layer: MemoryLayer) => request<void>(`${sessionPath(id)}/memory/reset/${layer}`, 'POST'),
  createSession: (title: string) => request<Session>('/sessions', 'POST', { title }),
  history: (id: string) => request<{ sessionId: string; messages: StoryMessage[] }>(`${sessionPath(id)}/history`),
  config: (id: string) => request<SessionConfig>(`${sessionPath(id)}/config`),
  saveConfig: (id: string, settings: Pick<SessionConfig, 'systemPrompt' | 'fixedProtagonists' | 'rules' | 'temperature' | 'streamingEnabled' | 'validationEnabled'>) =>
    request<SessionConfig>(`${sessionPath(id)}/config`, 'PATCH', settings),
  resumeSession: (id: string) => request<Session>(`${sessionPath(id)}/resume`, 'POST'),
  importSession: (bundle: File) => {
    const body = new FormData();
    body.append('bundle', bundle);
    return request<Session>('/session-imports', 'POST', body);
  },
  streamTurn: async (id: string, prompt: string, onText: (text: string) => void) => {
    const response = await fetch(`/v1${sessionPath(id)}/turns/stream`, {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt }),
    });
    if (!response.ok || !response.body) throw new Error('Generation could not start.');
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
  turn: (id: string, prompt: string) => request<{
    sessionId: string; userMessageIndex: number; assistantMessageIndex: number; response: string;
  }>(`${sessionPath(id)}/turns`, 'POST', { prompt }),
};
