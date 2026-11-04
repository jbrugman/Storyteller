import { useEffect, useRef, useState } from 'react';
import { api } from './api';
import type { MemoryLayer, Session, SessionConfig, SessionState, SessionAtlas, SessionHistory, StoryMessage } from './api';
import StartPage from './StartPage';
import StoryPage from './StoryPage';
import SettingsPage from './SettingsPage';
import HistoryPage from './HistoryPage';
import AtlasPage from './AtlasPage';
import type { EditableSettings } from './SettingsPage';
import { applyTheme, readTheme, saveTheme } from './theme';
import type { Theme } from './theme';
import { errorMessage, errorOutcome } from './session-errors';
import { submitTurn } from './turn-submission';
import { prepareImage } from './images';
import { formatKnowledgeGraph } from './knowledge-graph';
import type { GraphAction } from './knowledge-graph';

const modelUnavailableTitle = 'Model temporarily unavailable';

// Page size mirrors the bundled interface (STORY_PAGE_MESSAGES): ten messages, roughly five exchanges, per window.
const MESSAGES_PER_PAGE = 10;

// Only the JSON API is available here, so its graph actions report success without the JTE counts.
const graphActions: Record<GraphAction, { send: (id: string) => Promise<{ knowledgeGraph: unknown }>; title: string; message: string }> = {
  generate: { send: id => api.generateEmptyGraph(id), title: 'Empty knowledge graph generated',
    message: 'The knowledge graph was replaced with a minimal empty graph.' },
  fill: { send: id => api.fillGraph(id), title: 'Knowledge graph filled',
    message: 'Facts were generated from the saved Fixed protagonists.' },
  reset: { send: id => api.resetGraph(id), title: 'Turn-based facts reset',
    message: 'Turn-based facts and entities were removed; fixed and manual data were kept.' },
};

export default function App() {
  const [theme, setTheme] = useState<Theme>(readTheme);
  const [session, setSession] = useState<Session | null>(null);
  const [messages, setMessages] = useState<StoryMessage[]>([]);
  const [config, setConfig] = useState<SessionConfig | null>(null);
  const [atlas, setAtlas] = useState<SessionAtlas | null>(null);
  const [state, setState] = useState<SessionState | null>(null);
  const [page, setPage] = useState<'start' | 'story' | 'settings' | 'history' | 'atlas'>('start');
  const [prompt, setPrompt] = useState('');
  const [image, setImage] = useState<File | null>(null);
  const [imageError, setImageError] = useState('');
  const [graphText, setGraphText] = useState('');
  const [notification, setNotification] = useState<{ title: string; message: string } | null>(null);
  const [busy, setBusy] = useState(true);
  const [busyAction, setBusyAction] = useState<'' | 'writing' | 'undoing'>('');
  const [error, setError] = useState('');
  const [streamText, setStreamText] = useState<string | null>(null);
  const [streamPrompt, setStreamPrompt] = useState('');
  // Exclusive cutoff for past-context selection plus the exchanges currently ticked by the user.
  const [referenceBefore, setReferenceBefore] = useState(0);
  const [selectedPast, setSelectedPast] = useState<number[]>([]);
  // Whether older pages remain so the conversation can page back through a long story without loading all of it.
  const [hasOlder, setHasOlder] = useState(false);
  const generation = useRef<AbortController | null>(null);
  const imageSelection = useRef(0);

  useEffect(() => {
    let cancelled = false;
    async function restoreSession() {
      try {
        const current = await api.currentSession();
        const history = await api.history(current.sessionId, { limit: MESSAGES_PER_PAGE });
        if (!cancelled) {
          setSession(current);
          applyHistoryWindow(history);
          setPage('story');
        }
      } catch (cause) {
        // A missing or expired session simply leaves the start screen visible, matching the bundled interface.
        const outcome = errorOutcome(cause);
        if (!cancelled && outcome.kind !== 'session-expired' && outcome.kind !== 'ignored') setError(outcome.message);
      } finally { if (!cancelled) setBusy(false); }
    }
    void restoreSession();
    return () => { cancelled = true; };
  }, []);

  // A model notification fades away on its own and reacts to Escape, like the bundled modal.
  useEffect(() => {
    if (!notification) return;
    const timer = window.setTimeout(() => setNotification(null), 8000);
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') setNotification(null); };
    document.addEventListener('keydown', onKeyDown);
    return () => { window.clearTimeout(timer); document.removeEventListener('keydown', onKeyDown); };
  }, [notification]);

  useEffect(() => {
    const labels = { settings: 'Story settings', atlas: 'Story atlas', history: 'Story history' } as const;
    const title = page === 'story' ? session?.title || 'Untitled story' : labels[page as keyof typeof labels] || 'Storyteller';
    document.title = `${title} · React example`;
  }, [page, session]);

  // Shared loading/error handling keeps each screen focused on its form and layout.
  async function run(action: () => Promise<void>, pending: '' | 'writing' | 'undoing' = '') {
    setBusy(true);
    setError('');
    setBusyAction(pending);
    try { await action(); return true; }
    catch (cause) {
      const outcome = errorOutcome(cause);
      if (outcome.kind === 'ignored') return false;
      if (outcome.kind === 'model-unavailable') setNotification({ title: modelUnavailableTitle, message: outcome.message });
      else setError(outcome.message);
      // A session can expire during any action, not only at startup; drop it and return to the start screen.
      if (outcome.kind === 'session-expired') { setSession(null); resetStoryData(); setPage('start'); }
      return false;
    }
    finally { setBusy(false); setBusyAction(''); }
  }

  // A windowed history read replaces the recent page and records whether older pages remain to scroll back to.
  function applyHistoryWindow(history: SessionHistory) {
    setMessages(history.messages);
    setReferenceBefore(history.referenceBeforeMessageIndex);
    setHasOlder(history.hasOlder);
  }

  // Reset every value tied to a session so a new one, a stop, or an expiry start from the same clean slate.
  function resetStoryData() {
    setMessages([]);
    setHasOlder(false);
    setStreamText(null);
    setStreamPrompt('');
    setReferenceBefore(0);
    setSelectedPast([]);
    setConfig(null);
    setState(null);
    setAtlas(null);
    setPrompt('');
    setImage(null);
    setImageError('');
  }

  async function openSession(open: () => Promise<Session>) {
    await run(async () => {
      const opened = await open();
      // Keep the new ID even if loading history fails; do not accidentally recreate it.
      setSession(opened);
      resetStoryData();
      setPage('story');
      applyHistoryWindow(await api.history(opened.sessionId, { limit: MESSAGES_PER_PAGE }));
    });
  }

  async function openSettings() {
    if (!session) return;
    await run(async () => {
      const [settings, current] = await Promise.all([api.config(session.sessionId), api.state(session.sessionId)]);
      setConfig(settings);
      setGraphText(formatKnowledgeGraph(current.knowledgeGraph));
      setPage('settings');
    });
  }

  // Saving mirrors the bundled settings form, which persists both the prompts and the knowledge graph.
  async function saveSettings(settings: EditableSettings) {
    if (!session) return;
    await run(async () => {
      setConfig(await api.saveConfig(session.sessionId, settings));
      await api.saveGraph(session.sessionId, graphText);
      setPage('story');
    });
  }

  async function applyGraph(action: GraphAction, settings: EditableSettings) {
    if (!session) return;
    await run(async () => {
      setConfig(await api.saveConfig(session.sessionId, settings));
      await api.saveGraph(session.sessionId, graphText);
      const result = await graphActions[action].send(session.sessionId);
      setGraphText(formatKnowledgeGraph(result.knowledgeGraph));
      setNotification({ title: graphActions[action].title, message: graphActions[action].message });
    });
  }

  async function sendPrompt() {
    if (!session || !prompt.trim() || imageError) return;
    const controller = new AbortController();
    generation.current = controller;
    // Only exchanges still outside the recent window are sent, so a stale tick can never trigger a 400.
    const pastMessageIndexes = selectedPast.filter(index => index < referenceBefore);
    const sent = await run(() => submitTurn({
      sessionId: session.sessionId, prompt, image: image ?? undefined, signal: controller.signal, pastMessageIndexes,
      onPrompt: setPrompt, onStreamPrompt: setStreamPrompt, onStreamText: setStreamText, onMessages: setMessages,
      onReferenceBefore: setReferenceBefore, historyLimit: MESSAGES_PER_PAGE, onHasOlder: setHasOlder,
    }), 'writing');
    // The bundled interface reloads after each turn, which resets the selection; mirror that on success.
    if (sent) { setImage(null); setImageError(''); setSelectedPast([]); }
    generation.current = null;
  }

  async function undo() {
    if (!session) return;
    await run(async () => {
      await api.undo(session.sessionId);
      applyHistoryWindow(await api.history(session.sessionId, { limit: MESSAGES_PER_PAGE }));
      setSelectedPast([]);
    }, 'undoing');
  }

  async function editMessage(messageIndex: number, content: string) {
    if (!session) return;
    await run(async () => {
      await api.editMessage(session.sessionId, messageIndex, content);
      applyHistoryWindow(await api.history(session.sessionId, { limit: MESSAGES_PER_PAGE }));
      setSelectedPast([]);
    });
  }

  // Fetch the page before the oldest loaded message and prepend it, mirroring the bundled infinite-scroll history.
  async function loadOlder() {
    if (!session) return;
    const oldest = messages[0]?.messageIndex;
    if (oldest === undefined || oldest < 1) { setHasOlder(false); return; }
    const page = await api.history(session.sessionId, { before: oldest, limit: MESSAGES_PER_PAGE });
    setMessages(previous => [...page.messages, ...previous]);
    setHasOlder(page.hasOlder);
  }

  async function stopStory() {
    if (!session) return;
    if (!session.infinite && !window.confirm('Stop this story? The session and all stored story data will be permanently deleted.')) return;
    generation.current?.abort();
    await run(async () => {
      await api.stopSession();
      setSession(null);
      resetStoryData();
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

  // Selecting or pasting an image prepares a sendable copy; the latest selection wins if two overlap.
  async function selectImage(file: File | null) {
    const selection = ++imageSelection.current;
    setImage(null);
    setImageError('');
    if (!file) return;
    try {
      const prepared = await prepareImage(file);
      if (selection !== imageSelection.current) return;
      setImage(prepared);
    } catch (cause) {
      if (selection === imageSelection.current) setImageError(errorMessage(cause));
    }
  }

  // Toggle a past-exchange tick while never exceeding the backend limit of three.
  function togglePast(messageIndex: number) {
    setSelectedPast(previous => previous.includes(messageIndex)
      ? previous.filter(index => index !== messageIndex)
      : previous.length >= 3 ? previous : [...previous, messageIndex]);
  }

  function removeImage() {
    ++imageSelection.current;
    setImage(null);
    setImageError('');
  }

  return <>
    {error && <div className="example-notice" role="alert">
      <p>{error}</p><button onClick={() => setError('')}>Close</button>
    </div>}
    {notification && <NotificationModal {...notification} onClose={() => setNotification(null)} />}
    <p className="example-status" role="status">{busy ? 'Working…' : ''}</p>
    {page === 'start' && <StartPage busy={busy}
      onCreate={title => { void openSession(() => api.createSession(title)); }}
      onResume={id => { void openSession(() => api.resumeSession(id)); }}
      onImport={file => { void openSession(() => api.importSession(file)); }} />}
    {page === 'story' && session && <StoryPage session={session} messages={messages} busy={busy} pending={busyAction}
      streamText={streamText} streamPrompt={streamPrompt}
      prompt={prompt} image={image} imageError={imageError}
      imageUrl={messageIndex => api.imageUrl(session.sessionId, messageIndex)}
      exportUrl={api.exportUrl(session.sessionId)}
      onPromptChange={setPrompt} onSend={() => { void sendPrompt(); }} onUndo={() => { void undo(); }}
      onEditMessage={(messageIndex, content) => { void editMessage(messageIndex, content); }}
      onImageSelect={file => { void selectImage(file); }} onImageRemove={removeImage}
      onSettings={() => { void openSettings(); }} onStop={() => { void stopStory(); }}
      onAtlas={() => { void openAtlas(); }} onInfinite={() => { void toggleInfinite(); }} onHistory={() => { void openHistory(); }}
      referenceBefore={referenceBefore} selectedPast={selectedPast} onTogglePast={togglePast}
      hasOlder={hasOlder} onLoadOlder={loadOlder} />}
    {page === 'settings' && config && <SettingsPage config={config} busy={busy} theme={theme} graphText={graphText}
      onGraphTextChange={setGraphText} onGraphAction={(action, settings) => { void applyGraph(action, settings); }}
      onThemeChange={theme => { setTheme(theme); applyTheme(theme); saveTheme(theme); }}
      onSave={settings => { void saveSettings(settings); }} onBack={() => setPage('story')} />}
    {page === 'atlas' && atlas && <AtlasPage key={atlas.sessionId} atlas={atlas} busy={busy}
      onRefresh={() => { void openAtlas(); }} onBack={() => setPage('story')} />}
    {page === 'history' && state && <HistoryPage state={state} busy={busy}
      onReset={layer => { void resetMemory(layer); }} onBack={() => setPage('story')} />}
  </>;
}

function NotificationModal({ title, message, onClose }: { title: string; message: string; onClose: () => void }) {
  return <div className="notification-modal" role="alertdialog" aria-modal="true"
    aria-labelledby="notification-title" aria-describedby="notification-message">
    <div className="notification-modal-content">
      <p id="notification-title" className="notification-modal-title">{title}</p>
      <p id="notification-message">{message}</p>
      <button autoFocus type="button" onClick={onClose}>Close</button>
    </div>
  </div>;
}
