package nl.llm.storyteller.core.graph.turnbasedservice;

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
import nl.llm.storyteller.core.graph.persistence.KnowledgeGraphStore;
import nl.llm.storyteller.core.graph.service.ReadOnlyKnowledgeGraphService;
import nl.llm.storyteller.core.model.Message;
import nl.llm.storyteller.core.service.ChatClient;
import nl.llm.storyteller.core.service.DerivedMemoryTaskQueue;
import nl.llm.storyteller.core.service.HistoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnBasedKnowledgeGraphServiceTest {
  @TempDir
  Path tempDir;

  @Test
  @DisplayName("""
    Given a turn-based graph batch size of three turns,
    When three completed story turns have been persisted,
    Then exactly that batch should be extracted and stored as lower-authority TURNBASED graph data
    """)
  void updatesGraphAfterConfiguredNumberOfTurns() throws Exception {
    TestContext context = context();
    AtomicReference<List<Message>> request = new AtomicReference<>();
    CountDownLatch completed = new CountDownLatch(1);
    ChatClient client = (messages, _, _) -> {
      request.set(messages);
      completed.countDown();
      return """
        {
          "schemaVersion": 1,
          "revision": 0,
          "entities": {
            "character.valerie": {"type":"CHARACTER", "name":"Valerie", "aliases":[]},
            "item.microphone": {"type":"ITEM", "name":"Microphone", "aliases":[]}
          },
          "facts": [{
            "id":"fact.valerie_microphone",
            "subject":"character.valerie",
            "predicate":"POSSESSES",
            "object":"item.microphone",
            "polarity":"POSITIVE"
          }],
          "characterEvidence": {"character.valerie": {
            "firstName":"Valerie", "lastName":"Thorne",
            "introduction":{"response":1,"quote":"My name is Valerie Thorne."},
            "mentions":[{"response":1,"name":"Valerie Thorne"},
              {"response":2,"name":"Valerie"},{"response":3,"name":"Thorne"}]
          }}
        }
        """;
    };
    try (DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue()) {
      TurnBasedKnowledgeGraphService service = service(context, client, queue);
      context.historyStore().appendTurn("Turn 1", "Reply 1. My name is Valerie Thorne.");
      service.startUpdateIfNeeded();
      context.historyStore().appendTurn("Turn 2", "Reply 2. Valerie returns.");
      service.startUpdateIfNeeded();

      assertFalse(completed.await(100, TimeUnit.MILLISECONDS));

      context.historyStore().appendTurn("Turn 3", "Reply 3. Thorne speaks.");
      service.startUpdateIfNeeded();

      assertTrue(completed.await(5, TimeUnit.SECONDS));
      assertTrue(queue.awaitIdle(5, TimeUnit.SECONDS));
      KnowledgeGraphDocument graph = context.store().load();
      assertEquals(FactSource.TURNBASED, graph.entities().get("character.valerie").source());
      assertEquals("Valerie Thorne", graph.entities().get("character.valerie").name());
      assertEquals(List.of("Valerie"), graph.entities().get("character.valerie").aliases());
      assertEquals(FactSource.TURNBASED, graph.entities().get("item.microphone").source());
      assertEquals(FactSource.TURNBASED, graph.facts().getFirst().source());
      assertEquals(3, graph.facts().getFirst().sourceTurn());
      assertFalse(graph.facts().getFirst().hard());
      String systemPrompt = request.get().getFirst().content();
      String prompt = request.get().getLast().content();
      assertTrue(systemPrompt.contains("Use exactly this object shape and field names"));
      assertTrue(systemPrompt.contains("\"character.alice\""));
      assertTrue(systemPrompt.contains("Every entity requires type, name, aliases, and source"));
      assertTrue(systemPrompt.contains("Every fact requires id, subject, predicate"));
      assertTrue(systemPrompt.contains("Treat interactions as events, not proof of an enduring interpersonal relationship"));
      assertTrue(systemPrompt.contains("Talking,\nflirting, kissing"));
      assertTrue(systemPrompt.contains("When in doubt, omit it"));
      assertTrue(systemPrompt.contains("Never emit LIVES or LIVES_WITH from story turns"));
      assertFalse(systemPrompt.contains("LIVES (CHARACTER to LOCATION)"));
      assertFalse(systemPrompt.contains("LIVES_WITH (CHARACTER to CHARACTER)"));
      assertTrue(systemPrompt.contains("Represent clothing with WEARS from a CHARACTER to an ITEM"));
      assertTrue(systemPrompt.contains("one ITEM entity and one\nWEARS fact per distinct garment"));
      assertTrue(prompt.contains("USER: Turn 1"));
      assertTrue(prompt.contains("ASSISTANT: Reply 3"));
    }
  }

  @Test
  @DisplayName("""
    Given an existing story whose graph has never received turn-based data,
    When another turn is persisted beyond an absolute batch boundary,
    Then the overdue turn-based graph update should still be requested
    """)
  void catchesUpGraphWithoutTurnBasedCursor() throws Exception {
    TestContext context = context();
    for (int turn = 1; turn <= 47; turn++) {
      appendTurn(context.historyStore(), turn);
    }
    CountDownLatch requested = new CountDownLatch(1);
    ChatClient client = (_, _, _) -> {
      requested.countDown();
      return """
        {
          "schemaVersion": 1,
          "revision": 0,
          "entities": {},
          "facts": []
        }
        """;
    };

    try (DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue()) {
      service(context, client, queue).startUpdateIfNeeded();

      assertTrue(requested.await(5, TimeUnit.SECONDS));
    }
  }

  @Test
  @DisplayName("""
    Given fixed-protagonist graph data and a conflicting turn-based extraction,
    When the extracted graph is merged,
    Then fixed entities and facts should remain unchanged
    """)
  void preservesHigherAuthorityGraphDataDuringMerge() {
    TestContext context = context();
    EntityId valerie = new EntityId("character.valerie");
    EntityId microphone = new EntityId("item.microphone");
    EntityId chris = new EntityId("character.chris");
    EntityId piano = new EntityId("item.piano");
    Fact fixedFact = fact("fact.fixed", valerie, microphone, Polarity.POSITIVE, FactSource.FIXED_PROTAGONIST, true);
    Fact manualFact = fact("fact.manual", chris, piano, Polarity.POSITIVE, FactSource.MANUAL, true);
    KnowledgeGraphDocument current = new KnowledgeGraphDocument(
      1,
      4,
      Map.of(
        valerie.value(), new Entity(EntityType.CHARACTER, "Valerie", List.of(), FactSource.FIXED_PROTAGONIST),
        microphone.value(), new Entity(EntityType.ITEM, "Microphone", List.of(), FactSource.FIXED_PROTAGONIST),
        chris.value(), new Entity(EntityType.CHARACTER, "Chris", List.of(), FactSource.MANUAL),
        piano.value(), new Entity(EntityType.ITEM, "Piano", List.of(), FactSource.MANUAL)
      ),
      List.of(fixedFact, manualFact)
    );
    KnowledgeGraphDocument candidate = new KnowledgeGraphDocument(
      1,
      0,
      Map.of(
        valerie.value(), new Entity(EntityType.CHARACTER, "Wrong name", List.of(), FactSource.TURNBASED),
        microphone.value(), new Entity(EntityType.ITEM, "Wrong item", List.of(), FactSource.TURNBASED)
      ),
      List.of(fact("fact.generated", valerie, microphone, Polarity.NEGATIVE, FactSource.TURNBASED, false))
    );
    try (DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue()) {
      TurnBasedKnowledgeGraphService service = service(context, (_, _, _) -> "", queue);

      KnowledgeGraphDocument merged = service.merge(current, candidate, 3);

      assertEquals("Valerie", merged.entities().get(valerie.value()).name());
      assertEquals("Microphone", merged.entities().get(microphone.value()).name());
      assertEquals(current.entities().get(chris.value()), merged.entities().get(chris.value()));
      assertEquals(current.entities().get(piano.value()), merged.entities().get(piano.value()));
      assertEquals(List.of(fixedFact, manualFact), merged.facts());
    }
  }

  @Test
  @DisplayName("""
    Given existing and newly extracted turn-based residence facts,
    When the graph update is merged,
    Then turn-based residence facts should be removed while authoritative residence remains
    """)
  void shouldExcludeResidenceFactsFromTurnBasedGraphData() {
    TestContext context = context();
    EntityId valerie = new EntityId("character.valerie");
    EntityId home = new EntityId("location.home");
    EntityId marseille = new EntityId("location.marseille");
    EntityId calanques = new EntityId("location.calanques");
    Fact fixedHome = fact("fact.valerie_home", valerie, new PredicateId("LIVES"), home,
      Polarity.POSITIVE, FactSource.FIXED_PROTAGONIST, true);
    Fact pollutedResidence = fact("fact.valerie_marseille", valerie, new PredicateId("LIVES"), marseille,
      Polarity.POSITIVE, FactSource.TURNBASED, false);
    KnowledgeGraphDocument current = new KnowledgeGraphDocument(1, 4, Map.of(
      valerie.value(), new Entity(EntityType.CHARACTER, "Valerie", List.of(), FactSource.FIXED_PROTAGONIST),
      home.value(), new Entity(EntityType.LOCATION, "Home", List.of(), FactSource.FIXED_PROTAGONIST),
      marseille.value(), new Entity(EntityType.LOCATION, "Marseille", List.of(), FactSource.TURNBASED)
    ), List.of(fixedHome, pollutedResidence));
    KnowledgeGraphDocument candidate = new KnowledgeGraphDocument(1, 0, Map.of(
      calanques.value(), new Entity(EntityType.LOCATION, "Calanques", List.of(), FactSource.TURNBASED)
    ), List.of(fact("fact.valerie_calanques", valerie, new PredicateId("LIVES"), calanques,
      Polarity.POSITIVE, FactSource.TURNBASED, false)));

    try (DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue()) {
      KnowledgeGraphDocument merged = service(context, (_, _, _) -> "", queue)
        .merge(current, candidate, 6);

      assertEquals(List.of(fixedHome), merged.facts());
      assertFalse(merged.entities().containsKey(marseille.value()));
      assertFalse(merged.entities().containsKey(calanques.value()));
      assertTrue(merged.entities().containsKey(home.value()));
    }
  }

  @Test
  @DisplayName("""
    Given a turn-based shirt and an authoritative coat worn by a character,
    When a later turn-based clothing snapshot contains only a sweater,
    Then the shirt should disappear while the authoritative coat remains
    """)
  void replacesOnlyTurnBasedClothingForUpdatedCharacter() {
    TestContext context = context();
    EntityId valerie = new EntityId("character.valerie");
    EntityId shirt = new EntityId("item.shirt");
    EntityId coat = new EntityId("item.coat");
    EntityId sweater = new EntityId("item.sweater");
    Fact oldShirt = fact("fact.shirt", valerie, new PredicateId("WEARS"), shirt,
      Polarity.POSITIVE, FactSource.TURNBASED, false);
    Fact fixedCoat = fact("fact.coat", valerie, new PredicateId("WEARS"), coat,
      Polarity.POSITIVE, FactSource.FIXED_PROTAGONIST, true);
    KnowledgeGraphDocument current = new KnowledgeGraphDocument(1, 4, Map.of(
      valerie.value(), new Entity(EntityType.CHARACTER, "Valerie", List.of(), FactSource.FIXED_PROTAGONIST),
      shirt.value(), new Entity(EntityType.ITEM, "overhemd", List.of(), FactSource.TURNBASED),
      coat.value(), new Entity(EntityType.ITEM, "jas", List.of(), FactSource.FIXED_PROTAGONIST)
    ), List.of(oldShirt, fixedCoat));
    KnowledgeGraphDocument candidate = new KnowledgeGraphDocument(1, 0, Map.of(
      sweater.value(), new Entity(EntityType.ITEM, "trui", List.of(), FactSource.TURNBASED)
    ), List.of(fact("fact.sweater", valerie, new PredicateId("WEARS"), sweater,
      Polarity.POSITIVE, FactSource.TURNBASED, false)));
    try (DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue()) {
      TurnBasedKnowledgeGraphService service = service(context, (_, _, _) -> "", queue);

      KnowledgeGraphDocument merged = service.merge(current, candidate, 6);

      assertFalse(merged.entities().containsKey(shirt.value()));
      assertTrue(merged.entities().containsKey(sweater.value()));
      assertTrue(merged.facts().contains(fixedCoat));
      assertFalse(merged.facts().contains(oldShirt));
      assertTrue(merged.facts().stream().anyMatch(fact ->
        WEARS_ID.equals(fact.predicate()) && sweater.equals(fact.object())));
    }
  }

  @Test
  @DisplayName("""
    Given the graph revision changes while a turn-based extraction is running,
    When the stale candidate is ready to be persisted,
    Then it should be skipped without publishing or reporting a successful update
    """)
  void skipsStaleCandidateWithoutReportingSuccess() {
    TestContext context = context();
    appendTurn(context.historyStore(), 1);
    appendTurn(context.historyStore(), 2);
    appendTurn(context.historyStore(), 3);
    AtomicInteger succeeded = new AtomicInteger();
    AtomicInteger skipped = new AtomicInteger();
    ChatClient client = (_, _, _) -> {
      context.store().update(current -> new KnowledgeGraphDocument(
        current.schemaVersion(), current.revision() + 1, current.entities(), current.facts()
      ));
      return """
        {
          "schemaVersion": 1,
          "revision": 0,
          "entities": {},
          "facts": []
        }
        """;
    };
    KnowledgeGraphUpdateObserver observer = new KnowledgeGraphUpdateObserver() {
      @Override
      public void succeeded(int latestTurn, long revision, int entities, int facts) {
        succeeded.incrementAndGet();
      }

      @Override
      public void skipped(int latestTurn, long startingRevision, long currentRevision) {
        skipped.incrementAndGet();
      }
    };

    try (DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue()) {
      TurnBasedKnowledgeGraphService service = new TurnBasedKnowledgeGraphService(
        context.historyStore(), client, context.store(), context.graphService(), context.predicates(), queue,
        3, Map.of(), 10, observer
      );

      service.updateFromTurns(context.historyStore().load().messages(), 3);
    }

    assertEquals(1, context.store().load().revision());
    assertEquals(0, context.graphService().current().revision());
    assertEquals(0, succeeded.get());
    assertEquals(1, skipped.get());
  }

  private static final PredicateId WEARS_ID = new PredicateId("WEARS");

  @Test
  @DisplayName("""
    Given a qualified new character without relationship facts and an unqualified character with a possession,
    When an automatic graph update is persisted,
    Then only the qualified character is stored and facts referring to the rejected character are omitted
    """)
  void shouldPersistQualifiedStandaloneCharactersWithoutDanglingFacts() throws Exception {
    // Given
    TestContext context = context();
    context.historyStore().appendTurn("Opening", "My name is Mila de Vries.");
    context.historyStore().appendTurn("Continue", "Mila returns.");
    context.historyStore().appendTurn("Continue", "de Vries speaks.");
    AtomicReference<String> supplied = new AtomicReference<>();
    ChatClient client = (messages, _, _) -> {
      supplied.set(messages.getLast().content());
      return """
        {"schemaVersion":1,"revision":0,"entities":{
          "character.mila":{"type":"CHARACTER","name":"Mila","aliases":[]},
          "character.bob":{"type":"CHARACTER","name":"Bob","aliases":[]},
          "item.compass":{"type":"ITEM","name":"Compass","aliases":[]}
        },"facts":[{"id":"fact.bob_compass","subject":"character.bob",
          "predicate":"POSSESSES","object":"item.compass","polarity":"POSITIVE"}],
        "characterEvidence":{"character.mila":{
          "firstName":"Mila","lastName":"de Vries",
          "introduction":{"response":1,"quote":"My name is Mila de Vries."},
          "mentions":[{"response":1,"name":"Mila de Vries"},
            {"response":2,"name":"Mila"},{"response":3,"name":"de Vries"}]
        }}}
        """;
    };

    // When
    try (DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue()) {
      service(context, client, queue).updateFromTurns(context.historyStore().load().messages(), 3);
    }

    // Then
    var graph = context.store().load();
    assertEquals(1, graph.revision());
    assertEquals(Set.of("character.mila"), graph.entities().keySet());
    assertEquals("Mila de Vries", graph.entities().get("character.mila").name());
    assertEquals(List.of("Mila"), graph.entities().get("character.mila").aliases());
    assertEquals(FactSource.TURNBASED, graph.entities().get("character.mila").source());
    assertTrue(graph.facts().isEmpty());
    assertEquals(graph.entities().get("character.mila"),
      context.graphService().current().entity(new EntityId("character.mila")).orElseThrow());
    assertTrue(supplied.get().contains("Story response 1\nASSISTANT: My name is Mila de Vries."));
    assertTrue(supplied.get().contains("Story response 3\nASSISTANT: de Vries speaks."));
    assertFalse(Files.readString(tempDir.resolve("knowledge-graph.json")).contains("characterEvidence"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"LIVES", "LIVES_WITH"})
  @DisplayName("""
    Given a stored turn-based character whose only fact is an obsolete residence fact,
    When residence facts are cleaned up during a graph update,
    Then the named character remains while the residence fact and unreferenced location are removed
    """)
  void shouldRetainStandaloneCharactersDuringResidenceCleanup(String predicate) {
    // Given
    TestContext context = context();
    var person = new EntityId("character.mila");
    var other = new EntityId(predicate.equals("LIVES") ? "location.home" : "character.nora");
    var current = new KnowledgeGraphDocument(1, 2, Map.of(
      person.value(), new Entity(EntityType.CHARACTER, "Mila de Vries", List.of("Mila"), FactSource.TURNBASED),
      other.value(), new Entity(predicate.equals("LIVES") ? EntityType.LOCATION : EntityType.CHARACTER,
        "Other", List.of(), FactSource.TURNBASED)
    ), List.of(fact("fact.residence", person, new PredicateId(predicate), other,
      Polarity.POSITIVE, FactSource.TURNBASED, false)));

    // When
    KnowledgeGraphDocument merged;
    try (DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue()) {
      merged = service(context, (_, _, _) -> "", queue).merge(current, KnowledgeGraphDocument.empty(), 3);
    }

    // Then
    assertEquals(current.entities().get(person.value()), merged.entities().get(person.value()));
    assertTrue(merged.facts().isEmpty());
    assertEquals(predicate.equals("LIVES_WITH"), merged.entities().containsKey(other.value()));
  }

  private TestContext context() {
    KnowledgeGraphStore store = new KnowledgeGraphStore(tempDir.resolve("knowledge-graph.json"));
    return new TestContext(
      new HistoryStore(tempDir.resolve("history.json"), tempDir.resolve("history.md")),
      store,
      new ReadOnlyKnowledgeGraphService(store),
      PredicateCatalog.load(Path.of(System.getProperty("user.dir")).toAbsolutePath())
    );
  }

  private TurnBasedKnowledgeGraphService service(
    TestContext context,
    ChatClient client,
    DerivedMemoryTaskQueue queue
  ) {
    return new TurnBasedKnowledgeGraphService(
      context.historyStore(), client, context.store(), context.graphService(), context.predicates(), queue,
      3, Map.of(), 10
    );
  }

  private void appendTurn(HistoryStore historyStore, int turn) {
    historyStore.appendTurn("Turn " + turn, "Reply " + turn);
  }

  private Fact fact(
    String id,
    EntityId subject,
    EntityId object,
    Polarity polarity,
    FactSource source,
    boolean hard
  ) {
    return fact(id, subject, new PredicateId("POSSESSES"), object, polarity, source, hard);
  }

  private Fact fact(
    String id,
    EntityId subject,
    PredicateId predicate,
    EntityId object,
    Polarity polarity,
    FactSource source,
    boolean hard
  ) {
    return new Fact(
      id, subject, predicate, object, polarity,
      FactStatus.ACTIVE, source, null, hard
    );
  }

  private record TestContext(
    HistoryStore historyStore,
    KnowledgeGraphStore store,
    ReadOnlyKnowledgeGraphService graphService,
    PredicateCatalog predicates
  ) {
  }
}
