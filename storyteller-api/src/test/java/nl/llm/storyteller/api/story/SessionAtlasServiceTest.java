package nl.llm.storyteller.api.story;

import nl.llm.storyteller.api.session.SessionSettingsService;
import nl.llm.storyteller.core.graph.KnowledgeGraphValidator;
import nl.llm.storyteller.core.graph.PredicateCatalog;
import nl.llm.storyteller.core.graph.model.Entity;
import nl.llm.storyteller.core.graph.model.EntityId;
import nl.llm.storyteller.core.graph.model.EntityType;
import nl.llm.storyteller.core.graph.model.Fact;
import nl.llm.storyteller.core.graph.model.FactSource;
import nl.llm.storyteller.core.graph.model.FactStatus;
import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;
import nl.llm.storyteller.core.graph.model.Polarity;
import nl.llm.storyteller.core.graph.model.PredicateId;
import nl.llm.storyteller.db.SessionPrompts;
import nl.llm.storyteller.db.SessionSettings;
import nl.llm.storyteller.db.SessionSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionAtlasServiceTest {
  private static final String SESSION_ID = "atlas-session";
  private static final PredicateCatalog PREDICATES = PredicateCatalog.fromJson("""
    {"predicates":{"TRUSTS":{"subjectType":"CHARACTER","objectType":"CHARACTER",
    "temporal":false,"positiveText":"relies on","negativeText":"does not rely on"}}}
    """);

  @ParameterizedTest(name = "{0}, {1}")
  @MethodSource("factVariants")
  @DisplayName("""
    Given a stored directed fact and a configured predicate catalog,
    When the atlas is loaded,
    Then both endpoints retain the same direction, polarity, status, and source metadata
    """)
  void shouldPreserveDirectedFacts(Polarity polarity, FactStatus status, String sentence) {
    // Given
    var fact = fact("character.linde", "character.nora", polarity, status);
    var repository = repository("fixed_protagonists: {}", graph(List.of(fact)));
    var service = service(repository);

    // When
    var atlas = service.load(SESSION_ID);

    // Then
    var relation = entry(atlas, "character.linde").relations().getFirst();
    assertEquals(sentence, relation.text());
    assertEquals("character.linde", relation.subjectId());
    assertEquals("character.nora", relation.objectId());
    assertEquals(status.name(), relation.status());
    assertEquals("Fixed protagonist", relation.source());
    assertEquals(7, relation.sourceTurn());
    assertTrue(relation.hard());
    assertEquals(List.of(relation), entry(atlas, "character.nora").relations());
    assertEquals(SESSION_ID, atlas.sessionId());
    assertEquals(12, atlas.revision());
    assertEquals(List.of("Linde", "Nora"), atlas.entries().stream().map(SessionAtlas.Entry::name).toList());
    assertSame(repository.settings.knowledgeGraph(), repository.original.knowledgeGraph());
    assertEquals(repository.original, repository.settings);
  }

  static Stream<Arguments> factVariants() {
    return Stream.of(FactStatus.values()).flatMap(status -> Stream.of(
      Arguments.of(Polarity.POSITIVE, status, "Linde relies on Nora"),
      Arguments.of(Polarity.NEGATIVE, status, "Linde does not rely on Nora")
    ));
  }

  @ParameterizedTest
  @EnumSource(FactStatus.class)
  @DisplayName("""
    Given a fact whose subject and object are the same character,
    When the atlas is loaded,
    Then the fact is shown once for that character regardless of status
    """)
  void shouldNotDuplicateSelfRelations(FactStatus status) {
    // Given
    var service = service(repository("fixed_protagonists: {}", graph(List.of(
      fact("character.linde", "character.linde", Polarity.POSITIVE, status)
    ))));

    // When
    var atlas = service.load(SESSION_ID);

    // Then
    assertEquals(1, entry(atlas, "character.linde").relations().size());
    assertTrue(entry(atlas, "character.nora").relations().isEmpty());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scalarLists")
  @DisplayName("""
    Given a fixed-protagonist profile containing a list of scalar values,
    When the atlas is loaded,
    Then the values are comma-separated without numbered child fields
    """)
  void shouldPresentScalarListsInline(String yamlValue, String expected) {
    // Given
    var service = service(repository("fixed_protagonists:\n  Linde:\n    traits: " + yamlValue,
      KnowledgeGraphDocument.empty()));

    // When
    var atlas = service.load(SESSION_ID);

    // Then
    var traits = field(entry(atlas, "profile:Linde").profile(), "Traits");
    assertEquals(expected, traits.value());
    assertTrue(traits.children().isEmpty());
  }

  static Stream<Arguments> scalarLists() {
    return Stream.of(
      Arguments.of("[Loyal, Playful]", "Loyal, Playful"),
      Arguments.of("[Scientist]", "Scientist"),
      Arguments.of("[0, false, null]", "0, false, Not specified"),
      Arguments.of("['<b>Kind</b>', 'Calm & patient']", "<b>Kind</b>, Calm & patient"),
      Arguments.of("['', Quiet]", ", Quiet"),
      Arguments.of("[]", "[]")
    );
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
    "[{name: Compass, quantity: 1}, {name: Lantern, quantity: 2}]",
    "[Compass, {name: Lantern, quantity: 2}]",
    "[[Compass, Lantern], {name: Lantern, quantity: 2}]"
  })
  @DisplayName("""
    Given a profile list containing objects or nested lists,
    When the atlas is loaded,
    Then structured entries retain their fields instead of becoming comma-separated object text
    """)
  void shouldRetainStructuredListEntries(String equipment) {
    // Given
    var service = service(repository("fixed_protagonists:\n  Linde:\n    equipment: " + equipment,
      KnowledgeGraphDocument.empty()));

    // When
    var atlas = service.load(SESSION_ID);

    // Then
    var items = field(entry(atlas, "profile:Linde").profile(), "Equipment");
    assertEquals("", items.value());
    assertEquals(2, items.children().size());
    assertEquals("Lantern", field(items.children().get(1).children(), "Name").value());
    assertEquals("2", field(items.children().get(1).children(), "Quantity").value());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("profileMatches")
  @DisplayName("""
    Given one profile with a unique graph name, identifier, or alias match,
    When the atlas is loaded,
    Then the profile is attached to that character while all graph entries remain available
    """)
  void shouldAttachOnlyUniqueProfileMatches(String yaml) {
    // Given
    var service = service(repository(yaml, graph(List.of())));

    // When
    var atlas = service.load(SESSION_ID);

    // Then
    assertEquals(2, atlas.entries().size());
    assertFalse(entry(atlas, "character.linde").profile().isEmpty());
    assertTrue(entry(atlas, "character.nora").profile().isEmpty());
    assertEquals("", entry(atlas, "character.linde").profileNotice());
    assertEquals(List.of("Cartographer"), entry(atlas, "character.linde").aliases().stream()
      .filter(alias -> alias.equals("Cartographer")).toList());
  }

  static Stream<String> profileMatches() {
    return Stream.of(
      "fixed_protagonists:\n  Linde:\n    age: 28",
      "fixed_protagonists:\n  character.linde:\n    age: 28",
      "fixed_protagonists:\n  Profile:\n    name: ' lInDe '\n    age: 28",
      "fixed_protagonists:\n  Profile:\n    full_name: Linde\n    age: 28",
      "fixed_protagonists:\n  Profile:\n    aliases: [Cartographer]\n    age: 28",
      "fixed_protagonists:\n  Profile:\n    aliases: Cartographer\n    age: 28",
      "fixed_protagonists:\n  Profile:\n    profile:\n      full_name: Linde\n      age: 28",
      "fixed_protagonists:\n  Profile:\n    profile:\n      aliases: [Cartographer]\n      age: 28"
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("ambiguousProfiles")
  @DisplayName("""
    Given profiles with multiple possible matches or competing for the same graph character,
    When the atlas is loaded,
    Then profiles remain separate with an ambiguity notice and neither graph character gains a profile
    """)
  void shouldKeepAmbiguousProfilesSeparate(String yaml, int profileCount) {
    // Given
    var service = service(repository(yaml, graph(List.of())));

    // When
    var atlas = service.load(SESSION_ID);

    // Then
    assertEquals(2 + profileCount, atlas.entries().size());
    assertTrue(entry(atlas, "character.linde").profile().isEmpty());
    assertTrue(entry(atlas, "character.nora").profile().isEmpty());
    var profiles = atlas.entries().stream().filter(e -> e.id().startsWith("profile:")).toList();
    assertEquals(profileCount, profiles.size());
    assertTrue(profiles.stream().allMatch(e -> !e.profileNotice().isBlank()));
  }

  static Stream<Arguments> ambiguousProfiles() {
    return Stream.of(
      Arguments.of("fixed_protagonists:\n  Linde:\n    aliases: [Nora]\n    age: 28", 1),
      Arguments.of("fixed_protagonists:\n  First:\n    name: Linde\n  Second:\n    name: Linde", 2)
    );
  }

  @ParameterizedTest
  @EnumSource(value = EntityType.class, names = {"LOCATION", "ITEM", "SKILL"})
  @DisplayName("""
    Given a graph entry with the same name as a protagonist but a different entity type,
    When the atlas is loaded,
    Then the protagonist remains a separate character and is not attached to the non-character entry
    """)
  void shouldNotAttachProfilesToNonCharacters(EntityType type) {
    // Given
    var graph = new KnowledgeGraphDocument(1, 3,
      Map.of("other.linde", new Entity(type, "Linde", List.of())), List.of());
    var service = service(repository("fixed_protagonists:\n  Linde:\n    age: 28", graph));

    // When
    var atlas = service.load(SESSION_ID);

    // Then
    assertEquals(2, atlas.entries().size());
    assertTrue(entry(atlas, "other.linde").profile().isEmpty());
    assertEquals("CHARACTER", entry(atlas, "profile:Linde").type());
    assertEquals("28", field(entry(atlas, "profile:Linde").profile(), "Age").value());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"", "null", "[]", "other: {}", "fixed_protagonists: []",
    "fixed_protagonists: [broken", "fixed_protagonists:\n  Linde: {}\n  Linde: {}"})
  @DisplayName("""
    Given unreadable or unsupported stored protagonist YAML and a valid graph,
    When the atlas is loaded,
    Then a warning is returned while graph entries remain available
    """)
  void shouldKeepGraphAvailableWhenProfilesCannotBeRead(String yaml) {
    // Given
    var service = service(repository(yaml, graph(List.of())));

    // When
    var atlas = service.load(SESSION_ID);

    // Then
    assertFalse(atlas.profileWarning().isBlank());
    assertEquals(2, atlas.entries().size());
    assertTrue(atlas.entries().stream().allMatch(e -> e.profile().isEmpty()));
  }

  @Test
  @DisplayName("""
    Given a profile with nested appearance and personality fields and no graph,
    When the atlas is loaded,
    Then all supplied profile fields remain visible without inferring missing data
    """)
  void shouldRetainNestedProfilesWithoutGraphEntries() {
    // Given
    var service = service(repository("""
      fixed_protagonists:
        Linde:
          profile:
            age: 28
            occupation: Cartographer
          residence: Inland villa
          appearance:
            eyes: Blue-green
          personality:
            traits: [Loyal, Playful]
      """, KnowledgeGraphDocument.empty()));

    // When
    var atlas = service.load(SESSION_ID);

    // Then
    assertEquals(1, atlas.entries().size());
    var linde = entry(atlas, "profile:Linde");
    assertEquals("28", field(field(linde.profile(), "Profile").children(), "Age").value());
    assertEquals("Blue-green", field(field(linde.profile(), "Appearance").children(), "Eyes").value());
    assertEquals("Loyal, Playful", field(field(linde.profile(), "Personality").children(), "Traits").value());
    assertEquals("Inland villa", field(linde.profile(), "Residence").value());
    assertTrue(linde.relations().isEmpty());
    assertEquals("", atlas.profileWarning());
  }

  @Test
  @DisplayName("""
    Given a session whose saved profile changes after an atlas read,
    When the atlas is loaded again,
    Then the updated profile is returned without changing the stored settings or graph
    """)
  void shouldReadCurrentProfilesWithoutWritingSettings() {
    // Given
    var repository = repository("fixed_protagonists:\n  Linde:\n    age: 28", graph(List.of()));
    var service = service(repository);
    var first = service.load(SESSION_ID);
    repository.settings = new SessionSettings(new SessionPrompts("System",
      "fixed_protagonists:\n  Linde:\n    age: 31", "Rules"), repository.settings.knowledgeGraph());
    var saved = repository.settings;

    // When
    var second = service.load(SESSION_ID);

    // Then
    assertEquals("28", field(entry(first, "character.linde").profile(), "Age").value());
    assertEquals("31", field(entry(second, "character.linde").profile(), "Age").value());
    assertSame(saved, repository.settings);
    assertSame(repository.original.knowledgeGraph(), repository.settings.knowledgeGraph());
  }

  private static SessionAtlasService service(ReadOnlyRepository repository) {
    return new SessionAtlasService(new SessionSettingsService(repository, new KnowledgeGraphValidator(PREDICATES)), PREDICATES);
  }

  private static ReadOnlyRepository repository(String yaml, KnowledgeGraphDocument graph) {
    return new ReadOnlyRepository(new SessionSettings(new SessionPrompts("System", yaml, "Rules"), graph));
  }

  private static KnowledgeGraphDocument graph(List<Fact> facts) {
    return new KnowledgeGraphDocument(1, 12, Map.of(
      "character.nora", new Entity(EntityType.CHARACTER, "Nora", List.of()),
      "character.linde", new Entity(EntityType.CHARACTER, "Linde", List.of("Cartographer"))
    ), facts);
  }

  private static Fact fact(String subject, String object, Polarity polarity, FactStatus status) {
    return new Fact("fact.trust", new EntityId(subject), new PredicateId("TRUSTS"), new EntityId(object),
      polarity, status, FactSource.FIXED_PROTAGONIST, 7, true);
  }

  private static SessionAtlas.Entry entry(SessionAtlas atlas, String id) {
    return atlas.entries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
  }

  private static SessionAtlas.ProfileField field(List<SessionAtlas.ProfileField> fields, String label) {
    return fields.stream().filter(f -> f.label().equals(label)).findFirst().orElseThrow();
  }

  private static final class ReadOnlyRepository implements SessionSettingsRepository {
    private final SessionSettings original;
    private SessionSettings settings;

    private ReadOnlyRepository(SessionSettings settings) {
      original = settings;
      this.settings = settings;
    }

    @Override public SessionSettings load(String sessionId) {
      assertEquals(SESSION_ID, sessionId);
      return settings;
    }

    @Override public void save(String sessionId, SessionSettings settings) {
      throw new AssertionError("Atlas must not save settings");
    }

    @Override public void patchConfiguration(String sessionId, Map<String, String> prompts,
                                             boolean updateTemperature, Double temperature) {
      throw new AssertionError("Atlas must not patch settings");
    }

    @Override public void setTemperature(String sessionId, Double temperature) {
      throw new AssertionError("Atlas must not change temperature");
    }
  }
}
