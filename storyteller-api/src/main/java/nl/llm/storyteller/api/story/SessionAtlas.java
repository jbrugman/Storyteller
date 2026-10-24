package nl.llm.storyteller.api.story;

import java.util.List;

public record SessionAtlas(String sessionId, long revision, List<Entry> entries, String profileWarning) {
  public SessionAtlas {
    entries = List.copyOf(entries);
  }

  public record Entry(String id, String type, String name, List<String> aliases, String source,
                      List<Relation> relations, List<ProfileField> profile, String profileNotice) {
    public Entry {
      aliases = List.copyOf(aliases);
      relations = List.copyOf(relations);
      profile = List.copyOf(profile);
    }
  }

  public record ProfileField(String label, String value, List<ProfileField> children) {
    public ProfileField {
      children = List.copyOf(children);
    }
  }

  public record Relation(String id, String text, String subjectId, String objectId, String status,
                         String source, Integer sourceTurn, boolean hard) { }
}
