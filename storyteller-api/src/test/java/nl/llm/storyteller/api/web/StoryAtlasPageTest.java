package nl.llm.storyteller.api.web;

import nl.llm.storyteller.api.story.SessionAtlas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryAtlasPageTest {
  @ParameterizedTest(name = "{0} selects {1}")
  @CsvSource({"CHARACTER,linde", "LOCATION,harbour", "ITEM,map", "SKILL,sailing"})
  @DisplayName("""
    Given an atlas with entries in every category and an obsolete selection,
    When another category is requested,
    Then only that category is listed and its first entry supplies the detail content
    """)
  void shouldSelectContentFromTheRequestedCategory(String category, String expectedId) {
    // Given
    var atlas = atlas();
    String previousSelection = category.equals("CHARACTER") ? "harbour" : "linde";

    // When
    var page = StoryAtlasPage.from(atlas, "", category, previousSelection);

    // Then
    assertEquals(category, page.type());
    assertTrue(page.entries().stream().allMatch(e -> e.type().equals(category)));
    assertEquals(expectedId, page.selected().id());
    assertEquals(category.equals("CHARACTER") ? 2 : 1, page.entries().size());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "invalid", "character"})
  @DisplayName("""
    Given a missing or unsupported category,
    When the atlas page is constructed,
    Then the category and detail selection fall back to characters
    """)
  void shouldDefaultUnsupportedCategoriesToCharacters(String category) {
    // Given
    var atlas = atlas();

    // When
    var page = StoryAtlasPage.from(atlas, null, category, "harbour");

    // Then
    assertEquals("CHARACTER", page.type());
    assertEquals("", page.query());
    assertEquals("linde", page.selected().id());
  }

  @ParameterizedTest(name = "query={0}, selected={1}")
  @MethodSource("searchCases")
  @DisplayName("""
    Given names and aliases in the selected category,
    When a search is applied,
    Then matching is case-insensitive, ignores surrounding whitespace, and excludes other categories
    """)
  void shouldSearchNamesAndAliases(String query, String expectedId, int expectedCount) {
    // Given
    var atlas = atlas();

    // When
    var page = StoryAtlasPage.from(atlas, query, "CHARACTER", "harbour");

    // Then
    assertEquals(expectedCount, page.entries().size());
    if (expectedId == null) assertNull(page.selected());
    else assertEquals(expectedId, page.selected().id());
  }

  static Stream<Arguments> searchCases() {
    return Stream.of(
      Arguments.of("lin", "linde", 1),
      Arguments.of("CARTOGRAPHER", "linde", 1),
      Arguments.of("  nOrA  ", "nora", 1),
      Arguments.of("Old harbour", null, 0),
      Arguments.of("missing", null, 0),
      Arguments.of("", "linde", 2),
      Arguments.of("   ", "linde", 2),
      Arguments.of(null, "linde", 2)
    );
  }

  @ParameterizedTest(name = "selected={0}, query={1}")
  @MethodSource("selectionCases")
  @DisplayName("""
    Given a requested entry identifier and filtered atlas entries,
    When the page selects detail content,
    Then it retains a matching selection or falls back within the visible results only
    """)
  void shouldSelectOnlyVisibleEntries(String selectedId, String query, String expectedId) {
    // Given
    var atlas = atlas();

    // When
    var page = StoryAtlasPage.from(atlas, query, "CHARACTER", selectedId);

    // Then
    if (expectedId == null) assertNull(page.selected());
    else assertEquals(expectedId, page.selected().id());
  }

  static Stream<Arguments> selectionCases() {
    return Stream.of(
      Arguments.of("nora", "", "nora"),
      Arguments.of("nora", "Cartographer", "linde"),
      Arguments.of("harbour", "", "linde"),
      Arguments.of("missing", "Nora", "nora"),
      Arguments.of(null, "", "linde"),
      Arguments.of("linde", "missing", null)
    );
  }

  @ParameterizedTest(name = "{0}: active={1}, other={2}")
  @CsvSource({"ACTIVE,1,0", "PROPOSED,0,1", "RETRACTED,0,1", "SUPERSEDED,0,1"})
  @DisplayName("""
    Given a selected entry with a relation of a particular status,
    When the page groups its facts,
    Then only active facts appear in the main list and all other statuses appear separately
    """)
  void shouldSeparateActiveFactsFromOtherStatuses(String status, int activeCount, int otherCount) {
    // Given
    var relation = new SessionAtlas.Relation("fact", "Linde trusts Nora", "linde", "nora",
      status, "Manual", null, false);
    var linde = new SessionAtlas.Entry("linde", "CHARACTER", "Linde", List.of(), "Manual",
      List.of(relation), List.of(), "");
    var atlas = new SessionAtlas("session", 2, List.of(linde), "");

    // When
    var page = StoryAtlasPage.from(atlas, "", "CHARACTER", "linde");

    // Then
    assertEquals(activeCount, page.activeRelations().size());
    assertEquals(otherCount, page.otherRelations().size());
    assertEquals(List.of(relation), activeCount == 1 ? page.activeRelations() : page.otherRelations());
  }

  @Test
  @DisplayName("""
    Given an empty atlas,
    When the page is constructed,
    Then there is no selection or fact content and the refresh link remains usable
    """)
  void shouldHandleAnEmptyAtlas() {
    // Given
    var atlas = new SessionAtlas("session", 0, List.of(), "");

    // When
    var page = StoryAtlasPage.from(atlas, null, null, null);

    // Then
    assertTrue(page.entries().isEmpty());
    assertNull(page.selected());
    assertTrue(page.activeRelations().isEmpty());
    assertTrue(page.otherRelations().isEmpty());
    assertEquals("/story/atlas?type=CHARACTER&q=&entity=", page.refreshUrl());
  }

  @ParameterizedTest(name = "query={0}, id={1}")
  @MethodSource("urlCases")
  @DisplayName("""
    Given a search term and entry identifier containing reserved or Unicode characters,
    When an entry or refresh link is generated,
    Then query values are encoded and the current selection and search are preserved
    """)
  void shouldEncodeEntryAndRefreshLinks(String query, String id, String expected) {
    // Given
    var entry = new SessionAtlas.Entry(id, "CHARACTER", query, List.of(), "Manual", List.of(), List.of(), "");
    var atlas = new SessionAtlas("session", 1, List.of(entry), "");
    var page = StoryAtlasPage.from(atlas, query, "CHARACTER", id);

    // When
    String url = page.entryUrl(id);
    String refresh = page.refreshUrl();

    // Then
    assertEquals(expected, url);
    assertEquals(expected, refresh);
  }

  static Stream<Arguments> urlCases() {
    return Stream.of(
      Arguments.of("Linde & Nora?", "profile:Linde/1",
        "/story/atlas?type=CHARACTER&q=Linde+%26+Nora%3F&entity=profile%3ALinde%2F1"),
      Arguments.of("Élise", "character.élise",
        "/story/atlas?type=CHARACTER&q=%C3%89lise&entity=character.%C3%A9lise")
    );
  }

  @Test
  @DisplayName("""
    Given a character page with an active search and a related location,
    When a link to that location is generated,
    Then it selects the location category and discards the character search
    """)
  void shouldLinkToTheRelatedCategoryWithoutOldSearchFilters() {
    // Given
    var page = StoryAtlasPage.from(atlas(), "Cartographer", "CHARACTER", "linde");

    // When
    String url = page.relatedUrl("harbour");
    String name = page.relatedName("harbour");

    // Then
    assertEquals("/story/atlas?type=LOCATION&entity=harbour", url);
    assertEquals("Old harbour", name);
    assertEquals("/story/atlas", page.relatedUrl("unknown"));
    assertEquals("unknown", page.relatedName("unknown"));
  }

  private static SessionAtlas atlas() {
    return new SessionAtlas("session", 4, List.of(
      entry("linde", "CHARACTER", "Linde", List.of("Cartographer")),
      entry("nora", "CHARACTER", "Nora", List.of()),
      entry("harbour", "LOCATION", "Old harbour", List.of()),
      entry("map", "ITEM", "Sea chart", List.of()),
      entry("sailing", "SKILL", "Sailing", List.of())
    ), "");
  }

  private static SessionAtlas.Entry entry(String id, String type, String name, List<String> aliases) {
    return new SessionAtlas.Entry(id, type, name, aliases, "Manual", List.of(), List.of(), "");
  }
}
