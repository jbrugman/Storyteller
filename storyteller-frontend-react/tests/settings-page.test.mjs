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
  contextMode: null, effectiveContextMode: 'turns', contextWindow: null, effectiveContextWindow: 4096,
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

// Extracts the rendered <fieldset class="context-fieldset"> block so control-state assertions stay precise.
const contextGroup = (html) => html.match(/<fieldset class="context-fieldset"[\s\S]*?<\/fieldset>/)?.[0] ?? '';
const contextModeSelect = (block) => block.match(/<select[^>]*id="contextMode"[\s\S]*?<\/select>/)?.[0] ?? '';
const contextWindowInput = (block) => block.match(/<input[^>]*id="contextWindow"[^>]*>/)?.[0] ?? '';
// The value of the option React marked selected; the empty (Server default) option yields "".
const selectedMode = (block) => block.match(/<option value="([^"]*)" selected/)?.[1] ?? null;

test('Given the settings form, When rendering, Then the context window group offers the mode select and the window input', () => {
  const group = contextGroup(render());
  assert.ok(group.length > 0, 'Expected a context window group');
  assert.ok(group.includes('<legend>Context window</legend>'), 'Expected the Context window legend');
  assert.ok(group.includes('id="contextMode"'), 'Expected the context mode select');
  for (const label of ['Server default', 'Turns', 'Rolling window']) {
    assert.ok(group.includes(`>${label}</option>`), `Expected the "${label}" mode option`);
  }
  assert.ok(contextModeSelect(group).length > 0, 'Expected the mode select to render its options');
  assert.ok(group.includes('id="contextWindow"'), 'Expected the context window input');
  assert.ok(/<input[^>]*id="contextWindow"[^>]*max="100000000"/.test(group), 'Expected the window input to allow up to 100000000');
});

test('Given a session that inherits the context settings, When rendering, Then the mode is on Server default and the window input is inactive', () => {
  const group = contextGroup(render());
  assert.equal(selectedMode(group), '', 'Expected the Server default mode to be selected');
  assert.ok(checkboxIn(group).includes('checked'), 'Expected the window server default to be the default choice');
  assert.ok(contextWindowInput(group).includes('disabled'),
    'Expected the window input to be inactive while the server default is used');
});

test('Given a session that chose the rolling window with its own size, When rendering, Then both values show and the window stays editable', () => {
  const group = contextGroup(render({ config: { ...config, contextMode: 'rolling', contextWindow: 200000, effectiveContextMode: 'rolling', effectiveContextWindow: 200000 } }));
  assert.equal(selectedMode(group), 'rolling', 'Expected the rolling mode to be selected');
  assert.ok(!checkboxIn(group).includes('checked'), 'An explicit window must not sit behind the server default');
  assert.ok(contextWindowInput(group).includes('value="200000"'), 'Expected the stored window size');
  assert.ok(!contextWindowInput(group).includes('disabled'), 'Expected the window input to stay editable');
});
