import { useState } from 'react';
import type { AtlasEntry, AtlasRelation, AtlasProfileField, SessionAtlas } from './api';

interface Props {
  atlas: SessionAtlas;
  busy: boolean;
  onRefresh: () => void;
  onBack: () => void;
}

const categories: { type: AtlasEntry['type']; label: string }[] = [
  { type: 'CHARACTER', label: 'Characters' }, { type: 'LOCATION', label: 'Locations' },
  { type: 'ITEM', label: 'Items' }, { type: 'SKILL', label: 'Skills' },
];

export default function AtlasPage({ atlas, busy, onRefresh, onBack }: Props) {
  const [query, setQuery] = useState('');
  const [type, setType] = useState<AtlasEntry['type']>('CHARACTER');
  const [selectedId, setSelectedId] = useState('');
  const search = query.trim().toLowerCase();
  const entries = atlas.entries.filter(entry => entry.type === type &&
    [entry.name, ...entry.aliases].some(name => name.toLowerCase().includes(search)));
  const selected = entries.find(entry => entry.id === selectedId) ?? entries[0];

  function follow(id: string) {
    const entry = atlas.entries.find(entry => entry.id === id);
    if (!entry) return;
    setType(entry.type);
    setSelectedId(entry.id);
    setQuery('');
  }

  function facts(relations: AtlasRelation[]) {
    return <ul className="atlas-facts">{relations.map(relation => <li key={relation.id}>
      <p>{relation.text}</p>
      <p className="setting-hint">{relation.status} · Source: {relation.source}</p>
      <div className="atlas-connections" aria-label="Connected entries">
        {[...new Set([relation.subjectId, relation.objectId])].map(id => <a key={id}
          href={`#atlas-${encodeURIComponent(id)}`} onClick={event => { event.preventDefault(); follow(id); }}>
          {atlas.entries.find(entry => entry.id === id)?.name ?? id}
        </a>)}
      </div>
    </li>)}</ul>;
  }

  const active = selected?.relations.filter(relation => relation.status === 'ACTIVE') ?? [];
  const other = selected?.relations.filter(relation => relation.status !== 'ACTIVE') ?? [];

  return <main className="settings-page atlas-page" aria-busy={busy}>
    <header className="story-header">
      <div><p className="eyebrow">Storyteller</p><h1>Story atlas</h1></div>
      <div className="session-actions">
        <button className="header-action secondary-action" disabled={busy} onClick={onRefresh}>Refresh</button>
        <button className="header-action secondary-action" disabled={busy} onClick={onBack}>Back to story</button>
      </div>
    </header>
    <p className="intro">Explore character profiles from Fixed protagonists alongside facts from the knowledge graph. Graph updates may lag behind the latest story turns.</p>
    {atlas.profileWarning && <p role="alert">{atlas.profileWarning}</p>}
    {atlas.entries.length === 0 ? <section className="panel settings-form">
      <h2>No atlas entries yet</h2>
      <p>No profiles or graph entries are available. Add Fixed protagonists in Settings, or continue the story with graph updates enabled.</p>
    </section> : <div className="atlas-layout">
      <aside className="panel settings-form atlas-index" aria-label="Atlas entries">
        <div className="atlas-search">
          <label htmlFor="atlas-search">Search names and aliases</label>
          <input id="atlas-search" type="search" value={query} onChange={event => setQuery(event.target.value)} />
          <label htmlFor="atlas-type">Category</label>
          <select id="atlas-type" value={type} onChange={event => {
            setType(event.target.value as AtlasEntry['type']); setQuery(''); setSelectedId('');
          }}>
            {categories.map(category => <option key={category.type} value={category.type}>{category.label}</option>)}
          </select>
        </div>
        <nav className="atlas-list" aria-label="Matching entries">
          {entries.map(entry => <a className="atlas-entry" key={entry.id}
            href={`#atlas-${encodeURIComponent(entry.id)}`} aria-current={entry.id === selected?.id ? 'true' : undefined}
            onClick={event => { event.preventDefault(); setSelectedId(entry.id); }}>{entry.name}</a>)}
          {entries.length === 0 && <p className="setting-hint">No entries match this category and search.</p>}
        </nav>
      </aside>
      <section className="panel settings-form atlas-detail" aria-label="Entry details" aria-live="polite">
        {!selected ? <><h2>No entry selected</h2><p>Choose another category or search term.</p></> : <>
          <header><p className="eyebrow">{selected.type}</p><h2>{selected.name}</h2></header>
          {selected.aliases.length > 0 && <p>Also known as: {selected.aliases.join(', ')}</p>}
          <p className="setting-hint">Source: {selected.source}</p>
          {selected.profileNotice && <p role="status">{selected.profileNotice}</p>}
          {selected.profile.length > 0 ? <section aria-label="Fixed protagonists profile">
            <h3>Profile — Fixed protagonists</h3><ProfileFields fields={selected.profile} />
          </section> : selected.type === 'CHARACTER' && <p className="setting-hint">No Fixed protagonists profile is linked to this character.</p>}
          <h3>Recorded facts — Knowledge graph</h3>
          {active.length === 0 ? <p>No active facts have been recorded for this entry.</p> : facts(active)}
          {other.length > 0 && <details className="atlas-other" key={selected.id}>
            <summary>Proposed and previous facts ({other.length})</summary>{facts(other)}
          </details>}
        </>}
      </section>
    </div>}
    <p className="setting-hint atlas-revision">Graph revision: {atlas.revision} · Only stored facts are shown; missing details are not inferred.</p>
  </main>;
}

function ProfileFields({ fields }: { fields: AtlasProfileField[] }) {
  return <dl className="atlas-profile">{fields.map((field, index) => <div key={index}>
    <dt>{field.label}</dt>
    <dd>{field.children.length > 0 ? <ProfileFields fields={field.children} />
      : <span className="atlas-profile-value">{field.value}</span>}</dd>
  </div>)}</dl>;
}
