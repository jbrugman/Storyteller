import test from 'node:test';
import assert from 'node:assert/strict';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { loadDefault } from './helpers/modules.mjs';

// Render the real SettingsPage to confirm the theme label and the knowledge-graph controls the JTE settings page has.
const SettingsPage = loadDefault('SettingsPage');

const config = {
  sessionId: 'session-1', systemPrompt: 'sys', fixedProtagonists: 'yaml', rules: 'rules',
  temperature: null, effectiveTemperature: 0.7, streamingEnabled: null, validationEnabled: null,
  effectiveStreamingEnabled: true, effectiveValidationEnabled: false,
};

function render(overrides = {}) {
  return renderToStaticMarkup(createElement(SettingsPage, {
    config, busy: false, theme: 'default', graphText: '{\n  "entities": {},\n  "facts": []\n}',
    onGraphTextChange() {}, onGraphAction() {}, onThemeChange() {}, onSave() {}, onBack() {}, ...overrides,
  }));
}

test('Given the theme selector, When rendering, Then the Sci-fi theme is labelled correctly and the misspelling is gone', () => {
  const html = render();
  assert.ok(html.includes('>Sci-fi<'));
  assert.ok(!html.includes('Ski-fi'));
});

test('Given the settings form, When rendering, Then the knowledge graph is editable as JSON', () => {
  const html = render();
  assert.ok(html.includes('Knowledge graph (JSON)'));
  assert.ok(html.includes('id="knowledgeGraph"'));
});

test('Given the settings form, When rendering, Then all three graph tools are present under their own group label', () => {
  const html = render();
  assert.ok(html.includes('>Graph tools<'));
  assert.equal((html.match(/compact-button/g) ?? []).length, 3);
  for (const label of ['Generate empty', 'Fill from protagonists', 'Reset turn-based']) {
    assert.ok(html.includes(`>${label}</button>`), `Expected a "${label}" graph tool`);
  }
});

test('Given a busy session, When rendering, Then every input and action is disabled', () => {
  const html = render({ busy: true });
  assert.ok(html.includes('id="knowledgeGraph"'));
  assert.ok((html.match(/ disabled/g) ?? []).length >= 4, 'Expected disabled inputs and buttons while busy');
});
