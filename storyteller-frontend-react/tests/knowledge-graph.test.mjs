import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from './helpers/modules.mjs';

const { graphHasData, formatKnowledgeGraph } = loadModule('knowledge-graph');

test('Given an empty graph, When checking for data, Then it reports no data', () => {
  assert.equal(graphHasData('{"schemaVersion":1,"revision":0,"entities":{},"facts":[]}'), false);
});

test('Given a graph with an entity, When checking for data, Then it reports data', () => {
  assert.equal(graphHasData('{"entities":{"a":{"name":"Ada"}},"facts":[]}'), true);
});

test('Given a graph with a fact, When checking for data, Then it reports data', () => {
  assert.equal(graphHasData('{"entities":{},"facts":[{"s":"a"}]}'), true);
});

test('Given invalid JSON, When checking for data, Then it is treated as data to stay safe', () => {
  assert.equal(graphHasData('not json'), true);
});

test('Given a graph object, When formatting it, Then it becomes indented JSON text', () => {
  assert.equal(formatKnowledgeGraph({ revision: 0 }), '{\n  "revision": 0\n}');
});
