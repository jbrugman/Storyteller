package nl.llm.storyteller.core.graph.turnbasedservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nl.llm.storyteller.core.JsonSupport;
import nl.llm.storyteller.core.graph.model.Entity;
import nl.llm.storyteller.core.graph.model.EntityType;
import nl.llm.storyteller.core.graph.model.FactSource;
import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;
import nl.llm.storyteller.core.model.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnBasedCharacterAdmissionTest {
  private static final String ID = "character.new";

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("fullNames")
  @DisplayName("""
    Given an explicit full-name introduction and mentions in three distinct story responses,
    When new-character evidence is checked,
    Then the generic full name is canonical and the actual first name is its alias without requiring a fact
    """)
  void shouldAdmitQualifiedNames(String first, String last) {
    // Given
    var candidate = candidate(first);
    var proof = evidence(first, last);
    var turns = responses(introduction(first, last), first + " returns.", last + " speaks.");

    // When
    var admitted = TurnBasedCharacterAdmission.apply(KnowledgeGraphDocument.empty(), candidate, proof, turns);

    // Then
    var entity = admitted.entities().get(ID);
    assertEquals(first + " " + last, entity.name());
    assertEquals(List.of(first), entity.aliases());
    assertEquals(FactSource.TURNBASED, entity.source());
    assertTrue(admitted.facts().isEmpty());
    assertEquals(first, candidate.entities().get(ID).name());
  }

  static Stream<Arguments> fullNames() {
    return Stream.of(Arguments.of("Thomas", "Vermeer"), Arguments.of("Élise", "Dupont"),
      Arguments.of("Jean-Luc", "van Dijk"), Arguments.of("Ana María", "de la Cruz"),
      Arguments.of("Sam", "O'Neill"));
  }

  @Test
  @DisplayName("""
    Given an introduction explicitly stating the first name and surname separately,
    When both names and three distinct response mentions are verified,
    Then the canonical name joins the known parts without requiring them to be adjacent in the quotation
    """)
  void shouldAcceptExplicitlyIntroducedSeparateNameParts() {
    // Given
    var proof = evidence("Élise", "Dupont");
    String quote = "My first name is Élise and my surname is Dupont.";
    ((ObjectNode) proof.path(ID).path("introduction")).put("quote", quote);

    // When
    var admitted = TurnBasedCharacterAdmission.apply(KnowledgeGraphDocument.empty(), candidate("Élise"), proof,
      responses(quote, "Élise returns.", "Dupont speaks."));

    // Then
    assertEquals("Élise Dupont", admitted.entities().get(ID).name());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidEvidence")
  @DisplayName("""
    Given incomplete names, fabricated evidence, or fewer than three distinct assistant responses,
    When new-character evidence is checked,
    Then the character is not admitted
    """)
  void shouldRejectInsufficientEvidence(String scenario, JsonNode proof, List<Message> turns) {
    // Given
    var candidate = candidate("Thomas");

    // When
    var admitted = TurnBasedCharacterAdmission.apply(KnowledgeGraphDocument.empty(), candidate, proof, turns);

    // Then
    assertFalse(admitted.entities().containsKey(ID), scenario);
  }

  static Stream<Arguments> invalidEvidence() {
    var turns = responses(introduction("Thomas", "Vermeer"), "Thomas returns.", "Vermeer speaks.");
    var duplicate = evidence("Thomas", "Vermeer");
    duplicate.path(ID).path("mentions").forEach(m -> ((ObjectNode) m).put("response", 1));
    var inventedQuote = evidence("Thomas", "Vermeer");
    ((ObjectNode) inventedQuote.path(ID).path("introduction")).put("quote", "I introduce myself as Thomas Vermeer.");
    var unknownName = evidence("Thomas", "Vermeer");
    ((ObjectNode) unknownName.path(ID).path("mentions").get(1)).put("name", "Theo");
    var missingFirst = evidence("", "Vermeer");
    var missingLast = evidence("Thomas", "");
    var missingProof = JsonSupport.OBJECT_MAPPER.createObjectNode();
    var missingMentions = evidence("Thomas", "Vermeer");
    ((ObjectNode) missingMentions.path(ID)).remove("mentions");
    return Stream.of(
      Arguments.of("first name only", missingLast, turns),
      Arguments.of("surname only", missingFirst, turns),
      Arguments.of("no evidence", missingProof, turns),
      Arguments.of("no response evidence", missingMentions, turns),
      Arguments.of("one response with many repetitions", evidence("Thomas", "Vermeer"),
        responses(introduction("Thomas", "Vermeer") + " Thomas Thomas Vermeer Vermeer.")),
      Arguments.of("only two responses", evidence("Thomas", "Vermeer"),
        responses(introduction("Thomas", "Vermeer"), "Thomas and Vermeer speak.")),
      Arguments.of("duplicate response indices", duplicate, turns),
      Arguments.of("fabricated introduction", inventedQuote, turns),
      Arguments.of("unsupported nickname", unknownName, turns),
      Arguments.of("claimed mentions absent from text", evidence("Thomas", "Vermeer"),
        responses(introduction("Thomas", "Vermeer"), "Nobody returns.", "Nobody speaks.")),
      Arguments.of("names embedded in other words", evidence("Ann", "May"),
        responses(introduction("Ann", "May"), "Anna returns.", "Maybe tomorrow.")),
      Arguments.of("user messages are not story responses", evidence("Thomas", "Vermeer"), List.of(
        new Message("assistant", introduction("Thomas", "Vermeer")),
        new Message("user", "Thomas returns."), new Message("user", "Vermeer speaks."))),
      Arguments.of("introduction only in user prompt", evidence("Thomas", "Vermeer"), List.of(
        new Message("user", introduction("Thomas", "Vermeer")),
        new Message("assistant", "Thomas returns."), new Message("assistant", "Thomas speaks."),
        new Message("assistant", "Vermeer leaves.")))
    );
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 4, Integer.MAX_VALUE, Integer.MIN_VALUE})
  @DisplayName("""
    Given an introduction citing a response outside the supplied story window,
    When the evidence is checked,
    Then the character is rejected without counting unavailable responses
    """)
  void shouldRejectInvalidResponseNumbers(int response) {
    // Given
    var proof = evidence("Thomas", "Vermeer");
    ((ObjectNode) proof.path(ID).path("introduction")).put("response", response);

    // When
    var admitted = TurnBasedCharacterAdmission.apply(KnowledgeGraphDocument.empty(), candidate("Thomas"), proof,
      responses(introduction("Thomas", "Vermeer"), "Thomas returns.", "Vermeer speaks."));

    // Then
    assertTrue(admitted.entities().isEmpty());
  }

  @Test
  @DisplayName("""
    Given name mentions in three non-consecutive story responses,
    When evidence cites those responses in the supplied window,
    Then the character qualifies without requiring consecutive mentions
    """)
  void shouldCountNonConsecutiveResponses() {
    // Given
    var proof = evidence("Thomas", "Vermeer");
    ((ObjectNode) proof.path(ID).path("mentions").get(1)).put("response", 3);
    ((ObjectNode) proof.path(ID).path("mentions").get(2)).put("response", 5);
    var turns = responses(introduction("Thomas", "Vermeer"), "Rain falls.", "Thomas returns.",
      "Night comes.", "Vermeer speaks.");

    // When
    var admitted = TurnBasedCharacterAdmission.apply(KnowledgeGraphDocument.empty(), candidate("Thomas"), proof, turns);

    // Then
    assertEquals("Thomas Vermeer", admitted.entities().get(ID).name());
  }

  @ParameterizedTest
  @ValueSource(strings = {"Thomas Jansen", "Mia Vermeer", "Thomas Vermeer"})
  @DisplayName("""
    Given another known character sharing a first name, surname, or complete identity,
    When admission evidence relies on that ambiguous name,
    Then the new character is not admitted
    """)
  void shouldRejectAmbiguousNames(String otherName) {
    // Given
    var current = new KnowledgeGraphDocument(1, 1, Map.of("character.other",
      new Entity(EntityType.CHARACTER, otherName, List.of(), FactSource.MANUAL)), List.of());

    // When
    var admitted = TurnBasedCharacterAdmission.apply(current, candidate("Thomas"), evidence("Thomas", "Vermeer"),
      responses(introduction("Thomas", "Vermeer"), "Thomas returns.", "Vermeer speaks."));

    // Then
    assertFalse(admitted.entities().containsKey(ID));
  }

  @Test
  @DisplayName("""
    Given two characters sharing a first name but distinct full names,
    When all three responses and their evidence use the new character's unambiguous full name,
    Then the new character can qualify without counting the shared first name
    """)
  void shouldAcceptUnambiguousFullNamesDespiteSharedFirstName() {
    // Given
    var current = new KnowledgeGraphDocument(1, 1, Map.of("character.other",
      new Entity(EntityType.CHARACTER, "Thomas Jansen", List.of("Thomas"))), List.of());
    var proof = evidence("Thomas", "Vermeer");
    proof.path(ID).path("mentions").forEach(m -> ((ObjectNode) m).put("name", "Thomas Vermeer"));

    // When
    var admitted = TurnBasedCharacterAdmission.apply(current, candidate("Thomas"), proof,
      responses(introduction("Thomas", "Vermeer"), "Thomas Vermeer returns.", "Thomas Vermeer speaks."));

    // Then
    assertEquals("Thomas Vermeer", admitted.entities().get(ID).name());
  }

  @ParameterizedTest
  @EnumSource(value = FactSource.class, names = {"MANUAL", "FIXED_PROTAGONIST", "TURNBASED"})
  @DisplayName("""
    Given a character already stored with a canonical name and aliases,
    When a later batch mentions only their first name without new admission evidence,
    Then the existing identity remains available and is not renamed or required to qualify again
    """)
  void shouldPreserveExistingCharacters(FactSource source) {
    // Given
    var known = new Entity(EntityType.CHARACTER, "Thomas Vermeer", List.of("Thomas"), source);
    var current = new KnowledgeGraphDocument(1, 2, Map.of(ID, known), List.of());

    // When
    var admitted = TurnBasedCharacterAdmission.apply(current, candidate("Thomas"),
      JsonSupport.OBJECT_MAPPER.createObjectNode(), responses("Thomas returns."));

    // Then
    assertEquals(known, admitted.entities().get(ID));
  }

  static ObjectNode evidence(String first, String last) {
    var root = JsonSupport.OBJECT_MAPPER.createObjectNode();
    var proof = root.putObject(ID);
    proof.put("firstName", first);
    proof.put("lastName", last);
    proof.putObject("introduction").put("response", 1).put("quote", introduction(first, last));
    var mentions = proof.putArray("mentions");
    mentions.addObject().put("response", 1).put("name", first + " " + last);
    mentions.addObject().put("response", 2).put("name", first);
    mentions.addObject().put("response", 3).put("name", last);
    return root;
  }

  private static String introduction(String first, String last) {
    return "Hello, my name is " + first + " " + last + ".";
  }

  private static List<Message> responses(String... responses) {
    List<Message> messages = new ArrayList<>();
    for (String response : responses) {
      messages.add(new Message("user", "Continue"));
      messages.add(new Message("assistant", response));
    }
    return messages;
  }

  private static KnowledgeGraphDocument candidate(String name) {
    return new KnowledgeGraphDocument(1, 0, Map.of(ID,
      new Entity(EntityType.CHARACTER, name, List.of(), FactSource.TURNBASED)), List.of());
  }
}
