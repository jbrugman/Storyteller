package nl.llm.storyteller.api.web;

import nl.llm.storyteller.api.story.SessionAtlas;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public record StoryAtlasPage(SessionAtlas atlas, String query, String type,
                             List<SessionAtlas.Entry> entries, SessionAtlas.Entry selected) {
  public record Category(String type, String label) { }

  public static final List<Category> CATEGORIES = List.of(
    new Category("CHARACTER", "Characters"), new Category("LOCATION", "Locations"),
    new Category("ITEM", "Items"), new Category("SKILL", "Skills")
  );

  public static StoryAtlasPage from(SessionAtlas atlas, String query, String type, String entityId) {
    String search = query == null ? "" : query.strip();
    String category = CATEGORIES.stream().anyMatch(c -> c.type().equals(type)) ? type : "CHARACTER";
    String normalized = search.toLowerCase(Locale.ROOT);
    var entries = atlas.entries().stream().filter(e -> e.type().equals(category))
      .filter(e -> e.name().toLowerCase(Locale.ROOT).contains(normalized)
        || e.aliases().stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).contains(normalized)))
      .toList();
    var selected = entries.stream().filter(e -> e.id().equals(entityId)).findFirst()
      .orElse(entries.isEmpty() ? null : entries.getFirst());
    return new StoryAtlasPage(atlas, search, category, entries, selected);
  }

  public String entryUrl(String id) {
    return "/story/atlas?type=" + encode(type) + "&q=" + encode(query) + "&entity=" + encode(id);
  }

  public String relatedUrl(String id) {
    return atlas.entries().stream().filter(e -> e.id().equals(id)).findFirst()
      .map(e -> "/story/atlas?type=" + encode(e.type()) + "&entity=" + encode(id)).orElse("/story/atlas");
  }

  public String relatedName(String id) {
    return atlas.entries().stream().filter(e -> e.id().equals(id)).findFirst()
      .map(SessionAtlas.Entry::name).orElse(id);
  }

  public String refreshUrl() {
    return entryUrl(selected == null ? "" : selected.id());
  }

  public List<SessionAtlas.Relation> activeRelations() {
    return selected == null ? List.of() : selected.relations().stream()
      .filter(r -> r.status().equals("ACTIVE")).toList();
  }

  public List<SessionAtlas.Relation> otherRelations() {
    return selected == null ? List.of() : selected.relations().stream()
      .filter(r -> !r.status().equals("ACTIVE")).toList();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
