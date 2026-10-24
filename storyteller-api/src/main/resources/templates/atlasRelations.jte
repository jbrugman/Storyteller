@import nl.llm.storyteller.api.web.StoryAtlasPage
@import nl.llm.storyteller.api.story.SessionAtlas
@import java.util.List
@param StoryAtlasPage page
@param List<SessionAtlas.Relation> relations

<ul class="atlas-facts">
  @for(var relation : relations)
    <li>
      <p>${relation.text()}</p>
      <p class="setting-hint">${relation.status()} · Source: ${relation.source()}</p>
      <div class="atlas-connections" aria-label="Connected entries">
        <a href="${page.relatedUrl(relation.subjectId())}">${page.relatedName(relation.subjectId())}</a>
        @if(!relation.subjectId().equals(relation.objectId()))
          <a href="${page.relatedUrl(relation.objectId())}">${page.relatedName(relation.objectId())}</a>
        @endif
      </div>
    </li>
  @endfor
</ul>
