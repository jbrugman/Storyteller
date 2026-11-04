import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from './helpers/modules.mjs';

const { submitTurn } = loadModule('turn-submission');
const settle = () => new Promise(resolve => setImmediate(resolve));

// Drive the real submitTurn with an in-memory API and record the state the composer would render.
function harness(prompt = 'Hello') {
  const state = { prompt, streamPrompt: '', streamText: null, messages: [] };
  const callbacks = {
    onPrompt: value => { state.prompt = value; },
    onStreamPrompt: value => { state.streamPrompt = value; },
    onStreamText: value => { state.streamText = value; },
    onMessages: value => { state.messages = value; },
  };
  return { state, callbacks };
}

function api({ streaming = true, receivedText = 'Partial', historyFails = false } = {}) {
  return {
    config: async () => ({ effectiveStreamingEnabled: streaming }),
    turn: async () => ({}),
    streamTurn: async (_id, _prompt, onText) => {
      if (receivedText !== null) onText(receivedText);
      throw new Error('Generation failed');
    },
    history: async () => {
      if (historyFails) throw new Error('History unavailable');
      return { sessionId: 's', messages: [{ role: 'assistant', content: 'Complete' }] };
    },
  };
}

test('Given a submitted prompt, When streaming succeeds, Then the prompt is cleared only after completion', async () => {
  const { state, callbacks } = harness();
  let finish;
  const running = submitTurn({ sessionId: 's', prompt: 'Hello', ...callbacks }, {
    ...api(),
    streamTurn: (_id, _prompt, onText) => new Promise(resolve => {
      onText('Partial');
      finish = () => { onText('Complete'); resolve(); };
    }),
  });
  await settle();
  assert.equal(state.prompt, 'Hello', 'The prompt stays until the turn finished');
  assert.equal(state.streamText, 'Partial');
  assert.equal(state.streamPrompt, 'Hello');
  finish();
  await running;
  assert.equal(state.prompt, '');
  assert.equal(state.streamText, null);
  assert.equal(state.messages[0].content, 'Complete');
});

for (const reason of ['Generation failed', 'The stream ended before completion']) {
  test(`Given a submitted prompt, When streaming reports ${reason}, Then the prompt and partial answer remain for retry`, async () => {
    const { state, callbacks } = harness();
    const running = submitTurn({ sessionId: 's', prompt: 'Hello', ...callbacks }, {
      ...api(),
      streamTurn: async (_id, _prompt, onText) => { onText('Partial'); throw new Error(reason); },
    });
    await assert.rejects(running, new Error(reason));
    assert.equal(state.prompt, 'Hello');
    assert.equal(state.streamText, 'Partial', 'The partial answer must stay visible');
  });
}

for (const receivedText of [null, '', '  \n ']) {
  test(`Given no useful text (${JSON.stringify(receivedText)}), When streaming fails, Then the preview is cleared`, async () => {
    const { state, callbacks } = harness();
    await assert.rejects(submitTurn({ sessionId: 's', prompt: 'Hello', ...callbacks }, api({ receivedText })));
    assert.equal(state.prompt, 'Hello');
    assert.equal(state.streamText, null);
  });
}

test('Given a completed turn, When refreshing history fails, Then the prompt stays cleared to avoid resubmission', async () => {
  const { state, callbacks } = harness();
  const running = submitTurn({ sessionId: 's', prompt: 'Hello', ...callbacks }, {
    ...api({ historyFails: true }),
    streamTurn: async (_id, _prompt, onText) => { onText('Complete'); },
  });
  await assert.rejects(running, new Error('History unavailable'));
  assert.equal(state.prompt, '');
});

test('Given streaming disabled, When a normal turn succeeds, Then the prompt is still cleared', async () => {
  const { state, callbacks } = harness();
  await submitTurn({ sessionId: 's', prompt: 'Hello', ...callbacks }, api({ streaming: false }));
  assert.equal(state.prompt, '');
  assert.equal(state.messages[0].content, 'Complete');
});

test('Given selected past exchanges, When a streaming turn is submitted, Then streamTurn receives the past indexes', async () => {
  const calls = [];
  await submitTurn({ sessionId: 's', prompt: 'Next', pastMessageIndexes: [0, 2], onPrompt() {}, onStreamPrompt() {},
    onStreamText() {}, onMessages() {} }, {
    config: async () => ({ effectiveStreamingEnabled: true }),
    turn: async () => ({}),
    streamTurn: async (_id, _prompt, onText, options) => { calls.push(options); onText('Complete'); },
    history: async () => ({ sessionId: 's', messages: [] }),
  });
  assert.deepEqual(calls[0].pastMessageIndexes, [0, 2], 'The streaming call must carry the selection');
});

test('Given selected past exchanges, When a non-streaming turn is submitted, Then the turn call receives the past indexes', async () => {
  const calls = [];
  await submitTurn({ sessionId: 's', prompt: 'Next', pastMessageIndexes: [1], onPrompt() {}, onStreamPrompt() {},
    onStreamText() {}, onMessages() {} }, {
    config: async () => ({ effectiveStreamingEnabled: false }),
    turn: async (_id, _prompt, options) => { calls.push(options); return {}; },
    streamTurn: async () => {},
    history: async () => ({ sessionId: 's', messages: [] }),
  });
  assert.deepEqual(calls[0].pastMessageIndexes, [1]);
});

test('Given refreshed history reports a reference window, When a turn completes, Then onReferenceBefore is called with it', async () => {
  let referenceBefore;
  await submitTurn({ sessionId: 's', prompt: 'Next', onPrompt() {}, onStreamPrompt() {}, onStreamText() {},
    onMessages() {}, onReferenceBefore: value => { referenceBefore = value; } }, {
    ...api(),
    streamTurn: async (_id, _prompt, onText) => { onText('Complete'); },
    history: async () => ({ sessionId: 's', messages: [], referenceBeforeMessageIndex: 4 }),
  });
  assert.equal(referenceBefore, 4, 'The composer must learn the new eligibility cutoff after the turn');
});

test('Given a history limit, When a turn completes, Then history is refreshed as the recent window and hasOlder is forwarded', async () => {
  const pages = [];
  let hasOlder;
  await submitTurn({ sessionId: 's', prompt: 'Next', historyLimit: 10, onPrompt() {}, onStreamPrompt() {},
    onStreamText() {}, onMessages() {}, onHasOlder: value => { hasOlder = value; } }, {
    config: async () => ({ effectiveStreamingEnabled: false }),
    turn: async () => ({}),
    streamTurn: async () => {},
    history: async (_id, page) => { pages.push(page); return { sessionId: 's', messages: [], hasOlder: true }; },
  });
  assert.deepEqual(pages[0], { limit: 10 }, 'The composer must refresh only the recent window, not the whole story');
  assert.equal(hasOlder, true, 'The composer must learn whether older pages remain after the turn');
});
