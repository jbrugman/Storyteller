import { useState } from 'react';
import type { SessionConfig } from './api';
import type { Theme } from './theme';
import { graphHasData } from './knowledge-graph';
import type { GraphAction } from './knowledge-graph';

export type EditableSettings = Pick<SessionConfig, 'systemPrompt' | 'fixedProtagonists' | 'rules' | 'temperature' | 'streamingEnabled' | 'validationEnabled' | 'graphEnabled' | 'maxTokens' | 'contextMode' | 'contextWindow'>;
interface Props {
  config: SessionConfig;
  busy: boolean;
  theme: Theme;
  graphText: string;
  onGraphTextChange: (graph: string) => void;
  onGraphAction: (action: GraphAction, settings: EditableSettings) => void;
  onThemeChange: (theme: Theme) => void;
  onSave: (settings: EditableSettings) => void;
  onBack: () => void;
}

// Mirrors SessionSettingsService.RECOMMENDED_CONTEXT_WINDOW_TOKENS: the size offered to a session that
// chooses rolling without knowing its model.
const RECOMMENDED_CONTEXT_WINDOW_TOKENS = 32_768;

// Mirrors the bundled graph tools, which only warn before replacing data that already exists.
const graphWarnings: Record<GraphAction, string> = {
  reset: 'Remove all turn-based facts and entities? Fixed and manual graph data will be kept.',
  fill: 'The knowledge graph already contains data. Replace it with facts generated from Fixed protagonists?',
  generate: 'The knowledge graph already contains data. Replace it with a minimal empty graph?',
};

export default function SettingsPage({ config, busy, theme, graphText, onGraphTextChange, onGraphAction, onThemeChange, onSave, onBack }: Props) {
  const [systemPrompt, setSystemPrompt] = useState(config.systemPrompt);
  const [fixedProtagonists, setFixedProtagonists] = useState(config.fixedProtagonists);
  const [rules, setRules] = useState(config.rules);
  const [streamingEnabled, setStreamingEnabled] = useState(config.effectiveStreamingEnabled);
  const [validationEnabled, setValidationEnabled] = useState(config.effectiveValidationEnabled);
  const [graphEnabled, setGraphEnabled] = useState(config.effectiveGraphEnabled);
  const [useDefault, setUseDefault] = useState(config.temperature === null);
  const [temperature, setTemperature] = useState(config.temperature ?? config.effectiveTemperature ?? 1);
  const [useDefaultTokens, setUseDefaultTokens] = useState(config.maxTokens === null);
  const [maxTokens, setMaxTokens] = useState(config.maxTokens ?? config.effectiveMaxTokens ?? 0);
  const serverWindow = config.effectiveContextWindow ?? 0;
  // The form always names a mode: turns or rolling. An unset mode is the one the server started the session on.
  const [contextMode, setContextMode] = useState(config.contextMode ?? config.effectiveContextMode ?? 'turns');
  // Rolling without any known size cannot plan, so it starts on the recommended size instead of an empty field.
  const needsASize = (config.contextMode ?? config.effectiveContextMode ?? 'turns') === 'rolling'
    && (config.contextWindow ?? serverWindow) <= 0;
  const [useDefaultWindow, setUseDefaultWindow] = useState(config.contextWindow === null && !needsASize);
  const [contextWindow, setContextWindow] = useState(
    config.contextWindow ?? (needsASize ? RECOMMENDED_CONTEXT_WINDOW_TOKENS : serverWindow));
  const windowInEffect = useDefaultWindow ? serverWindow : contextWindow;
  const rolling = contextMode === 'rolling';
  const contextMissing = rolling && windowInEffect <= 0;
  const chooseContextMode = (mode: string) => {
    setContextMode(mode);
    if (mode === 'rolling' && windowInEffect <= 0) {
      setUseDefaultWindow(false);
      setContextWindow(RECOMMENDED_CONTEXT_WINDOW_TOKENS);
    }
  };
  const currentSettings = (): EditableSettings => ({
    systemPrompt, fixedProtagonists, rules, temperature: useDefault ? null : temperature, streamingEnabled, validationEnabled, graphEnabled,
    maxTokens: useDefaultTokens ? null : maxTokens,
    contextMode,
    contextWindow: useDefaultWindow ? null : contextWindow,
  });
  const contextError = `Rolling context needs the model's context window before it can be saved. Give it the model's size in tokens, or ${RECOMMENDED_CONTEXT_WINDOW_TOKENS} to start with, and a model that reserves room for its reasoning counted in that size too.`;
  const runGraphAction = (action: GraphAction) => {
    if (!graphHasData(graphText) || window.confirm(graphWarnings[action])) onGraphAction(action, currentSettings());
  };
  return <main className="settings-page">
    <header className="story-header">
      <div><p className="eyebrow">Storyteller{config.appVersion ? ` ${config.appVersion}` : ''}</p><h1>Story settings</h1></div>
      <button className="header-action secondary-action" onClick={onBack} disabled={busy}>Back to story</button>
    </header>
    <section className="panel settings-form theme-settings" aria-label="Appearance">
      <label htmlFor="theme">Theme</label>
      <select id="theme" value={theme} onChange={event => onThemeChange(event.target.value as Theme)}>
        <option value="default">Default</option>
        <option value="dark-sci-fi">Sci-fi</option>
        <option value="nature">Nature</option>
        <option value="ocean">Ocean</option>
        <option value="passion">Passion</option>
        <option value="summer">Summer</option>
        <option value="evening">Evening</option>
      </select>
      <p className="setting-hint">Applies immediately and is remembered in this browser for one year when cookies are enabled.</p>
    </section>
    <form className="panel settings-form" onSubmit={event => {
      event.preventDefault();
      onSave(currentSettings());
    }}>
      <p className="intro">These prompts belong to this session and are used for subsequent story turns and validation.</p>
      <label htmlFor="systemPrompt">System prompt</label>
      <textarea id="systemPrompt" rows={12} maxLength={1000000} required value={systemPrompt}
        onChange={event => setSystemPrompt(event.target.value)} disabled={busy} />
      <label htmlFor="fixedProtagonists">Fixed protagonists (YAML)</label>
      <textarea id="fixedProtagonists" rows={12} maxLength={1000000} required value={fixedProtagonists}
        onChange={event => setFixedProtagonists(event.target.value)} disabled={busy} />
      <label htmlFor="rules">Rules</label>
      <textarea id="rules" rows={12} maxLength={1000000} required value={rules}
        onChange={event => setRules(event.target.value)} disabled={busy} />
      <label htmlFor="knowledgeGraph">Knowledge graph (JSON)</label>
      <textarea id="knowledgeGraph" rows={18} maxLength={1000000} required value={graphText}
        onChange={event => onGraphTextChange(event.target.value)} disabled={busy} />
      <fieldset disabled={busy}>
        <legend>Knowledge graph</legend>
        <label><input type="checkbox" id="graphEnabled" checked={graphEnabled} onChange={event => setGraphEnabled(event.target.checked)} />Use the knowledge graph</label>
        <p className="setting-hint">When on, facts from the graph above ground story generation and validation, and each turn updates the graph automatically. The graph tools below always stay available. When off, the story ignores the graph and stops updating it; stored facts are kept. Defaults to the server's graph.enabled setting and is saved per session in the database.</p>
        <div className="graph-tools" aria-label="Knowledge graph actions">
          <span>Graph tools</span>
          <button type="button" className="compact-button" disabled={busy || contextMissing} onClick={() => runGraphAction('generate')}>Generate empty</button>
          <button type="button" className="compact-button" disabled={busy || contextMissing} onClick={() => runGraphAction('fill')}>Fill from protagonists</button>
          <button type="button" className="compact-button danger-button" disabled={busy || contextMissing} onClick={() => runGraphAction('reset')}>Reset turn-based</button>
        </div>
      </fieldset>
      <fieldset disabled={busy}>
        <legend>Response generation</legend>
        <label><input type="checkbox" checked={streamingEnabled} onChange={event => {
          setStreamingEnabled(event.target.checked); if (event.target.checked) setValidationEnabled(false);
        }} />Streaming</label>
        <label><input type="checkbox" checked={validationEnabled} onChange={event => {
          setValidationEnabled(event.target.checked); if (event.target.checked) setStreamingEnabled(false);
        }} />Validation</label>
        <p className="setting-hint">Streaming requires validation to be off. Enabling either option turns the other off. Both may be off. Saved per session in the database.</p>
      </fieldset>
      <fieldset className="temperature-fieldset" disabled={busy}>
        <label htmlFor="temperature">Chat temperature</label>
        <p className="setting-hint">Controls randomness in the story. Lower values are more deterministic, higher values are more creative. Range: 0 (exact) to 2 (maximally diverse).</p>
        <label className="example-default"><input type="checkbox" checked={useDefault}
          onChange={event => setUseDefault(event.target.checked)} />Use server default</label>
        <div className="temperature-control">
          <input type="range" id="temperature" min={0} max={2} step={0.01} value={temperature}
            disabled={useDefault} onChange={event => setTemperature(Number(event.target.value))} />
          <output htmlFor="temperature">{useDefault ? 'default' : temperature.toFixed(2)}</output>
        </div>
      </fieldset>
      <fieldset className="max-tokens-fieldset" disabled={busy}>
        <label htmlFor="maxTokens">Maximum response tokens</label>
        <p className="setting-hint">Limits how many tokens one response may use. Only a limit can stop a response mid-sentence, so lower it deliberately on a smaller model. 0 means no limit. Range: 0 to 1000000.</p>
        <label className="example-default"><input type="checkbox" checked={useDefaultTokens}
          onChange={event => setUseDefaultTokens(event.target.checked)} />Use server default</label>
        <input type="number" id="maxTokens" min={0} max={1000000} step={1} value={maxTokens}
          disabled={useDefaultTokens} onChange={event => setMaxTokens(Number(event.target.value))} />
      </fieldset>
      <fieldset className="context-fieldset" disabled={busy}>
        <legend>Context window</legend>
        <label htmlFor="contextMode">Recent story sent verbatim</label>
        <select id="contextMode" value={contextMode} onChange={event => chooseContextMode(event.target.value)}>
          <option value="turns">Turns (default)</option>
          <option value="rolling">Rolling window</option>
        </select>
        <p className="setting-hint">Turns sends the configured number of recent turns. Rolling fills the model's context window with as many recent turns as still fit: the prompts, canonical state, summaries and this turn always go out, and the older story reaches the model through its summaries. A session that chooses rolling has to name its size below. Saved per session in the database.</p>
        <label htmlFor="contextWindow">Model context window (tokens)</label>
        <label className="example-default"><input type="checkbox" checked={useDefaultWindow} disabled={!rolling}
          onChange={event => setUseDefaultWindow(event.target.checked)} />Use server default</label>
        {contextMissing && <p id="context-error" className="settings-error" role="alert">{contextError}</p>}
        <input type="number" id="contextWindow" min={contextMissing ? 1 : 0} max={100000000} step={1} value={contextWindow}
          disabled={!rolling || useDefaultWindow} required={contextMissing} aria-invalid={contextMissing}
          aria-describedby={contextMissing ? 'context-error' : undefined}
          onChange={event => setContextWindow(Number(event.target.value))} />
        <p className="setting-hint">Rolling plans the story against this size, so a session on rolling has to name one; {RECOMMENDED_CONTEXT_WINDOW_TOKENS} is a workable start for a smaller local model, and a model that reserves room for its reasoning counts that room in the size too. Turns mode ignores the size, so this field is only editable for rolling and a stored size is kept for when rolling comes back. An empty field follows the server size. Range: 0 to 100000000. Saved per session in the database.</p>
      </fieldset>
      <div className="settings-actions"><button disabled={busy || contextMissing}>Save settings</button></div>
    </form>
  </main>;
}
