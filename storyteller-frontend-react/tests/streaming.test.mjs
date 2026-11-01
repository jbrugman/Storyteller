import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import ts from 'typescript';
const apiSource = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(apiSource, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } });
const { api } = await import('data:text/javascript;base64,' + Buffer.from(compiled.outputText).toString('base64'));

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
