import { useEffect, useRef, useState } from 'react';
import type { Session } from './api';
import { pairExchanges } from './story-exchanges';

interface Props {
  session: Session;
  messages: Parameters<typeof pairExchanges>[0];
  busy: boolean;
  pending: '' | 'writing' | 'undoing';
  streamText: string | null;
  streamPrompt: string;
  prompt: string;
  image: File | null;
  imageError: string;
  imageUrl: (messageIndex: number) => string;
  exportUrl: string;
  onPromptChange: (prompt: string) => void;
  onSend: () => void;
  onUndo: () => void;
  onEditMessage: (messageIndex: number, content: string) => void;
  onImageSelect: (file: File | null) => void;
  onImageRemove: () => void;
  onSettings: () => void;
  onStop: () => void;
  onInfinite: () => void;
  onHistory: () => void;
  onAtlas: () => void;
  referenceBefore: number;
  selectedPast: number[];
  onTogglePast: (messageIndex: number) => void;
  hasOlder: boolean;
  onLoadOlder: () => Promise<void>;
}

export default function StoryPage({
  session, messages, busy, pending, streamText, streamPrompt, prompt, image, imageError,
  imageUrl, exportUrl, onPromptChange, onSend, onUndo, onEditMessage, onImageSelect, onImageRemove,
  onSettings, onStop, onInfinite, onHistory, onAtlas, referenceBefore, selectedPast, onTogglePast,
  hasOlder, onLoadOlder,
}: Props) {
  const [single, setSingle] = useState(false);
  const [maximized, setMaximized] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);
  const [lightbox, setLightbox] = useState<string | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const conversation = useRef<HTMLElement>(null);
  const lightboxClose = useRef<HTMLButtonElement>(null);
  const openedLink = useRef<HTMLAnchorElement | null>(null);
  // Infinite-scroll bookkeeping: one page in flight, plus the scroll anchor to keep the view steady after a prepend.
  const loadingOlder = useRef(false);
  const restoreHeight = useRef<number | null>(null);
  const restoreTop = useRef(0);
  // Keep the latest props in refs so the scroll listener can subscribe once and never tear down mid-fetch.
  const hasOlderRef = useRef(hasOlder);
  const onLoadOlderRef = useRef(onLoadOlder);
  hasOlderRef.current = hasOlder;
  onLoadOlderRef.current = onLoadOlder;

  // Scroll to the newest exchange, except right after older pages were prepended, where the prior position is kept.
  useEffect(() => {
    const element = conversation.current;
    if (!element) return;
    if (restoreHeight.current !== null) {
      element.scrollTop = element.scrollHeight - restoreHeight.current + restoreTop.current;
      restoreHeight.current = null;
      restoreTop.current = 0;
      loadingOlder.current = false;
    } else {
      element.scrollTop = element.scrollHeight;
    }
  }, [messages, streamText, single, maximized]);

  // Near the top of a story with older pages, load the previous window once, mirroring the bundled history scroll.
  useEffect(() => {
    const element = conversation.current;
    if (!element) return;
    let cancelled = false;
    const onScroll = async () => {
      if (!hasOlderRef.current || loadingOlder.current || element.scrollTop > 80) return;
      loadingOlder.current = true;
      restoreTop.current = element.scrollTop;
      restoreHeight.current = element.scrollHeight;
      try {
        await onLoadOlderRef.current();
      } catch {
        if (!cancelled) {
          loadingOlder.current = false;
          restoreHeight.current = null;
        }
      }
    };
    element.addEventListener('scroll', onScroll);
    return () => { cancelled = true; element.removeEventListener('scroll', onScroll); };
  }, []);

  // A selected or pasted image gets a preview, released when it is replaced or the screen unmounts.
  useEffect(() => {
    if (!image) { setPreviewUrl(null); return; }
    const url = URL.createObjectURL(image);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [image]);

  useEffect(() => {
    if (lightbox) lightboxClose.current?.focus();
  }, [lightbox]);

  useEffect(() => {
    if (!lightbox) return;
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === 'Escape') closeLightbox(); };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  });

  function closeLightbox() {
    setLightbox(null);
    openedLink.current?.focus();
    openedLink.current = null;
  }

  // Pair by role and adjacent persisted indexes, keeping unmatched messages visible.
  const exchanges = pairExchanges(messages);

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
        <a className="header-action secondary-action export-session" href={exportUrl}>Export</a>
        <button className="stop-story" onClick={onStop} disabled={busy}>Stop story</button>
      </div>
    </header>
    <section className="conversation" ref={conversation} aria-label="Story conversation">
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
      {exchanges.length === 0 && streamText === null && <div className="empty-state">
        <h2>Begin the story</h2>
        <p>Describe the opening scene or tell the storyteller what should happen next.</p>
      </div>}
      {exchanges.map((exchange, index) => <article className="exchange" key={exchange.prompt?.messageIndex ?? exchange.response!.messageIndex}>
        <div className="exchange-prompt">
          <p className="message-role">Prompt</p>
          {exchange.prompt && exchange.prompt.messageIndex < referenceBefore && <label className="past-reference">
            <input type="checkbox" checked={selectedPast.includes(exchange.prompt.messageIndex)}
              disabled={busy || (!selectedPast.includes(exchange.prompt.messageIndex) && selectedPast.length >= 3)}
              onChange={() => onTogglePast(exchange.prompt!.messageIndex)} />
            Use as past context
          </label>}
          {exchange.prompt?.hasImage && <a className="prompt-image-link" href={imageUrl(exchange.prompt.messageIndex)}
            aria-label="Show the full image supplied with this prompt"
            onClick={event => { event.preventDefault(); openedLink.current = event.currentTarget; setLightbox(imageUrl(exchange.prompt!.messageIndex)); }}>
            <img className="prompt-thumbnail" src={imageUrl(exchange.prompt.messageIndex)}
              alt="Image supplied with this prompt" title="Show full image" />
          </a>}
          <div className="message-content">{exchange.prompt?.content}</div>
        </div>
        <div className="exchange-response">
          <div className="response-title-row">
            <p className="message-role">Response</p>
            {exchange.response && <details className="response-edit" open={editing === index}
              onToggle={event => setEditing(event.currentTarget.open ? index : null)}>
              <summary>{editing === index ? 'Cancel' : 'Edit'}</summary>
              <form onSubmit={event => {
                event.preventDefault();
                const content = new FormData(event.currentTarget).get('content');
                if (typeof content === 'string' && content.trim()) {
                  onEditMessage(exchange.response!.messageIndex + 1, content);
                  setEditing(null);
                }
              }}>
                <label htmlFor={`response-edit-${index}`}>Response text</label>
                <textarea id={`response-edit-${index}`} name="content" rows={8} maxLength={100000} required
                  defaultValue={exchange.response.content} disabled={busy} />
                <button type="submit" className="compact-button" disabled={busy}>Save response</button>
              </form>
            </details>}
          </div>
          <div className="message-content">{exchange.response?.content}</div>
        </div>
      </article>)}
      {streamText !== null && <article className="exchange">
        <div className="exchange-prompt message-content">{streamPrompt}</div>
        <div className="exchange-response">
          <p className="message-role" role={busy ? 'status' : 'alert'}>{busy ? 'Generating…' : 'Incomplete response — reload the story before retrying.'}</p>
          <div className="message-content">{streamText}</div>
        </div>
      </article>}
    </section>
    <form className="composer" aria-busy={busy} onSubmit={event => { event.preventDefault(); onSend(); }}>
      <label htmlFor="prompt">What happens next?</label>
      <textarea id="prompt" rows={3} maxLength={100000} required autoFocus value={prompt} disabled={busy}
        onChange={event => onPromptChange(event.target.value)}
        onPaste={event => {
          const item = Array.from(event.clipboardData?.items ?? []).find(entry => entry.kind === 'file' && entry.type.startsWith('image/'));
          const file = item?.getAsFile();
          if (file) { event.preventDefault(); onImageSelect(file); }
        }}
        onKeyDown={event => { if (event.key === 'Enter' && event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); } }} />
      {imageError && <p id="image-error" role="alert">{imageError}</p>}
      <div className="image-preview" hidden={!image && !imageError}>
        {image && <>
          <img src={previewUrl ?? ''} alt="Image ready to send" />
          <span>{image.name} ({Math.ceil(image.size / 1024)} KiB)</span>
          <button type="button" className="remove-image" onClick={onImageRemove} disabled={busy}>Remove</button>
        </>}
        {!image && imageError && <span>Image not attached</span>}
      </div>
      <div className="composer-actions">
        <label className="image-picker" htmlFor="image">Add image</label>
        <input id="image" type="file" accept="image/png,image/jpeg" hidden disabled={busy}
          onChange={event => onImageSelect(event.target.files?.[0] ?? null)} />
        <span className="past-reference-count">{selectedPast.length}/3 past exchanges</span>
        <button type="button" className="undo-story" onClick={onUndo} disabled={busy || exchanges.length === 0}>
          {pending === 'undoing' ? 'Undoing…' : 'Undo'}
        </button>
        <button disabled={busy || !prompt.trim() || !!imageError}>
          {pending === 'writing' ? 'Writing…' : 'Continue story'}
        </button>
      </div>
    </form>
    {lightbox && <div className="image-lightbox" role="dialog" aria-modal="true" aria-label="Prompt image"
      onClick={event => { if (event.target === event.currentTarget) closeLightbox(); }}>
      <div className="image-lightbox-content">
        <button ref={lightboxClose} className="image-lightbox-close" type="button" aria-label="Close full image" onClick={closeLightbox}>Close</button>
        <img src={lightbox} alt="Full image supplied with this prompt" />
      </div>
    </div>}
  </main>;
}
