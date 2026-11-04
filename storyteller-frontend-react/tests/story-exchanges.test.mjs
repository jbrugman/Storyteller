import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from './helpers/modules.mjs';

const { pairExchanges } = loadModule('story-exchanges');
const message = (messageIndex, role) => ({ messageIndex, role, content: `${role} ${messageIndex}`, hasImage: false });

test('Given adjacent prompt and response, When pairing, Then they form one exchange', () => {
  const exchanges = pairExchanges([message(0, 'user'), message(1, 'assistant')]);
  assert.equal(exchanges.length, 1);
  assert.equal(exchanges[0].prompt.messageIndex, 0);
  assert.equal(exchanges[0].response.messageIndex, 1);
});

test('Given a non-adjacent assistant message, When pairing, Then it stays a separate exchange', () => {
  const exchanges = pairExchanges([message(0, 'user'), message(2, 'assistant')]);
  assert.equal(exchanges.length, 2);
  assert.equal(exchanges[0].response, undefined);
  assert.equal(exchanges[1].response.messageIndex, 2);
});

test('Given an orphan assistant message, When pairing, Then it remains visible', () => {
  const exchanges = pairExchanges([message(5, 'assistant')]);
  assert.equal(exchanges.length, 1);
  assert.equal(exchanges[0].prompt, undefined);
  assert.equal(exchanges[0].response.messageIndex, 5);
});

test('Given messages, When pairing, Then the input array is not mutated', () => {
  const messages = [message(0, 'user'), message(1, 'assistant')];
  const snapshot = structuredClone(messages);
  pairExchanges(messages);
  assert.deepEqual(messages, snapshot);
});
