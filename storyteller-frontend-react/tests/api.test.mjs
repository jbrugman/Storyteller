import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from './helpers/modules.mjs';

const { api, ApiError } = loadModule('api');

// Replace fetch for one call and record exactly what the browser would have sent.
async function capture(invoke, response) {
  const original = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url, init = {}) => {
    requests.push({ url, init });
    return response ?? new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
  };
  try {
    return { requests, result: await invoke() };
  } finally {
    globalThis.fetch = original;
  }
}

const jsonError = (status, code, message) =>
  new Response(JSON.stringify({ code, message }), { status, headers: { 'Content-Type': 'application/json' } });

test('Given an unavailable model, When a turn fails, Then the error keeps the model_backend_error code', async () => {
  await assert.rejects(
    capture(() => api.turn('s', 'Hello'), jsonError(502, 'model_backend_error', 'Model is down')),
    error => error instanceof ApiError && error.status === 502 && error.code === 'model_backend_error');
});

test('Given an expired session, When a stream is requested, Then the failure surfaces before the first event', async () => {
  await assert.rejects(
    capture(() => api.streamTurn('s', 'Hello', () => {}), jsonError(410, 'session_gone', 'expired')),
    error => error instanceof ApiError && error.code === 'session_gone');
});

test('Given an empty body, When a request reads it, Then it resolves without parsing JSON', async () => {
  assert.equal((await capture(() => api.undo('s'), new Response(null, { status: 204 }))).result, undefined);
  assert.equal((await capture(() => api.undo('s'), new Response('', { status: 200 }))).result, undefined);
});

test('Given an image, When a turn is sent, Then prompt and image travel as form fields', async () => {
  const image = new File([new Uint8Array([1, 2, 3])], 'scene.png', { type: 'image/png' });
  const { requests } = await capture(() => api.turn('s', 'Describe this', { image }));
  const body = requests[0].init.body;
  assert.ok(body instanceof FormData, 'An image must use multipart, not JSON');
  assert.equal(body.get('prompt'), 'Describe this');
  const attached = body.get('image');
  assert.equal(attached.name, 'scene.png');
  assert.equal(attached.type, 'image/png');
  assert.equal(attached.size, 3);
  assert.equal(requests[0].init.headers, undefined, 'The browser sets the multipart boundary itself');
});

test('Without an image, When a turn is sent, Then the prompt is sent as JSON with the abort signal', async () => {
  const controller = new AbortController();
  const { requests } = await capture(() => api.turn('s', 'Plain', { signal: controller.signal }));
  assert.equal(requests[0].init.headers['Content-Type'], 'application/json');
  assert.equal(requests[0].init.body, JSON.stringify({ prompt: 'Plain', pastMessageIndexes: [] }));
  assert.equal(requests[0].init.signal, controller.signal, 'Aborting must reach the underlying fetch');
});

test('Given graph JSON, When saving it, Then the raw text is sent unchanged and not re-encoded', async () => {
  const graph = '{"schemaVersion":1,"revision":3,"entities":{},"facts":[]}';
  const { requests } = await capture(() => api.saveGraph('s', graph));
  assert.equal(requests[0].init.method, 'PUT');
  assert.equal(requests[0].init.body, graph, 'The graph must not be JSON-encoded a second time');
  assert.equal(requests[0].init.headers['Content-Type'], 'application/json');
});

test('Given settings with the knowledge graph toggled, When saving, Then the config PATCH carries graphEnabled', async () => {
  const settings = {
    systemPrompt: 'sys', fixedProtagonists: 'yaml', rules: 'rules', temperature: null,
    streamingEnabled: false, validationEnabled: true, graphEnabled: false,
  };
  const { requests } = await capture(() => api.saveConfig('s', settings));
  assert.equal(requests[0].init.method, 'PATCH');
  assert.deepEqual(JSON.parse(requests[0].init.body), settings);
});

test('Given selected past exchanges, When a turn is sent without an image, Then the JSON body carries pastMessageIndexes', async () => {
  const { requests } = await capture(() => api.turn('s', 'Next', { pastMessageIndexes: [0, 2] }));
  assert.equal(requests[0].init.body, JSON.stringify({ prompt: 'Next', pastMessageIndexes: [0, 2] }));
});

test('Given selected past exchanges with an image, When a turn is sent, Then each becomes a repeated pastExchange form field', async () => {
  const image = new File([new Uint8Array([1])], 'scene.png', { type: 'image/png' });
  const { requests } = await capture(() => api.turn('s', 'Look', { image, pastMessageIndexes: [0, 3] }));
  const body = requests[0].init.body;
  assert.ok(body instanceof FormData);
  assert.deepEqual(body.getAll('pastExchange'), ['0', '3'], 'Multipart mirrors the bundled form field per selection');
});

test('Given selected past exchanges, When a stream is requested without an image, Then the JSON body carries pastMessageIndexes', async () => {
  const stream = new Response('event: done\ndata: {"response":"ok"}\n\n', { status: 200 });
  const { requests } = await capture(() => api.streamTurn('s', 'Next', () => {}, { pastMessageIndexes: [1] }), stream);
  assert.equal(requests[0].init.body, JSON.stringify({ prompt: 'Next', pastMessageIndexes: [1] }));
});

test('Given an older-history window, When reading history, Then the cursor and size travel as query parameters and hasOlder is read back', async () => {
  const body = { sessionId: 's', messages: [], referenceBeforeMessageIndex: 0, hasOlder: true };
  const response = new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  const { requests, result } = await capture(() => api.history('s', { before: 20, limit: 10 }), response);
  assert.equal(requests[0].url, '/v1/sessions/s/history?before=20&limit=10');
  assert.equal(result.hasOlder, true, 'The page flag must survive decoding so infinite scroll can continue');
});

test('Without a window, When reading history, Then no query parameters are appended', async () => {
  const { requests } = await capture(() => api.history('s'));
  assert.equal(requests[0].url, '/v1/sessions/s/history', 'A full read stays a plain history request');
});
