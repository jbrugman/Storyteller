import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import ts from 'typescript';

// Guards the bundled JTE composer so the React example can stay aligned with it. The React submission and render
// behaviour lives in turn-submission.test.mjs and story-page.test.mjs, which run the real modules instead of re-evaluating
// extracted code, so the two halves are deliberately tested separately.
const template = await readFile(new URL('../../storyteller-api/src/main/resources/templates/story.jte', import.meta.url), 'utf8');
const script = template.slice(template.lastIndexOf('<script>') + '<script>'.length, template.lastIndexOf('</script>'));
const jte = ts.createSourceFile('story.js', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);

// The submit handler and the helpers it closes over (redispatch, lockComposer and the submission state) are taken
// together, so the real handlers run instead of stubs the test could drift away from the page it is meant to guard.
let submitCall;
function findSubmit(node) {
  if (ts.isCallExpression(node) && node.expression.getText(jte) === 'composer.addEventListener'
    && node.arguments[0]?.text === 'submit') submitCall = node;
  ts.forEachChild(node, findSubmit);
}
findSubmit(jte);
assert.ok(submitCall, 'The JTE composer must have a submit handler');

const blockStart = script.indexOf('let imageReadyToSubmit = false;');
assert.ok(blockStart >= 0, 'The composer submission state must be present');
const composerBlock = script.slice(blockStart, submitCall.getEnd());

const settle = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

// Drives the real composer. streamEnabled is the only collaborator that decides how the turn leaves the page:
// a confirmed yes streams, anything else has to reach the server as exactly one ordinary form submission.
function jteSubmission({ streamEnabled = true, generation = deferred() } = {}) {
  const prompt = { value: 'Hello', readOnly: false };
  const continueButton = {};
  const undoButton = { dataset: { undoAvailable: 'false' } };
  const element = { setAttribute() {}, removeAttribute() {} };
  const reloadValues = [];
  const generationError = { hidden: false, textContent: 'Previous error' };
  const observed = { nativePosts: 0, submitEvents: 0, streamChecks: 0, generateCalls: 0 };

  // requestSubmit mirrors the browser: it dispatches a submit event and only submits the form when nothing cancels it.
  // The first dispatch stands for the user's own submission, redispatch() re-enters the handler through requestSubmit.
  let submit;
  function dispatch(submitter) {
    observed.submitEvents++;
    let canceled = false;
    submit({ submitter, preventDefault() { canceled = true; } });
    if (!canceled) observed.nativePosts++;
  }
  const composer = {
    ...element,
    dataset: { streamUrl: '/stream', configUrl: '/config' },
    addEventListener(type, handler) { if (type === 'submit') submit = handler; },
    requestSubmit(submitter) { dispatch(submitter); },
  };
  const context = vm.createContext({
    prompt, continueButton, undoButton, generationError, composer,
    imageError: { hidden: true }, submitting: false,
    imagePicker: element, removeImage: {}, actionButtons: [continueButton, undoButton], actionLinks: [],
    imageSelection: 0, imagePreparation: Promise.resolve(),
    FormData: class {}, document: { querySelector() { return {}; } },
    StoryStreaming: {
      streamEnabled() { observed.streamChecks++; return Promise.resolve(streamEnabled); },
      generate() { observed.generateCalls++; return generation.promise; },
    },
    // The first continue dispatch always waits for pending image work, then re-dispatches itself. Running the
    // deferred callback at once keeps that real handoff inside the synchronous part of the turn.
    window: { setTimeout: callback => callback(), location: { reload() { reloadValues.push(prompt.value); } } },
  });
  vm.runInContext(composerBlock, context);
  dispatch(continueButton);
  return { prompt, continueButton, undoButton, reloadValues, generationError, observed };
}

test('Given a submitted prompt, When JTE streaming succeeds, Then the prompt is cleared only after completion', async () => {
  const generation = deferred();
  const ui = jteSubmission({ streamEnabled: true, generation });
  await settle();
  assert.equal(ui.observed.generateCalls, 1, 'A confirmed setting streams once');
  assert.equal(ui.observed.nativePosts, 0, 'A streamed turn must never post the plain form');
  assert.equal(ui.prompt.value, 'Hello', 'The prompt stays until the turn finished');
  assert.deepEqual(ui.reloadValues, []);
  generation.resolve();
  await settle();
  assert.equal(ui.prompt.value, '');
  assert.deepEqual(ui.reloadValues, [''], 'The prompt must be empty before reloading');
});

for (const reason of ['Generation failed', 'The stream ended before completion']) {
  test(`Given a submitted prompt, When JTE reports ${reason}, Then the prompt remains available for retry`, async () => {
    const generation = deferred();
    const ui = jteSubmission({ streamEnabled: true, generation });
    await settle();
    generation.reject(new Error(reason));
    await settle();
    assert.equal(ui.prompt.value, 'Hello');
    assert.equal(ui.prompt.readOnly, false);
    assert.deepEqual(ui.reloadValues, []);
    assert.equal(ui.generationError.hidden, false);
    assert.equal(ui.generationError.textContent, reason);
  });
}

test('Given streaming on when the page loaded, When it was disabled elsewhere, Then submit posts exactly one plain turn', async () => {
  const ui = jteSubmission({ streamEnabled: false });
  await settle();
  assert.equal(ui.observed.streamChecks, 1, 'The composer asks the server exactly once');
  assert.equal(ui.observed.generateCalls, 0, 'A disabled setting must never open a stream');
  assert.equal(ui.observed.nativePosts, 1, 'Exactly one ordinary submission reaches the server');
  assert.equal(ui.continueButton.textContent, 'Writing…', 'The composer locks after the plain submission started');
  assert.equal(ui.prompt.readOnly, true);
});
