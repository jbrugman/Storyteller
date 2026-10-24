@import nl.llm.storyteller.api.story.SessionAtlas
@import java.util.List
@param List<SessionAtlas.ProfileField> fields

<dl class="atlas-profile">
  @for(var field : fields)
    <div>
      <dt>${field.label()}</dt>
      <dd>
        @if(field.children().isEmpty())
          <span class="atlas-profile-value">${field.value()}</span>
        @else
          @template.atlasProfile(field.children())
        @endif
      </dd>
    </div>
  @endfor
</dl>
