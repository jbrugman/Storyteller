package nl.llm.storyteller.core.graph.turnbasedservice;

import com.fasterxml.jackson.databind.JsonNode;
import nl.llm.storyteller.core.graph.model.Entity;
import nl.llm.storyteller.core.graph.model.EntityType;
import nl.llm.storyteller.core.graph.model.FactSource;
import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;
import nl.llm.storyteller.core.model.Message;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks extraction evidence against the actual assistant responses; evidence is never persisted. */
final class TurnBasedCharacterAdmission {
  private TurnBasedCharacterAdmission() { }

  static KnowledgeGraphDocument apply(KnowledgeGraphDocument current, KnowledgeGraphDocument candidate,
                                     JsonNode evidence, List<Message> turns) {
    List<String> responses = turns.stream().filter(m -> "assistant".equals(m.role()))
      .map(Message::content).toList();
    Map<String, Set<String>> owners = new HashMap<>();
    current.entities().forEach((id, entity) -> indexEntity(owners, id, entity));
    candidate.entities().forEach((id, entity) -> indexEntity(owners, id, entity));
    Map<String, Name> names = new HashMap<>();
    candidate.entities().forEach((id, entity) -> {
      if (entity.type() != EntityType.CHARACTER || current.entities().containsKey(id)) return;
      JsonNode proof = evidence.path(id);
      String first = text(proof.path("firstName"));
      String last = text(proof.path("lastName"));
      if (first.isBlank() || last.isBlank()) return;
      var name = new Name(first, last);
      names.put(id, name);
      index(owners, id, first);
      index(owners, id, last);
      index(owners, id, name.full());
    });

    Map<String, Entity> entities = new LinkedHashMap<>(candidate.entities());
    Set<String> rejected = new HashSet<>();
    candidate.entities().forEach((id, entity) -> {
      if (entity.type() != EntityType.CHARACTER) return;
      Entity existing = current.entities().get(id);
      if (existing != null) {
        // Later batches must not replace a known full name with a shortened model-generated name.
        entities.put(id, existing);
        return;
      }
      Name name = names.get(id);
      if (name == null || !qualifies(id, name, evidence.path(id), responses, owners)) {
        entities.remove(id);
        rejected.add(id);
      } else {
        entities.put(id, new Entity(EntityType.CHARACTER, name.full(), List.of(name.first()), FactSource.TURNBASED));
      }
    });
    var facts = candidate.facts().stream().filter(f ->
      (f.subject() == null || !rejected.contains(f.subject().value()))
        && (f.object() == null || !rejected.contains(f.object().value()))).toList();
    return new KnowledgeGraphDocument(candidate.schemaVersion(), candidate.revision(), entities, facts);
  }

  private static boolean qualifies(String id, Name name, JsonNode proof, List<String> responses,
                                   Map<String, Set<String>> owners) {
    if (!unique(owners, name.full(), id)) return false;
    JsonNode introduction = proof.path("introduction");
    int introducedIn = responseIndex(introduction.path("response"), responses.size());
    String quote = text(introduction.path("quote"));
    if (introducedIn < 0 || quote.isBlank() || !containsName(quote, name.first()) || !containsName(quote, name.last())
      || !normalize(responses.get(introducedIn)).contains(normalize(quote))) return false;

    Set<Integer> mentionedIn = new HashSet<>();
    mentionedIn.add(introducedIn);
    Set<String> allowed = Set.copyOf(List.of(normalize(name.first()), normalize(name.last()), normalize(name.full())));
    JsonNode mentions = proof.path("mentions");
    if (!mentions.isArray()) return false;
    for (JsonNode mention : mentions) {
      int index = responseIndex(mention.path("response"), responses.size());
      String usedName = text(mention.path("name"));
      if (index >= 0 && allowed.contains(normalize(usedName)) && unique(owners, usedName, id)
        && containsName(responses.get(index), usedName)) mentionedIn.add(index);
    }
    return mentionedIn.size() >= 3;
  }

  private static int responseIndex(JsonNode number, int size) {
    if (!number.isIntegralNumber() || !number.canConvertToInt()) return -1;
    int index = number.intValue() - 1;
    return index >= 0 && index < size ? index : -1;
  }

  private static String text(JsonNode node) {
    return node.isTextual() ? node.textValue().strip().replaceAll("\\s+", " ") : "";
  }

  private static String normalize(String value) {
    return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private static boolean containsName(String text, String name) {
    return !name.isBlank() && Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}_])" + Pattern.quote(normalize(name))
      + "(?![\\p{L}\\p{M}\\p{N}_])").matcher(normalize(text)).find();
  }

  private static boolean unique(Map<String, Set<String>> owners, String name, String id) {
    return Set.of(id).equals(owners.get(normalize(name)));
  }

  private static void indexEntity(Map<String, Set<String>> owners, String id, Entity entity) {
    if (entity.type() != EntityType.CHARACTER) return;
    index(owners, id, entity.name());
    entity.aliases().forEach(alias -> index(owners, id, alias));
    // Known names may have compound given names or surnames. Mark their prefixes/suffixes
    // as potentially ambiguous rather than guessing a split or assigning them to another person.
    String name = normalize(entity.name());
    for (int space = name.indexOf(' '); space >= 0; space = name.indexOf(' ', space + 1)) {
      index(owners, id, name.substring(0, space));
      index(owners, id, name.substring(space + 1));
    }
  }

  private static void index(Map<String, Set<String>> owners, String id, String name) {
    if (!name.isBlank()) owners.computeIfAbsent(normalize(name), ignored -> new HashSet<>()).add(id);
  }

  private record Name(String first, String last) {
    String full() { return first + " " + last; }
  }
}
