import { useState } from 'react';
import type { SessionConfig } from './api';
import type { Theme } from './theme';

export type EditableSettings = Pick<SessionConfig, 'systemPrompt' | 'fixedProtagonists' | 'rules' | 'temperature'>;
interface Props {
  config: SessionConfig;
  busy: boolean;
  theme: Theme;
  onThemeChange: (theme: Theme) => void;
  onSave: (settings: EditableSettings) => void;
  onBack: () => void;
}

export default function SettingsPage({ config, busy, theme, onThemeChange, onSave, onBack }: Props) {
  const [systemPrompt, setSystemPrompt] = useState(config.systemPrompt);
  const [fixedProtagonists, setFixedProtagonists] = useState(config.fixedProtagonists);
  const [rules, setRules] = useState(config.rules);
  const [useDefault, setUseDefault] = useState(config.temperature === null);
  const [temperature, setTemperature] = useState(config.temperature ?? config.effectiveTemperature ?? 1);
  return <main className="settings-page">
    <header className="story-header">
      <div><p className="eyebrow">Storyteller</p><h1>Story settings</h1></div>
      <button className="header-action secondary-action" onClick={onBack} disabled={busy}>Back to story</button>
    </header>
    <section className="panel settings-form theme-settings" aria-label="Appearance">
      <label htmlFor="theme">Theme</label>
      <select id="theme" value={theme} onChange={event => onThemeChange(event.target.value as Theme)}>
        <option value="default">Default</option>
        <option value="dark-sci-fi">Dark sci-fi</option>
        <option value="nature">Nature</option>
        <option value="ocean">Ocean</option>
      </select>
      <p className="setting-hint">Applies immediately and is remembered in this browser for one year when cookies are enabled.</p>
    </section>
    <form className="panel settings-form" onSubmit={event => {
      event.preventDefault();
      onSave({ systemPrompt, fixedProtagonists, rules, temperature: useDefault ? null : temperature });
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
      <div className="settings-actions"><button disabled={busy}>Save settings</button></div>
    </form>
  </main>;
}
