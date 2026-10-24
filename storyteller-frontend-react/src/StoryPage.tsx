import { useEffect, useRef, useState } from 'react';
import type { Session, StoryMessage } from './api';

interface Props {
  session: Session;
  messages: StoryMessage[];
  busy: boolean;
  prompt: string;
  onPromptChange: (prompt: string) => void;
  onSend: () => void;
  onSettings: () => void;
  onStop: () => void;
  onInfinite: () => void;
  onHistory: () => void;
  onAtlas: () => void;
}

export default function StoryPage({ session, messages, busy, prompt, onPromptChange, onSend, onSettings, onStop, onInfinite, onHistory, onAtlas }: Props) {
  const [single, setSingle] = useState(false);
  const [maximized, setMaximized] = useState(false);
  const conversation = useRef<HTMLElement>(null);
  useEffect(() => {
    const element = conversation.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [messages]);

  // Pair by role and adjacent persisted indexes, keeping unmatched messages visible.
  const exchanges: { prompt?: StoryMessage; response?: StoryMessage }[] = [];
  for (const message of messages) {
    const previous = exchanges[exchanges.length - 1];
    if (message.role === 'assistant' && previous?.prompt && !previous.response &&
      previous.prompt.messageIndex + 1 === message.messageIndex) previous.response = message;
    else exchanges.push(message.role === 'user' ? { prompt: message } : { response: message });
  }

  return <main className={`story-page${single ? ' single-column' : ''}${maximized ? ' response-maximized' : ''}`}>
    <header className="story-header">
      <div>
        <p className="eyebrow">Storyteller</p>
        <h1>{session.title || 'Untitled story'}</h1>
        {session.infinite && <p className="resume-session-id">Resume ID: <code>{session.sessionId}</code></p>}
      </div>
      <div className="session-actions">
        <button className="header-action secondary-action" onClick={onAtlas} disabled={busy}>Atlas</button>
        <button className="header-action secondary-action" onClick={onHistory} disabled={busy}>History</button>
        <button className="header-action secondary-action" onClick={onSettings} disabled={busy}>Settings</button>
        <button className="infinite-session" aria-pressed={session.infinite} onClick={onInfinite} disabled={busy}>
          {session.infinite ? 'Use timeout' : 'Infinite'}
        </button>
        <button className="stop-story" onClick={onStop} disabled={busy}>Stop story</button>
      </div>
    </header>
    <section className="conversation" ref={conversation} aria-label="Story conversation" aria-live="polite">
      <div className="conversation-heading">
        <span>Prompt</span>
        <span className="response-heading">
          <span className="response-label">Response</span>
          <span className="heading-actions">
            <button type="button" aria-pressed={single} onClick={() => setSingle(!single)}>{single ? 'Split' : 'Single'}</button>
            <button type="button" aria-pressed={maximized} onClick={() => setMaximized(!maximized)}>{maximized ? 'Restore' : 'Maximize'}</button>
          </span>
        </span>
      </div>
      {exchanges.length === 0 && <div className="empty-state">
        <h2>Begin the story</h2>
        <p>Describe the opening scene or tell the storyteller what should happen next.</p>
      </div>}
      {exchanges.map(exchange => <article className="exchange" key={exchange.prompt?.messageIndex ?? exchange.response!.messageIndex}>
        <div className="exchange-prompt">
          <p className="message-role">Prompt</p>
          <div className="message-content">{exchange.prompt?.content}</div>
          {exchange.prompt?.hasImage && <p className="setting-hint">This prompt contains an image. This example displays text only.</p>}
        </div>
        <div className="exchange-response">
          <p className="message-role">Response</p>
          <div className="message-content">{exchange.response?.content}</div>
        </div>
      </article>)}
    </section>
    <form className="composer" aria-busy={busy} onSubmit={event => { event.preventDefault(); onSend(); }}>
      <label htmlFor="prompt">What happens next?</label>
      <textarea id="prompt" rows={3} maxLength={100000} required autoFocus value={prompt}
        onChange={event => onPromptChange(event.target.value)} disabled={busy} />
      <div className="composer-actions"><button disabled={busy || !prompt.trim()}>Continue story</button></div>
    </form>
  </main>;
}
