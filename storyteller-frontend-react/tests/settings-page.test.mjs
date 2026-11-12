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

function render(overrides = {}, configOverrides = {}) {
  return renderToStaticMarkup(createElement(SettingsPage, {
    config: { ...config, ...configOverrides },
    busy: false, theme: 'default', graphText: '{\n  "entities": {},\n  "facts": []\n}',
    onGraphTextChange() {}, onGraphAction() {}, onThemeChange() {}, onSave() {}, onBack() {}, ...overrides,
  }));
}

test('Given the theme selector, When rendering, Then the Sci-fi theme is labelled correctly and the misspelling is gone', () => {
  const html = render();
  assert.ok(html.includes('>Sci-fi<'));
  assert.ok(!html.includes('Ski-fi'));
});

// The stamp answers "which build serves this page?", so it has to be the version the server reports.
test('Given a server that reports its version, When rendering, Then the header carries that version', () => {
  assert.ok(render({}, { appVersion: '2.2.0' }).includes('<p class="eyebrow">Storyteller 2.2.0</p>'));
});

test('Given a server that predates the version stamp, When rendering, Then the header stays plain Storyteller', () => {
  assert.ok(render().includes('<p class="eyebrow">Storyteller</p>'));
});

test('Given the settings form, When rendering, Then the knowledge graph is editable as JSON', () => {
  const html = render();
  assert.ok(html.includes('Knowledge graph (JSON)'));
  assert.ok(html.includes('id="knowledgeGraph"'));
});

// Extracts the rendered <input ... id="graphEnabled" ...> tag so checked-state assertions stay precise.
const graphToggle = (html) => html.match(/<input[^>]*id="graphEnabled"[^>]*>/)?.[0] ?? '';

test('Given a session with graph use on, When rendering, Then the toggle is checked', () => {
  const html = render();
  const toggle = graphToggle(html);
  assert.ok(toggle.length > 0, 'Expected a knowledge graph toggle');
  assert.ok(toggle.includes('checked'), 'Expected the toggle to be checked when effective graph use is on');
});

// The graph belongs together: what it holds, whether it is used, and the tools acting on it, before unrelated settings.
test('Given the settings form, When rendering, Then the knowledge graph group comes before response generation', () => {
  const html = render();
  const order = ['id="knowledgeGraph"', 'id="graphEnabled"', '>Graph tools<', '<legend>Response generation</legend>'];
  const positions = order.map(marker => html.indexOf(marker));
  assert.ok(positions.every(position => position >= 0), `Expected every group to render, got ${JSON.stringify(positions)}`);
  for (let index = 1; index < positions.length; index += 1) {
    assert.ok(positions[index - 1] < positions[index],
      `Expected "${order[index - 1]}" before "${order[index]}"`);
  }
});

// The tools act on the graph, so they belong inside its block instead of beside it.
test('Given the settings form, When rendering, Then the graph tools sit inside the knowledge graph block', () => {
  const html = render();
  const block = html.slice(html.indexOf('id="graphEnabled"'), html.indexOf('<legend>Response generation</legend>'));
  const tools = block.indexOf('class="graph-tools"');
  const closed = block.indexOf('</fieldset>');
  assert.ok(tools >= 0 && closed >= 0 && tools < closed,
    `Expected the tools inside the block, got tools=${tools} and the block closing at ${closed}`);
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
// The value of the option React marked selected, which is always one of the two concrete modes.
const selectedMode = (block) => block.match(/<option value="([^"]*)" selected/)?.[1] ?? null;

test('Given the settings form, When rendering, Then the context window group offers the mode select and the window input', () => {
  const group = contextGroup(render());
  assert.ok(group.length > 0, 'Expected a context window group');
  assert.ok(group.includes('<legend>Context window</legend>'), 'Expected the Context window legend');
  assert.ok(group.includes('id="contextMode"'), 'Expected the context mode select');
  const select = contextModeSelect(group);
  assert.ok(select.length > 0, 'Expected the mode select to render its options');
  assert.deepEqual([...select.matchAll(/<option value="([^"]*)"[^>]*>([^<]*)<\/option>/g)]
    .map(match => match[2]), ['Turns (default)', 'Rolling window'],
  'A story is always on turns or on rolling, so those two are the only choices');
  assert.ok(!select.includes('<option value=""'), 'Expected no third choice to inherit, because turns always works');
  assert.ok(group.includes('id="contextWindow"'), 'Expected the context window input');
  assert.ok(/<input[^>]*id="contextWindow"[^>]*max="100000000"/.test(group), 'Expected the window input to allow up to 100000000');
});

test('Given a session that follows the server onto turns, When rendering, Then turns is selected and the size cannot be edited', () => {
  const group = contextGroup(render());
  assert.equal(selectedMode(group), 'turns', 'Expected the mode in effect to be selected, with no inherit option hiding it');
  assert.ok(checkboxIn(group).includes('checked'), 'Expected the window server default to be the default choice');
  assert.ok(checkboxIn(group).includes('disabled'),
    'Expected the server default choice to be inactive while turns ignores the size');
  assert.ok(contextWindowInput(group).includes('disabled'),
    'Expected the size to be grayed out while turns mode does not plan against it');
});

// A session that names rolling itself has to name the size, so the form says so where the size belongs.
const saveButton = (html) => html.match(/<button[^>]*>Save settings<\/button>/)?.[0] ?? '';

test('Given rolling mode without any known window, When rendering, Then the form starts on the recommended size', () => {
  const html = render({ config: { ...config, contextMode: 'rolling', effectiveContextMode: 'rolling', effectiveContextWindow: 0 } });
  const group = contextGroup(html);
  const window = contextWindowInput(group);
  assert.ok(window.includes('value="32768"'), 'Expected the recommended starting size in the field');
  assert.ok(!window.includes('disabled'), 'Expected the size to be editable while rolling plans against it');
  assert.ok(!group.includes('id="context-error"'), 'Expected no complaint about a size the form already supplied');
  assert.ok(!saveButton(html).includes('disabled'), 'Expected saving to stay available once a size is there');
});

// A size can still go missing, because a session may have stored rolling with an unknown size.
test('Given a session that stored rolling mode with an unknown size, When rendering, Then saving is blocked until a size is named', () => {
  const html = render({ config: { ...config, contextMode: 'rolling', contextWindow: 0, effectiveContextMode: 'rolling', effectiveContextWindow: 0 } });
  const group = contextGroup(html);
  assert.ok(group.includes('id="context-error"'), 'Expected the error next to the context fields');
  assert.ok(group.includes('32768'), 'Expected the recommended starting size');
  const window = contextWindowInput(group);
  assert.ok(window.includes('aria-invalid="true"'), 'Expected the window input to be marked');
  assert.ok(window.includes('required'), 'Expected the window input to be required');
  assert.ok(window.includes('min="1"'), 'Expected the window input to reject zero while rolling needs a size');
  assert.ok(saveButton(html).includes('disabled'), 'Expected saving to be blocked while the size is missing');
  assert.equal((html.match(/<button[^>]*compact-button[^>]*disabled/g) ?? []).length, 3,
    'Expected the graph tools to be blocked in the same way as saving');
});

test('Given rolling mode while the server knows a window, When rendering, Then nothing is required and saving stays available', () => {
  const html = render({ config: { ...config, contextMode: 'rolling', effectiveContextMode: 'rolling' } });
  const group = contextGroup(html);
  assert.ok(!group.includes('id="context-error"'), 'Expected no error while the effective size is known');
  assert.ok(!contextWindowInput(group).includes('required'), 'Expected the field to stay optional when the server supplies a size');
  assert.ok(!saveButton(html).includes('disabled'), 'Expected saving to stay available');
  assert.equal((html.match(/<button[^>]*compact-button[^>]*disabled/g) ?? []).length, 0,
    'Expected the graph tools to stay available');
});

test('Given turns mode without a window, When rendering, Then the context window stays optional', () => {
  const html = render({ config: { ...config, effectiveContextWindow: 0 } });
  const group = contextGroup(html);
  assert.ok(!group.includes('id="context-error"'), 'Expected turns mode to accept an unknown size');
  assert.ok(!contextWindowInput(group).includes('required'), 'Expected no requirement in turns mode');
  assert.ok(contextWindowInput(group).includes('disabled'), 'Expected turns mode to gray out a size it does not use');
  assert.ok(!saveButton(html).includes('disabled'), 'Expected saving to stay available');
});

test('Given a session that chose the rolling window with its own size, When rendering, Then both values show and the window stays editable', () => {
  const group = contextGroup(render({ config: { ...config, contextMode: 'rolling', contextWindow: 200000, effectiveContextMode: 'rolling', effectiveContextWindow: 200000 } }));
  assert.equal(selectedMode(group), 'rolling', 'Expected the rolling mode to be selected');
  assert.ok(!checkboxIn(group).includes('checked'), 'An explicit window must not sit behind the server default');
  assert.ok(contextWindowInput(group).includes('value="200000"'), 'Expected the stored window size');
  assert.ok(!contextWindowInput(group).includes('disabled'), 'Expected the window input to stay editable');
});
