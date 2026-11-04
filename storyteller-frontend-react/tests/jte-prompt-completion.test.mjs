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
let submit;
function findSubmit(node) {
  if (ts.isCallExpression(node) && node.expression.getText(jte) === 'composer.addEventListener'
    && node.arguments[0]?.text === 'submit') submit = node.arguments[1].getText(jte);
  ts.forEachChild(node, findSubmit);
}
findSubmit(jte);
assert.ok(submit, 'The JTE composer must have a submit handler');

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

test('Given a submitted prompt, When JTE streaming succeeds, Then the prompt is cleared only after completion', async () => {
  const completion = deferred();
  const ui = jteSubmission(completion);
  await settle();
  assert.equal(ui.prompt.value, 'Hello', 'The prompt stays until the turn finished');
  assert.deepEqual(ui.reloadValues, []);
  completion.resolve();
  await settle();
  assert.equal(ui.prompt.value, '');
  assert.deepEqual(ui.reloadValues, [''], 'The prompt must be empty before reloading');
});

for (const reason of ['Generation failed', 'The stream ended before completion']) {
  test(`Given a submitted prompt, When JTE reports ${reason}, Then the prompt remains available for retry`, async () => {
    const completion = deferred();
    const ui = jteSubmission(completion);
    await settle();
    completion.reject(new Error(reason));
    await settle();
    assert.equal(ui.prompt.value, 'Hello');
    assert.equal(ui.prompt.readOnly, false);
    assert.deepEqual(ui.reloadValues, []);
    assert.equal(ui.generationError.hidden, false);
    assert.equal(ui.generationError.textContent, reason);
  });
}
