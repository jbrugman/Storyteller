@import nl.llm.storyteller.api.web.StoryAtlasPage
@param StoryAtlasPage page

<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Story atlas</title>
    <script src="/story-theme.js"></script>
    <link rel="stylesheet" href="/storyteller.css">
    <script src="/story-atlas.js" defer></script>
  </head>
  <body>
    <main class="settings-page atlas-page">
      <header class="story-header">
        <div><p class="eyebrow">Storyteller</p><h1>Story atlas</h1></div>
        <div class="session-actions">
          <a class="header-action" href="${page.refreshUrl()}">Refresh</a>
          <a class="header-action" href="/story">Back to story</a>
        </div>
      </header>
      <p class="intro">Explore character profiles from Fixed protagonists alongside facts from the knowledge graph. Graph updates may lag behind the latest story turns.</p>
      @if(!page.atlas().profileWarning().isEmpty())
        <p role="alert">${page.atlas().profileWarning()}</p>
      @endif
      @if(page.atlas().entries().isEmpty())
        <section class="panel settings-form">
          <h2>No atlas entries yet</h2>
          <p>No profiles or graph entries are available. Add Fixed protagonists in Settings, or continue the story with graph updates enabled.</p>
        </section>
      @else
        <div class="atlas-layout">
          <aside class="panel settings-form atlas-index" aria-label="Atlas entries">
            <form action="/story/atlas" method="get" class="atlas-search">
              <label for="atlas-search">Search names and aliases</label>
              <input id="atlas-search" name="q" type="search" value="${page.query()}">
              <label for="atlas-type">Category</label>
              <select id="atlas-type" name="type">
                @for(var category : StoryAtlasPage.CATEGORIES)
                  <option value="${category.type()}" selected="${page.type().equals(category.type())}">${category.label()}</option>
                @endfor
              </select>
              <button type="submit">Search</button>
            </form>
            <nav class="atlas-list" aria-label="Matching entries">
              @for(var entry : page.entries())
                <a class="atlas-entry" href="${page.entryUrl(entry.id())}"
                   aria-current="${page.selected() != null && page.selected().id().equals(entry.id()) ? "true" : null}">${entry.name()}</a>
              @endfor
              @if(page.entries().isEmpty())
                <p class="setting-hint">No entries match this category and search.</p>
              @endif
            </nav>
          </aside>
          <section class="panel settings-form atlas-detail" aria-label="Entry details">
            @if(page.selected() == null)
              <h2>No entry selected</h2><p>Choose another category or search term.</p>
            @else
              <header><p class="eyebrow">${page.selected().type()}</p><h2>${page.selected().name()}</h2></header>
              @if(!page.selected().aliases().isEmpty())
                <p>Also known as: ${String.join(", ", page.selected().aliases())}</p>
              @endif
              <p class="setting-hint">Source: ${page.selected().source()}</p>
              @if(!page.selected().profileNotice().isEmpty())
                <p role="status">${page.selected().profileNotice()}</p>
              @endif
              @if(!page.selected().profile().isEmpty())
                <section aria-label="Fixed protagonists profile">
                  <h3>Profile — Fixed protagonists</h3>
                  @template.atlasProfile(page.selected().profile())
                </section>
              @elseif(page.selected().type().equals("CHARACTER"))
                <p class="setting-hint">No Fixed protagonists profile is linked to this character.</p>
              @endif
              <h3>Recorded facts — Knowledge graph</h3>
              @if(page.activeRelations().isEmpty())
                <p>No active facts have been recorded for this entry.</p>
              @else
                @template.atlasRelations(page, page.activeRelations())
              @endif
              @if(!page.otherRelations().isEmpty())
                <details class="atlas-other">
                  <summary>Proposed and previous facts (${page.otherRelations().size()})</summary>
                  @template.atlasRelations(page, page.otherRelations())
                </details>
              @endif
            @endif
          </section>
        </div>
      @endif
      <p class="setting-hint atlas-revision">Graph revision: ${page.atlas().revision()} · Only stored facts are shown; missing details are not inferred.</p>
    </main>
  </body>
</html>
