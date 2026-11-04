import type { MemoryLayer, SessionState } from './api';

interface Props {
  state: SessionState;
  busy: boolean;
  onReset: (layer: MemoryLayer) => void;
  onBack: () => void;
}

export default function HistoryPage({ state, busy, onReset, onBack }: Props) {
  return <main className="settings-page">
    <header className="story-header">
      <div><p className="eyebrow">Storyteller</p><h1>Story history</h1></div>
      <button className="header-action secondary-action" onClick={onBack} disabled={busy}>Back to story</button>
    </header>
    <section className="panel settings-form" aria-busy={busy}>
      <p className="intro">These read-only summaries are generated from older story turns and supplied to subsequent model requests.</p>
      <label htmlFor="recentSummary">Mid-term history · processed messages: {state.recentSummaryCursor}</label>
      <textarea id="recentSummary" rows={12} readOnly value={state.recentSummary}
        placeholder="No mid-term history has been generated yet." />
      <label htmlFor="summary">Long-term history · processed messages: {state.summaryCursor}</label>
      <textarea id="summary" rows={16} readOnly value={state.summary}
        placeholder="No long-term history has been generated yet." />
      <label htmlFor="canonicalState">Canonical state · processed messages: {state.canonicalStateCursor}</label>
      <textarea id="canonicalState" rows={16} readOnly value={state.canonicalState}
        placeholder="No canonical state has been generated yet." />
      <div className="memory-actions" role="group" aria-label="Memory reset actions">
        <span>Memory reset</span>
        <button className="compact-button danger-button" disabled={busy} onClick={() => onReset('mid-term')}>Reset mid-term</button>
        <button className="compact-button danger-button" disabled={busy} onClick={() => onReset('long-term')}>Reset long-term</button>
        <button className="compact-button danger-button" disabled={busy} onClick={() => onReset('canonical-state')}>Reset canonical state</button>
      </div>
    </section>
  </main>;
}
