package nl.llm.storyteller.api.story;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import nl.llm.storyteller.api.session.SessionSettingsService;
import nl.llm.storyteller.core.graph.PredicateCatalog;
import nl.llm.storyteller.core.graph.model.Fact;
import nl.llm.storyteller.core.graph.model.Polarity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only presentation of session profiles and graph facts, shared by both frontends. */
public final class SessionAtlasService {
  private static final ObjectMapper YAML = new ObjectMapper(
    YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
  );
  private final SessionSettingsService settingsService;
  private final PredicateCatalog predicates;

  public SessionAtlasService(SessionSettingsService settingsService, PredicateCatalog predicates) {
    this.settingsService = settingsService;
    this.predicates = predicates;
  }

  public SessionAtlas load(String sessionId) {
    var settings = settingsService.load(sessionId);
    var graph = settings.knowledgeGraph();
    Map<String, List<SessionAtlas.Relation>> relations = new LinkedHashMap<>();
    graph.entities().keySet().forEach(id -> relations.put(id, new ArrayList<>()));
    for (Fact fact : graph.facts()) {
      var definition = predicates.require(fact.predicate());
      String subjectId = fact.subject().value();
      String objectId = fact.object().value();
      String verb = fact.polarity() == Polarity.NEGATIVE ? definition.negativeText() : definition.positiveText();
      String text = graph.entities().get(subjectId).name() + " " + verb + " "
        + graph.entities().get(objectId).name();
      var relation = new SessionAtlas.Relation(
        fact.id(), text, subjectId, objectId, fact.status().name(),
        label(fact.source().name()), fact.sourceTurn(), fact.hard()
      );
      relations.get(subjectId).add(relation);
      if (!subjectId.equals(objectId)) relations.get(objectId).add(relation);
    }
    Map<String, SessionAtlas.Entry> entries = new LinkedHashMap<>();
    graph.entities().forEach((id, entity) -> entries.put(id, new SessionAtlas.Entry(
      id, entity.type().name(), entity.name(), entity.aliases(), label(entity.source().name()),
      relations.get(id), List.of(), ""
    )));
    String warning = "";
    try {
      JsonNode document = YAML.readTree(settings.prompts().fixedProtagonists());
      if (document == null || !document.path("fixed_protagonists").isObject()) {
        warning = "Fixed protagonists must contain a fixed_protagonists mapping. Graph facts are still available.";
      } else {
        addProfiles(entries, document.path("fixed_protagonists"));
      }
    } catch (JsonProcessingException _) {
      warning = "Fixed protagonists could not be read. Graph facts are still available; check the YAML in Settings.";
    }
    var sorted = entries.values().stream()
      .sorted(Comparator.comparing(SessionAtlas.Entry::name, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(SessionAtlas.Entry::id)).toList();
    return new SessionAtlas(sessionId, graph.revision(), sorted, warning);
  }

  private void addProfiles(Map<String, SessionAtlas.Entry> entries, JsonNode protagonists) {
    List<Profile> profiles = new ArrayList<>();
    Map<String, List<Profile>> matches = new LinkedHashMap<>();
    protagonists.properties().forEach(property -> {
      JsonNode node = property.getValue();
      Set<String> names = new LinkedHashSet<>();
      names.add(property.getKey());
      addNames(names, node);
      addNames(names, node.path("profile"));
      String name = names.stream().filter(n -> !n.isBlank()).findFirst().orElse(property.getKey());
      var profile = new Profile(property.getKey(), name, List.copyOf(names), fields(node));
      profiles.add(profile);
      var candidates = entries.values().stream().filter(e -> e.type().equals("CHARACTER"))
        .filter(e -> matches(e, names)).map(SessionAtlas.Entry::id).toList();
      profile.candidates.addAll(candidates);
      candidates.forEach(id -> matches.computeIfAbsent(id, ignored -> new ArrayList<>()).add(profile));
    });
    for (var profile : profiles) {
      if (profile.candidates.size() == 1 && matches.get(profile.candidates.getFirst()).size() == 1) {
        String id = profile.candidates.getFirst();
        var entry = entries.get(id);
        Set<String> aliases = new LinkedHashSet<>(entry.aliases());
        aliases.addAll(profile.names);
        aliases.removeIf(alias -> normalize(alias).equals(normalize(entry.name())));
        entries.put(id, new SessionAtlas.Entry(id, entry.type(), entry.name(), List.copyOf(aliases),
          entry.source(), entry.relations(), profile.fields, ""));
      } else {
        String notice = profile.candidates.isEmpty() ? ""
          : "No unique graph match was found. This profile is shown separately to avoid combining different people.";
        var aliases = profile.names.stream().filter(n -> !n.equals(profile.name)).toList();
        entries.put("profile:" + profile.key, new SessionAtlas.Entry("profile:" + profile.key,
          "CHARACTER", profile.name, aliases, "Fixed protagonists", List.of(), profile.fields, notice));
      }
    }
  }

  private static boolean matches(SessionAtlas.Entry entry, Set<String> names) {
    Set<String> identifiers = new LinkedHashSet<>();
    identifiers.add(normalize(entry.id()));
    identifiers.add(normalize(entry.name()));
    entry.aliases().forEach(alias -> identifiers.add(normalize(alias)));
    return names.stream().map(SessionAtlasService::normalize).anyMatch(identifiers::contains);
  }

  private static String normalize(String value) {
    return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private static void addNames(Set<String> names, JsonNode node) {
    for (String key : List.of("name", "full_name")) {
      JsonNode name = node.path(key);
      if (name.isTextual() && !name.asText().isBlank()) names.add(name.asText().strip());
    }
    JsonNode aliases = node.path("aliases");
    if (aliases.isTextual() && !aliases.asText().isBlank()) names.add(aliases.asText().strip());
    if (aliases.isArray()) aliases.forEach(alias -> {
      if (alias.isTextual() && !alias.asText().isBlank()) names.add(alias.asText().strip());
    });
  }

  private static List<SessionAtlas.ProfileField> fields(JsonNode node) {
    List<SessionAtlas.ProfileField> fields = new ArrayList<>();
    if (node.isObject()) {
      node.properties().forEach(p -> fields.add(field(fieldLabel(p.getKey()), p.getValue())));
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) fields.add(field("Item " + (i + 1), node.get(i)));
    } else {
      fields.add(field("Value", node));
    }
    return fields;
  }

  private static SessionAtlas.ProfileField field(String label, JsonNode value) {
    if (value.isArray() && !value.isEmpty()) {
      List<String> values = new ArrayList<>();
      for (JsonNode item : value) {
        if (item.isContainerNode()) return new SessionAtlas.ProfileField(label, "", fields(value));
        values.add(item.isNull() ? "Not specified" : item.asText());
      }
      return new SessionAtlas.ProfileField(label, String.join(", ", values), List.of());
    }
    if (value.isContainerNode() && !value.isEmpty()) {
      return new SessionAtlas.ProfileField(label, "", fields(value));
    }
    return new SessionAtlas.ProfileField(label,
      value.isNull() ? "Not specified" : value.isContainerNode() ? value.toString() : value.asText(), List.of());
  }

  private static final class Profile {
    private final String key;
    private final String name;
    private final List<String> names;
    private final List<SessionAtlas.ProfileField> fields;
    private final List<String> candidates = new ArrayList<>();

    private Profile(String key, String name, List<String> names, List<SessionAtlas.ProfileField> fields) {
      this.key = key;
      this.name = name;
      this.names = names;
      this.fields = fields;
    }
  }

  private static String label(String value) {
    return fieldLabel(value.toLowerCase(Locale.ROOT));
  }

  private static String fieldLabel(String value) {
    if (value.isBlank()) return "Unnamed field";
    String text = value.replace('_', ' ');
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }
}
