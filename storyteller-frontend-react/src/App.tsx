import { useEffect, useState } from 'react';
import { api, ApiError } from './api';
import type { MemoryLayer, Session, SessionConfig, SessionState, SessionAtlas, StoryMessage } from './api';
import StartPage from './StartPage';
import StoryPage from './StoryPage';
import SettingsPage from './SettingsPage';
import HistoryPage from './HistoryPage';
import AtlasPage from './AtlasPage';
import type { EditableSettings } from './SettingsPage';
import { applyTheme, readTheme, saveTheme } from './theme';
import type { Theme } from './theme';

export default function App() {
  const [theme, setTheme] = useState<Theme>(readTheme);
  const [session, setSession] = useState<Session | null>(null);
  const [messages, setMessages] = useState<StoryMessage[]>([]);
  const [config, setConfig] = useState<SessionConfig | null>(null);
  const [atlas, setAtlas] = useState<SessionAtlas | null>(null);
  const [state, setState] = useState<SessionState | null>(null);
  const [page, setPage] = useState<'start' | 'story' | 'settings' | 'history' | 'atlas'>('start');
  const [prompt, setPrompt] = useState('');
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    async function restoreSession() {
      try {
        const current = await api.currentSession();
        const history = await api.history(current.sessionId);
        if (!cancelled) {
          setSession(current);
          setMessages(history.messages);
          setPage('story');
        }
      } catch (cause) {
        if (!cancelled && !(cause instanceof ApiError &&
          ['session_not_found', 'session_gone'].includes(cause.code))) setError(errorMessage(cause));
      } finally { if (!cancelled) setBusy(false); }
    }
    void restoreSession();
    return () => { cancelled = true; };
  }, []);

  // Shared loading/error handling keeps each screen focused on its form and layout.
  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError('');
    try { await action(); }
    catch (cause) { setError(errorMessage(cause)); }
    finally { setBusy(false); }
  }

  async function openSession(open: () => Promise<Session>) {
    await run(async () => {
      const opened = await open();
      // Keep the new ID even if loading history fails; do not accidentally recreate it.
      setSession(opened);
      setMessages([]);
      setConfig(null);
      setState(null);
      setAtlas(null);
      setPrompt('');
      setPage('story');
      const history = await api.history(opened.sessionId);
      setMessages(history.messages);
    });
  }

  async function openSettings() {
    if (!session) return;
    await run(async () => {
      const settings = await api.config(session.sessionId);
      setConfig(settings);
      setPage('settings');
    });
  }

  async function saveSettings(settings: EditableSettings) {
    if (!session) return;
    await run(async () => {
      const saved = await api.saveConfig(session.sessionId, settings);
      setConfig(saved);
      setPage('story');
    });
  }

  async function sendPrompt() {
    if (!session || !prompt.trim()) return;
    await run(async () => {
      await api.turn(session.sessionId, prompt.trim());
      setPrompt('');
      const history = await api.history(session.sessionId);
      setMessages(history.messages);
    });
  }

  async function stopStory() {
    if (!session) return;
    if (!session.infinite && !window.confirm('Stop this story? The session and all stored story data will be permanently deleted.')) return;
    await run(async () => {
      await api.stopSession();
      setSession(null);
      setMessages([]);
      setConfig(null);
      setState(null);
      setAtlas(null);
      setPrompt('');
      setPage('start');
    });
  }

  async function toggleInfinite() {
    if (!session) return;
    await run(async () => setSession(await api.setInfinite(session.sessionId, !session.infinite)));
  }

  async function openAtlas() {
    if (!session) return;
    await run(async () => {
      setAtlas(await api.atlas(session.sessionId));
      setPage('atlas');
    });
  }

  async function openHistory() {
    if (!session) return;
    await run(async () => {
      setState(await api.state(session.sessionId));
      setPage('history');
    });
  }

  async function resetMemory(layer: MemoryLayer) {
    if (!session) return;
    await run(async () => {
      await api.resetMemory(session.sessionId, layer);
      // State is derived asynchronously; always reread persisted values after a reset.
      setState(await api.state(session.sessionId));
    });
  }

  return <>
    {error && <div className="example-notice" role="alert">
      <p>{error}</p><button onClick={() => setError('')}>Close</button>
    </div>}
    <p className="example-status" role="status">{busy ? 'Working…' : ''}</p>
    {page === 'start' && <StartPage busy={busy}
      onCreate={title => { void openSession(() => api.createSession(title)); }}
      onResume={id => { void openSession(() => api.resumeSession(id)); }}
      onImport={file => { void openSession(() => api.importSession(file)); }} />}
    {page === 'story' && session && <StoryPage session={session} messages={messages} busy={busy}
      prompt={prompt} onPromptChange={setPrompt} onSend={() => { void sendPrompt(); }}
      onSettings={() => { void openSettings(); }} onStop={() => { void stopStory(); }}
      onAtlas={() => { void openAtlas(); }} onInfinite={() => { void toggleInfinite(); }} onHistory={() => { void openHistory(); }} />}
    {page === 'settings' && config && <SettingsPage config={config} busy={busy} theme={theme}
      onThemeChange={theme => { setTheme(theme); applyTheme(theme); saveTheme(theme); }}
      onSave={settings => { void saveSettings(settings); }} onBack={() => setPage('story')} />}
    {page === 'atlas' && atlas && <AtlasPage key={atlas.sessionId} atlas={atlas} busy={busy}
      onRefresh={() => { void openAtlas(); }} onBack={() => setPage('story')} />}
    {page === 'history' && state && <HistoryPage state={state} busy={busy}
      onReset={layer => { void resetMemory(layer); }} onBack={() => setPage('story')} />}
  </>;
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : 'An unexpected error occurred.';
}
