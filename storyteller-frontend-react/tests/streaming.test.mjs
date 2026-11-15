import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { loadModule } from './helpers/modules.mjs';
const { api } = loadModule('api');

const source = await readFile(new URL('../../storyteller-api/src/main/resources/public/story-stream.js', import.meta.url), 'utf8');
const frame = (event, value) => `event: ${event}\ndata: ${JSON.stringify(value)}\n\n`;

function responseFor(text) {
  const bytes = new TextEncoder().encode(text);
  return new Response(new ReadableStream({
    start(controller) {
      // Split frames and multibyte characters at every byte boundary.
      for (const byte of bytes) controller.enqueue(Uint8Array.of(byte));
      controller.close();
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } });
}

class Element {
  children = [];
  dataset = {};
  textContent = '';
  append(...nodes) {
    for (const node of nodes) { node.parent = this; this.children.push(node); }
  }
  remove() {
    if (this.parent) this.parent.children = this.parent.children.filter(node => node !== this);
  }
  setAttribute(name, value) { this[name] = value; }
  querySelector(selector) {
    return this.children.find(node => selector === '[data-stream-preview]'
      ? node.dataset.streamPreview === 'true' : node.className === selector.slice(1)) ?? null;
  }
}

for (const frontend of ['React', 'server-rendered web']) {
  async function generate(text, onText) {
    const fetch = async () => responseFor(text);
    if (frontend === 'React') {
      const original = globalThis.fetch;
      globalThis.fetch = fetch;
      try { await api.streamTurn('session', 'Hello', onText); }
      finally { globalThis.fetch = original; }
    } else {
      const conversation = new Element();
      const context = vm.createContext({ window: {}, document: { createElement: () => new Element() }, fetch, TextDecoder });
      vm.runInContext(source, context);
      try { await context.window.StoryStreaming.generate('/stream', new Map([['prompt', 'Hello']]), conversation); }
      finally { onText(conversation.children[0].children[1].children[1].textContent); }
    }
  }

  test(`Given fragmented UTF-8 events, When ${frontend} reads a stream, Then the final visible text is reconstructed`, async () => {
    const received = [];
    await generate(frame('text', { text: 'Hé' }) + frame('text', { text: 'Héllo <script>' })
      + frame('done', { response: 'Héllo <script>' }), text => received.push(text));
    assert.equal(received.at(-1), 'Héllo <script>');
    if (frontend === 'React') assert.equal(received[0], 'Hé');
  });

  test(`Given a truncated stream, When ${frontend} reaches EOF, Then it keeps the partial text and reports failure`, async () => {
    const received = [];
    await assert.rejects(generate(frame('text', { text: 'Partial' }), text => received.push(text)), /before completion/);
    assert.equal(received.at(-1), 'Partial');
  });

  test(`Given a backend error event, When ${frontend} reads it, Then it reports the error and keeps the preview`, async () => {
    const received = [];
    await assert.rejects(generate(frame('text', { text: 'Partial' }) + frame('error', { message: 'Generation failed' }),
      text => received.push(text)), /Generation failed/);
    assert.equal(received.at(-1), 'Partial');
  });
}

for (const [failure, fetch] of [
  ['an unavailable server', async () => { throw new Error('Network unavailable'); }],
  ['an HTTP error', async () => new Response('{}', { status: 502 })],
  ['an error event before text', async () => responseFor(frame('error', { message: 'Generation failed' }))],
  ['whitespace followed by an error', async () => responseFor(frame('text', { text: '  \n ' }) + frame('error', { message: 'Generation failed' }))],
  ['a stream closed before text', async () => responseFor('')],
]) {
  test(`Given saved story text, When JTE receives ${failure}, Then only the empty temporary exchange is removed`, async () => {
    const conversation = new Element();
    const saved = new Element();
    saved.className = 'exchange';
    saved.textContent = 'Last saved response';
    conversation.append(saved);
    const context = vm.createContext({ window: {}, document: { createElement: () => new Element() }, fetch, TextDecoder });
    vm.runInContext(source, context);
    await assert.rejects(context.window.StoryStreaming.generate('/stream', new Map([['prompt', 'Foo bar baz']]), conversation));
    assert.deepEqual(conversation.children, [saved]);
    assert.equal(saved.textContent, 'Last saved response');
    assert.equal(conversation.querySelector('[data-stream-preview]'), null);
  });
}

test('Given an empty story, When JTE generation fails without text, Then the empty state is restored without a failed exchange', async () => {
  const conversation = new Element();
  const empty = new Element();
  empty.className = 'empty-state';
  empty.hidden = false;
  conversation.append(empty);
  const context = vm.createContext({ window: {}, document: { createElement: () => new Element() },
    fetch: async () => responseFor(frame('error', { message: 'Generation failed' })), TextDecoder });
  vm.runInContext(source, context);
  await assert.rejects(context.window.StoryStreaming.generate('/stream', new Map([['prompt', 'Hello']]), conversation));
  assert.deepEqual(conversation.children, [empty]);
  assert.equal(empty.hidden, false);
});

const jsonResponse = body => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
function streamEnabled(fetchImpl, dataset = { streamUrl: '/stream', configUrl: '/config' }) {
  const context = vm.createContext({ window: {}, fetch: fetchImpl });
  vm.runInContext(source, context);
  return context.window.StoryStreaming.streamEnabled({ dataset });
}

test('Given a session with streaming enabled, When JTE asks at submit, Then only an explicit true decides to stream', async () => {
  assert.equal(await streamEnabled(async () => jsonResponse({ effectiveStreamingEnabled: true })), true);
});

for (const [label, body] of [
  ['streaming off', { effectiveStreamingEnabled: false }],
  ['a missing field', {}],
  ['a non-boolean truthy value', { effectiveStreamingEnabled: 1 }],
  ['a stringified false', { effectiveStreamingEnabled: 'false' }],
]) {
  test(`Given ${label}, When JTE asks at submit, Then the composer posts its plain form`, async () => {
    assert.equal(await streamEnabled(async () => jsonResponse(body)), false);
  });
}

for (const [label, fetchImpl] of [
  ['an unsuccessful answer', async () => new Response('{"effectiveStreamingEnabled":true}', { status: 502 })],
  ['a rejected request', async () => { throw new Error('Network unavailable'); }],
]) {
  test(`Given ${label} at submit, When JTE cannot read the setting, Then it costs streaming and never a turn`, async () => {
    assert.equal(await streamEnabled(fetchImpl), false);
  });
}

test('Given a composer without a config URL, When JTE asks at submit, Then it asks nothing and posts its plain form', async () => {
  let asked = 0;
  const decided = await streamEnabled(async () => { asked++; return jsonResponse({ effectiveStreamingEnabled: true }); },
    { streamUrl: '/stream' });
  assert.equal(decided, false);
  assert.equal(asked, 0, 'No config URL means no request to the server');
});

test('Given the page rendered with streaming on, When the setting changed off since then, Then submit does not stream', async () => {
  const streaming = await streamEnabled(async () => jsonResponse({ effectiveStreamingEnabled: false }));
  assert.equal(streaming, false, 'The submit-time answer, not the page render, decides');
});
