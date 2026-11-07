import test from 'node:test';
import assert from 'node:assert/strict';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { loadDefault } from './helpers/modules.mjs';

// Render the real SettingsPage to confirm the theme label and the knowledge-graph controls the JTE settings page has.
const SettingsPage = loadDefault('SettingsPage');

const config = {
  sessionId: 'session-1', systemPrompt: 'sys', fixedProtagonists: 'yaml', rules: 'rules',
  temperature: null, effectiveTemperature: 0.7, streamingEnabled: null, validationEnabled: null, graphEnabled: null,
  effectiveStreamingEnabled: true, effectiveValidationEnabled: false, effectiveGraphEnabled: true,
  maxTokens: null, effectiveMaxTokens: 0,
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

// Extracts the rendered <input ... id="graphEnabled" ...> tag so checked-state assertions stay precise.
const graphToggle = (html) => html.match(/<input[^>]*id="graphEnabled"[^>]*>/)?.[0] ?? '';

test('Given a session with graph use on, When rendering, Then the toggle is checked and sits above the graph editor', () => {
  const html = render();
  const toggle = graphToggle(html);
  assert.ok(toggle.length > 0, 'Expected a knowledge graph toggle');
  assert.ok(toggle.includes('checked'), 'Expected the toggle to be checked when effective graph use is on');
  assert.ok(html.indexOf('id="graphEnabled"') < html.indexOf('id="knowledgeGraph"'), 'Expected the toggle above the graph editor');
});

test('Given a session with graph use off, When rendering, Then the toggle is unchecked', () => {
  const html = render({ config: { ...config, graphEnabled: false, effectiveGraphEnabled: false } });
  const toggle = graphToggle(html);
  assert.ok(toggle.length > 0, 'Expected a knowledge graph toggle');
  assert.ok(!toggle.includes('checked'), 'Expected the toggle to be unchecked when effective graph use is off');
});

test('Given the settings form, When rendering, Then all three graph tools are present under their own group label', () => {
  const html = render();
  assert.ok(html.includes('>Graph tools<'));
  assert.equal((html.match(/compact-button/g) ?? []).length, 3);
  for (const label of ['Generate empty', 'Fill from protagonists', 'Reset turn-based']) {
    assert.ok(html.includes(`>${label}</button>`), `Expected a "${label}" graph tool`);
  }
});

// Extracts the rendered <fieldset class="max-tokens-fieldset"> block so control-state assertions stay precise.
const maxTokensGroup = (html) => html.match(/<fieldset class="max-tokens-fieldset"[\s\S]*?<\/fieldset>/)?.[0] ?? '';
const checkboxIn = (block) => block.match(/<input[^>]*type="checkbox"[^>]*>/)?.[0] ?? '';

test('Given a session that inherits the server response limit, When rendering, Then the limit follows the server default', () => {
  const group = maxTokensGroup(render());
  assert.ok(group.length > 0, 'Expected a response limit group');
  assert.ok(group.includes('Use server default'), 'Expected the server default option');
  assert.ok(checkboxIn(group).includes('checked'), 'Expected the server default to be the default choice');
  assert.ok(group.includes('id="maxTokens"'), 'Expected the response limit input');
  assert.ok(group.match(/<input[^>]*id="maxTokens"[^>]*>/)[0].includes('disabled'),
    'Expected the limit input to be inactive while the server default is used');
});

test('Given a session with its own response limit, When rendering, Then the cap is editable and the default is off', () => {
  const group = maxTokensGroup(render({ config: { ...config, maxTokens: 512, effectiveMaxTokens: 0 } }));
  assert.ok(!checkboxIn(group).includes('checked'), 'An explicit cap must not be hidden behind the server default');
  assert.ok(group.match(/<input[^>]*id="maxTokens"[^>]*>/)[0].includes('value="512"'), 'Expected the stored cap');
});

test('Given a busy session, When rendering, Then every input and action is disabled', () => {
  const html = render({ busy: true });
  assert.ok(html.includes('id="knowledgeGraph"'));
  assert.ok((html.match(/ disabled/g) ?? []).length >= 4, 'Expected disabled inputs and buttons while busy');
});
