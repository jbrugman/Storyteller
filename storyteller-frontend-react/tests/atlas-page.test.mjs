import test from 'node:test';
import assert from 'node:assert/strict';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { loadDefault } from './helpers/modules.mjs';

// Render the real AtlasPage to confirm category filtering, selection, and the accessible grouping around connections.
const AtlasPage = loadDefault('AtlasPage');

const entries = [
  {
    id: 'c1', type: 'CHARACTER', name: 'Ada', aliases: ['Adder'], source: 'FIXED',
    relations: [{ id: 'r1', text: 'Ada built a engine', subjectId: 'c1', objectId: 'l1', status: 'ACTIVE', source: 'GRAPH', sourceTurn: 3, hard: false }],
    profile: [{ label: 'Role', value: 'Inventor', children: [] }], profileNotice: '',
  },
  { id: 'l1', type: 'LOCATION', name: 'London', aliases: [], source: 'GRAPH', relations: [], profile: [], profileNotice: '' },
];
const atlas = { sessionId: 'session-1', revision: 3, entries, profileWarning: '' };

function render(overrides = {}) {
  return renderToStaticMarkup(createElement(AtlasPage, { atlas, busy: false, onRefresh() {}, onBack() {}, ...overrides }));
}

// Keep only the navigation list, which is where the character entries are rendered.
function navigationList(html) {
  const start = html.indexOf('class="atlas-list"');
  const end = html.indexOf('</nav>', start);
  return html.slice(start, end);
}

test('Given mixed categories, When rendering the default view, Then only the character is listed', () => {
  const html = render();
  const nav = navigationList(html);
  assert.equal((nav.match(/class="atlas-entry"/g) ?? []).length, 1, 'Only the character belongs to the character list');
  assert.ok(nav.includes('>Ada</a>'));
  assert.ok(!nav.includes('London'), 'The location must not appear in the character list');
});

test('Given a character fact that links to another entry, When rendering, Then the related location is offered as a connection', () => {
  const html = render();
  assert.ok(html.includes('Ada built a engine'));
  assert.ok(html.includes('>London</a>'), 'The connected location appears inside the fact connections');
});

test('Given a selected entry, When rendering, Then it is marked as current and its connections form an accessible group', () => {
  const html = render();
  assert.ok(html.includes('aria-current="true"'));
  assert.ok(html.includes('role="group"'), 'Connected entries must be a labelled group');
});

test('Given no atlas entries, When rendering, Then the empty state explains how to add some', () => {
  const html = render({ atlas: { ...atlas, entries: [] } });
  assert.ok(html.includes('No atlas entries yet'));
});

test('Given a busy atlas, When rendering, Then refresh and navigation are disabled', () => {
  const html = render({ busy: true });
  assert.ok(html.includes('aria-busy="true"'));
  assert.ok((html.match(/ disabled/g) ?? []).length >= 2, 'Expected the header actions disabled while busy');
});
