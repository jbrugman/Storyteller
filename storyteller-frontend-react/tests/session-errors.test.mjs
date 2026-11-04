import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from './helpers/modules.mjs';

const { ApiError } = loadModule('api');
const { errorOutcome, errorMessage, isAbort } = loadModule('session-errors');

test('Given an expired session code, When classified, Then it reports an expired session', () => {
  for (const code of ['session_not_found', 'session_gone']) {
    const outcome = errorOutcome(new ApiError(code === 'session_gone' ? 410 : 404, code, 'gone'));
    assert.equal(outcome.kind, 'session-expired');
    assert.equal(outcome.message,
      'The session has expired or was stopped. Start a new story or resume an infinite session.');
  }
});

test('Given an unavailable model code, When classified, Then it asks to retry the unsent prompt', () => {
  const outcome = errorOutcome(new ApiError(502, 'model_backend_error', 'backend said no'));
  assert.equal(outcome.kind, 'model-unavailable');
  assert.equal(outcome.message, 'The model is temporarily unavailable. Your prompt was not saved; please try again.');
});

test('Given a non-resumable session, When classified, Then it stays a plain notice rather than an expired session', () => {
  const outcome = errorOutcome(new ApiError(404, 'session_not_resumable', 'Infinite session was not found.'));
  assert.equal(outcome.kind, 'notice');
  assert.equal(outcome.message, 'Infinite session was not found.');
});

test('Given another API failure, When classified, Then its own message is shown as a notice', () => {
  const outcome = errorOutcome(new ApiError(400, 'invalid_request', 'Temperature is out of range.'));
  assert.equal(outcome.kind, 'notice');
  assert.equal(outcome.message, 'Temperature is out of range.');
});

test('Given an aborted request, When classified, Then it is ignored without a message', () => {
  const abort = Object.assign(new Error('Aborted'), { name: 'AbortError' });
  assert.equal(isAbort(abort), true);
  assert.deepEqual(errorOutcome(abort), { kind: 'ignored', message: '' });
});

test('Given a thrown value, When building a message, Then only real Error messages are surfaced', () => {
  assert.equal(errorMessage(new Error('boom')), 'boom');
  assert.equal(errorMessage('boom'), 'An unexpected error occurred.');
});
