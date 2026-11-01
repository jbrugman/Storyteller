import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import ts from 'typescript';
import { createRequire } from 'node:module';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

// Exercise the real UI handlers without copying them or adding browser dependencies.
const template = await readFile(new URL('../../storyteller-api/src/main/resources/templates/story.jte', import.meta.url), 'utf8');
const script = template.slice(template.lastIndexOf('<script>') + '<script>'.length, template.lastIndexOf('</script>'));
const jte = ts.createSourceFile('story.js', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
let submit;
function findSubmit(node) {
  if (ts.isCallExpression(node) && node.expression.getText(jte) === 'composer.addEventListener'
    && node.arguments[0]?.text === 'submit') submit = node.arguments[1].getText(jte);
  ts.forEachChild(node, findSubmit);
}
findSubmit(jte);
assert.ok(submit, 'The JTE composer must have a submit handler');

const appSource = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const app = ts.createSourceFile('App.tsx', appSource, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const appFunction = app.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'App');
const reactHandlers = appFunction.body.statements
  .filter(node => ts.isFunctionDeclaration(node) && ['sendPrompt', 'run'].includes(node.name?.text))
  .map(node => node.getText(app)).join('\n');
assert.ok(reactHandlers.includes('async function sendPrompt'));
const reactScript = ts.transpileModule(reactHandlers, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
const settle = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function jteSubmission(completion) {
  const prompt = { value: 'Hello', readOnly: false };
  const continueButton = {};
  const undoButton = { dataset: { undoAvailable: 'false' } };
  const element = { setAttribute() {}, removeAttribute() {} };
  const reloadValues = [];
  const generationError = { hidden: false, textContent: 'Previous error' };
  const context = vm.createContext({
    prompt, continueButton, undoButton, generationError,
    composer: { ...element, dataset: { streamUrl: '/stream' } },
    imageError: { hidden: true }, imageReadyToSubmit: true, submitting: false,
    imagePicker: element, removeImage: {}, actionButtons: [continueButton, undoButton], actionLinks: [],
    FormData: class {}, document: { querySelector() { return {}; } },
    StoryStreaming: { generate() { return completion.promise; } },
    window: { location: { reload() { reloadValues.push(prompt.value); } } },
  });
  vm.runInContext(`(${submit})({ submitter: continueButton, preventDefault() {} })`, context);
  return { prompt, reloadValues, generationError };
}

function reactSubmission(completion, { streaming = true, historyFails = false, receivedText = 'Partial' } = {}) {
  const state = { prompt: 'Hello', busy: false, streamText: null, messages: [], error: '' };
  const context = vm.createContext({
    session: { sessionId: 'session' }, prompt: state.prompt,
    api: {
      async config() { return { effectiveStreamingEnabled: streaming }; },
      streamTurn(_id, _prompt, onText) { if (receivedText !== null) onText(receivedText); return completion.promise; },
      turn() { return completion.promise; },
      async history() {
        if (historyFails) throw new Error('History unavailable');
        return { messages: [{ role: 'assistant', content: 'Complete' }] };
      },
    },
    setPrompt(value) { state.prompt = value; },
    setBusy(value) { state.busy = value; },
    setError(value) { state.error = value; },
    setStreamText(value) { state.streamText = value; },
    setStreamPrompt() {},
    setMessages(value) { state.messages = value; },
    errorMessage(error) { return error.message; },
  });
  vm.runInContext(reactScript, context);
  const finished = vm.runInContext('sendPrompt()', context);
  return { state, finished };
}

for (const frontend of ['JTE', 'React']) {
  test(`Given a submitted prompt, When ${frontend} streaming succeeds, Then the prompt is cleared only after completion`, async () => {
    const completion = deferred();
    const ui = frontend === 'JTE' ? jteSubmission(completion) : reactSubmission(completion);
    await settle();
    assert.equal(frontend === 'JTE' ? ui.prompt.value : ui.state.prompt, 'Hello');
    if (frontend === 'JTE') assert.deepEqual(ui.reloadValues, []);
    completion.resolve();
    await settle();
    if (frontend === 'JTE') {
      assert.equal(ui.prompt.value, '');
      assert.deepEqual(ui.reloadValues, [''], 'The prompt must be empty before reloading');
    } else {
      await ui.finished;
      assert.equal(ui.state.prompt, '');
      assert.equal(ui.state.busy, false);
      assert.equal(ui.state.streamText, null);
      assert.equal(ui.state.messages[0].content, 'Complete');
    }
  });

  for (const reason of ['Generation failed', 'The stream ended before completion']) {
    test(`Given a submitted prompt, When ${frontend} reports ${reason}, Then the prompt remains available for retry`, async () => {
      const completion = deferred();
      const ui = frontend === 'JTE' ? jteSubmission(completion) : reactSubmission(completion);
      await settle();
      completion.reject(new Error(reason));
      await settle();
      if (frontend === 'JTE') {
        assert.equal(ui.prompt.value, 'Hello');
        assert.equal(ui.prompt.readOnly, false);
        assert.deepEqual(ui.reloadValues, []);
        assert.equal(ui.generationError.hidden, false);
        assert.equal(ui.generationError.textContent, reason);
      } else {
        await ui.finished;
        assert.equal(ui.state.prompt, 'Hello');
        assert.equal(ui.state.busy, false);
        assert.equal(ui.state.streamText, 'Partial');
        assert.equal(ui.state.error, reason);
      }
    });
  }
}

test('Given a completed React turn, When refreshing history fails, Then the submitted prompt stays cleared to avoid accidental resubmission', async () => {
  const completion = deferred();
  const ui = reactSubmission(completion, { historyFails: true });
  completion.resolve();
  await ui.finished;
  assert.equal(ui.state.prompt, '');
  assert.equal(ui.state.error, 'History unavailable');
});

test('Given streaming disabled in React, When a normal turn succeeds, Then the prompt is still cleared', async () => {
  const completion = deferred();
  const ui = reactSubmission(completion, { streaming: false });
  completion.resolve();
  await ui.finished;
  assert.equal(ui.state.prompt, '');
  assert.equal(ui.state.messages[0].content, 'Complete');
});

const storySource = await readFile(new URL('../src/StoryPage.tsx', import.meta.url), 'utf8');
const storyModule = { exports: {} };
vm.runInNewContext(ts.transpileModule(storySource, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
}).outputText, { exports: storyModule.exports, module: storyModule, require: createRequire(import.meta.url) });

function renderStory(state) {
  return renderToStaticMarkup(createElement(storyModule.exports.default, {
    session: { sessionId: 'session', title: 'Story', infinite: false },
    messages: state.messages, busy: state.busy, streamText: state.streamText, streamPrompt: 'Hello', prompt: state.prompt,
    onPromptChange() {}, onSend() {}, onSettings() {}, onStop() {}, onInfinite() {}, onHistory() {}, onAtlas() {},
  }));
}

for (const receivedText of [null, '', '  \n ']) {
  test(`Given a saved React exchange, When generation fails with ${JSON.stringify(receivedText)} received, Then the failed row disappears and the prompt remains only in the composer`, async () => {
    const completion = deferred();
    const ui = reactSubmission(completion, { receivedText });
    const saved = [
      { messageIndex: 0, role: 'user', content: 'Previous prompt' },
      { messageIndex: 1, role: 'assistant', content: 'Last saved response' },
    ];
    ui.state.messages = saved;
    await settle();
    completion.reject(new Error('Generation failed'));
    await ui.finished;
    assert.equal(ui.state.prompt, 'Hello');
    assert.equal(ui.state.streamText, null);
    assert.equal(ui.state.messages, saved);
    assert.equal(ui.state.error, 'Generation failed');
    const html = renderStory(ui.state);
    assert.equal((html.match(/<article class="exchange">/g) ?? []).length, 1);
    assert.equal((html.match(/Hello/g) ?? []).length, 1, 'The failed prompt belongs only in the composer');
    assert.ok(html.includes('Last saved response'));
  });
}

test('Given an empty React story, When generation fails without text, Then the empty state returns and no exchange remains', async () => {
  const completion = deferred();
  const ui = reactSubmission(completion, { receivedText: null });
  await settle();
  completion.reject(new Error('Generation failed'));
  await ui.finished;
  const html = renderStory(ui.state);
  assert.ok(html.includes('Begin the story'));
  assert.equal((html.match(/<article class="exchange">/g) ?? []).length, 0);
});

test('Given a partial React answer, When the stream fails, Then the answer remains visibly marked incomplete', async () => {
  const completion = deferred();
  const ui = reactSubmission(completion);
  await settle();
  completion.reject(new Error('Connection lost'));
  await ui.finished;
  const html = renderStory(ui.state);
  assert.ok(html.includes('Partial'));
  assert.ok(html.includes('Incomplete response'));
  assert.equal((html.match(/<article class="exchange">/g) ?? []).length, 1);
});
