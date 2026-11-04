import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { loadDefault } from './helpers/modules.mjs';

// Render the real StoryPage (not a copy) so a failure means the shipped layout broke. Effects do not run during static
// rendering, which is fine here: the assertions only cover markup that depends on props, not on browser-only effects.
const StoryPage = loadDefault('StoryPage');

const session = { sessionId: 'session-1', title: 'Voyager', createdAt: '', updatedAt: '', lastAccessedAt: '', expiresAt: '', infinite: false };
const message = (messageIndex, role, overrides = {}) => ({ messageIndex, role, content: `${role} ${messageIndex}`, hasImage: false, ...overrides });
const pair = [message(0, 'user'), message(1, 'assistant')];

function render(overrides = {}) {
  return renderToStaticMarkup(createElement(StoryPage, {
    session, messages: [], busy: false, pending: '', streamText: null, streamPrompt: '', prompt: '',
    image: null, imageError: '', imageUrl: index => `/v1/session-1/images/${index}`, exportUrl: '/v1/session-1/export',
    onPromptChange() {}, onSend() {}, onUndo() {}, onEditMessage() {}, onImageSelect() {}, onImageRemove() {},
    onSettings() {}, onStop() {}, onInfinite() {}, onHistory() {}, onAtlas() {},
    referenceBefore: 0, selectedPast: [], onTogglePast() {}, hasOlder: false, onLoadOlder: async () => {}, ...overrides,
  }));
}

// Return the attributes of the composer button whose text is exactly `label`, or null when that button is absent.
function buttonAttributes(html, label) {
  const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = html.match(new RegExp('<button([^>]*)>' + escaped + '</button>'));
  return match ? match[1] : null;
}
const isDisabled = attributes => attributes !== null && / disabled/.test(attributes);

test('Given a story with saved exchanges, When rendering, Then the empty state is gone and one paired exchange remains', () => {
  const html = render({ messages: pair });
  assert.ok(!html.includes('Begin the story'));
  assert.equal((html.match(/<article class="exchange">/g) ?? []).length, 1);
  assert.ok(html.includes('assistant 1'));
});

for (const [label, overrides, expected] of [
  ['an empty story without a stream', {}, true],
  ['a saved exchange', { messages: pair }, false],
  ['a failed stream without saved exchanges', { streamText: 'Partial' }, false],
]) {
  test(`Given ${label}, When rendering, Then the empty state is ${expected ? 'shown' : 'hidden'}`, () => {
    assert.equal(render(overrides).includes('Begin the story'), expected);
  });
}

test('Given a partial streamed answer, When rendering a failed turn, Then it stays visible and is marked incomplete', () => {
  const html = render({ messages: pair, streamText: 'Partial' });
  assert.ok(html.includes('Incomplete response'));
  assert.ok(html.includes('Partial'));
  assert.equal((html.match(/<article class="exchange">/g) ?? []).length, 2);
});

test('Given a turn still generating, When rendering, Then the partial answer reads as generating rather than incomplete', () => {
  const html = render({ streamText: 'Partial', busy: true });
  assert.ok(html.includes('Generating…'));
  assert.ok(!html.includes('Incomplete response'));
});

test('Given a story without exchanges, When rendering, Then undo is disabled; with an exchange, When rendering, Then it is enabled', () => {
  assert.ok(isDisabled(buttonAttributes(render(), 'Undo')), 'Undo must be disabled with nothing to undo');
  const withExchange = render({ messages: pair });
  assert.ok(!isDisabled(buttonAttributes(withExchange, 'Undo')));
  assert.ok(buttonAttributes(render({ pending: 'undoing' }), 'Undoing…') !== null, 'Undo shows its pending label');
});

for (const [label, overrides, expected] of [
  ['an empty prompt', { prompt: '' }, true],
  ['a filled prompt', { prompt: 'Hello' }, false],
  ['an invalid image', { prompt: 'Hello', imageError: 'Only PNG and JPEG images are supported.' }, true],
]) {
  test(`Given ${label}, When rendering the composer, Then Continue is ${expected ? 'disabled' : 'enabled'}`, () => {
    assert.equal(isDisabled(buttonAttributes(render(overrides), 'Continue story')), expected);
  });
}

test('Given an image error, When rendering, Then the composer reports it as an alert', () => {
  const html = render({ prompt: 'Hello', imageError: 'The image must be between 1 byte and 5 MiB.' });
  assert.ok(html.includes('id="image-error"'));
  assert.ok(html.includes('The image must be between 1 byte and 5 MiB.'));
});

test('Given a writing turn, When rendering, Then the composer button reads Writing', () => {
  assert.ok(buttonAttributes(render({ prompt: 'Hello', pending: 'writing' }), 'Writing…') !== null);
});

test('Given a prompt with an image, When rendering its exchange, Then a linked thumbnail appears; without one, Then it does not', () => {
  const withImage = render({ messages: [message(0, 'user', { hasImage: true }), message(1, 'assistant')] });
  assert.ok(withImage.includes('class="prompt-thumbnail"'));
  assert.ok(withImage.includes('class="prompt-image-link"'));
  assert.ok(withImage.includes('/v1/session-1/images/0'));
  assert.ok(!render({ messages: pair }).includes('class="prompt-thumbnail"'));
});

test('Given a story, When rendering, Then layout toggles expose their pressed state and Export links to the session', () => {
  const html = render();
  assert.equal((html.match(/aria-pressed="false"/g) ?? []).length, 3);
  assert.ok(html.includes('href="/v1/session-1/export"'));
});

test('Given an infinite session, When rendering, Then the header shows its resume ID and marks the session infinite', () => {
  const html = render({ session: { ...session, infinite: true } });
  assert.ok(html.includes('aria-pressed="true"'));
  assert.ok(html.includes('>Use timeout<'));
  assert.ok(html.includes('Resume ID:'));
  assert.ok(html.includes('session-1'));
});

test('Given a story, When rendering the header, Then the actions follow the bundled order Atlas, History, Settings, Infinite, Export, Stop', () => {
  const html = render();
  const order = ['Atlas', 'History', 'Settings', 'Infinite', 'Export', 'Stop story'];
  const positions = order.map(label => html.indexOf(label));
  positions.forEach((position, index) => assert.ok(position >= 0, `${order[index]} must be present`));
  for (let index = 1; index < positions.length; index++) {
    assert.ok(positions[index] > positions[index - 1], `${order[index]} must come after ${order[index - 1]}`);
  }
});

for (const [selectedPast, expected] of [[[], '0/3 past exchanges'], [[0, 2], '2/3 past exchanges']]) {
  test(`Given ${selectedPast.length} selected past exchanges, When rendering the composer, Then the counter reads ${expected}`, () => {
    assert.ok(render({ selectedPast }).includes(expected));
  });
}

test('Given exchanges outside the recent window, When rendering, Then each offers "Use as past context"; when none are, Then it does not', () => {
  assert.ok(render({ messages: pair, referenceBefore: 2 }).includes('Use as past context'));
  assert.ok(!render({ messages: pair, referenceBefore: 0 }).includes('Use as past context'));
});

test('Given three exchanges already selected, When rendering another eligible exchange, Then only the unselected box is disabled', () => {
  const many = [message(0, 'user'), message(1, 'assistant'), message(2, 'user'), message(3, 'assistant'),
    message(4, 'user'), message(5, 'assistant'), message(6, 'user'), message(7, 'assistant')];
  const html = render({ messages: many, referenceBefore: 8, selectedPast: [0, 2, 4] });
  const boxes = html.match(/<input type="checkbox"[^>]*>/g) ?? [];
  assert.equal(boxes.length, 4, 'Every eligible exchange shows a checkbox');
  assert.equal(boxes.filter(box => / disabled/.test(box)).length, 1, 'Only the unselected box is disabled at the limit of three');
});

test('Given the composer, When rendering, Then the counter precedes Undo and its style pushes the buttons to the right', () => {
  const html = render();
  const counter = html.indexOf('class="past-reference-count"');
  const undo = html.indexOf('class="undo-story"');
  assert.ok(counter >= 0 && counter < undo, 'The counter must sit before Undo in the composer');
  const css = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8');
  const rule = css.match(/\.past-reference-count\s*{[^}]*}/);
  assert.ok(rule && /margin-right:\s*auto/.test(rule[0]), 'margin-right:auto right-aligns the Undo and Continue buttons');
});
