package nl.llm.storyteller.api.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import nl.llm.storyteller.core.JsonSupport;
import nl.llm.storyteller.core.graph.KnowledgeGraphValidator;
import nl.llm.storyteller.core.graph.model.KnowledgeGraphDocument;
import nl.llm.storyteller.core.graph.persistence.KnowledgeGraphJsonCodec;
import nl.llm.storyteller.db.SessionPrompts;
import nl.llm.storyteller.db.SessionSettings;
import nl.llm.storyteller.db.SessionSettingsRepository;

import java.util.LinkedHashMap;

import static nl.llm.storyteller.api.input.TextInputNormalizer.requiredMultiline;

public final class SessionSettingsService {
  private static final int MAX_PROMPT_LENGTH = 1_000_000;
  private final SessionSettingsRepository repository;
  private final KnowledgeGraphValidator graphValidator;
  private final boolean defaultStreaming;
  private final boolean defaultValidation;
  private final KnowledgeGraphJsonCodec graphCodec = new KnowledgeGraphJsonCodec();

  public SessionSettingsService(SessionSettingsRepository repository, KnowledgeGraphValidator graphValidator) {
    this(repository, graphValidator, false, true);
  }

  public SessionSettingsService(SessionSettingsRepository repository, KnowledgeGraphValidator graphValidator,
    boolean defaultStreaming, boolean defaultValidation) {
    this.defaultStreaming = defaultStreaming;
    this.defaultValidation = defaultValidation;
    this.repository = repository;
    this.graphValidator = graphValidator;
  }

  public SessionSettings load(String sessionId) {
    return repository.load(sessionId);
  }

  public void save(
    String sessionId,
    String systemPrompt,
    String fixedProtagonists,
    String rules,
    String knowledgeGraph,
    Double temperature
  ) {
    SessionPrompts prompts = new SessionPrompts(
      requiredMultiline(systemPrompt, "System prompt", MAX_PROMPT_LENGTH),
      requiredMultiline(fixedProtagonists, "Fixed protagonists", MAX_PROMPT_LENGTH),
      requiredMultiline(rules, "Rules", MAX_PROMPT_LENGTH)
    );
    FixedProtagonistsValidator.validate(prompts.fixedProtagonists());
    SessionSettings current = repository.load(sessionId);
    repository.save(sessionId, new SessionSettings(prompts, parseKnowledgeGraph(knowledgeGraph), temperature,
      current.streamingEnabled(), current.validationEnabled()));
  }

  public boolean streamingEnabled(SessionSettings settings) {
    return settings.streamingEnabled(defaultStreaming);
  }

  public boolean validationEnabled(SessionSettings settings) {
    return settings.validationEnabled(defaultValidation);
  }

  public void validateGeneration(Boolean streaming, Boolean validation) {
    if ((streaming != null ? streaming : defaultStreaming)
      && (validation != null ? validation : defaultValidation)) {
      throw new IllegalArgumentException("Streaming and validation cannot both be enabled.");
    }
  }

  public void save(String sessionId, String systemPrompt, String fixedProtagonists, String rules,
    String knowledgeGraph, Double temperature, Boolean streaming, Boolean validation) {
    validateGeneration(streaming, validation);
    SessionPrompts prompts = new SessionPrompts(
      requiredMultiline(systemPrompt, "System prompt", MAX_PROMPT_LENGTH),
      requiredMultiline(fixedProtagonists, "Fixed protagonists", MAX_PROMPT_LENGTH),
      requiredMultiline(rules, "Rules", MAX_PROMPT_LENGTH));
    FixedProtagonistsValidator.validate(prompts.fixedProtagonists());
    repository.save(sessionId, new SessionSettings(prompts, parseKnowledgeGraph(knowledgeGraph), temperature,
      streaming, validation));
  }

  public String formatKnowledgeGraph(SessionSettings settings) {
    try {
      return JsonSupport.OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
        .writeValueAsString(graphCodec.toJson(settings.knowledgeGraph()));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Could not render the knowledge graph.", ex);
    }
  }

  public void patchConfiguration(
    String sessionId,
    String systemPrompt,
    String fixedProtagonists,
    String rules,
    boolean updateTemperature,
    Double temperature
  ) {
    patchConfiguration(sessionId, systemPrompt, fixedProtagonists, rules, updateTemperature, temperature,
      false, null, false, null);
  }

  public synchronized void patchConfiguration(String sessionId, String systemPrompt, String fixedProtagonists,
    String rules, boolean updateTemperature, Double temperature, boolean updateStreaming, Boolean streaming,
    boolean updateValidation, Boolean validation) {
    SessionSettings current = repository.load(sessionId);
    Boolean effectiveStreaming = updateStreaming ? streaming : current.streamingEnabled();
    Boolean effectiveValidation = updateValidation ? validation : current.validationEnabled();
    validateGeneration(effectiveStreaming, effectiveValidation);
    var prompts = new LinkedHashMap<String, String>();
    if (systemPrompt != null) {
      prompts.put(SessionPrompts.SYSTEM_PROMPT_NAME, requiredMultiline(systemPrompt, "System prompt", MAX_PROMPT_LENGTH));
    }
    if (fixedProtagonists != null) {
      String normalized = requiredMultiline(fixedProtagonists, "Fixed protagonists", MAX_PROMPT_LENGTH);
      FixedProtagonistsValidator.validate(normalized);
      prompts.put(SessionPrompts.FIXED_PROTAGONISTS_NAME, normalized);
    }
    if (rules != null) {
      prompts.put(SessionPrompts.RULES_NAME, requiredMultiline(rules, "Rules", MAX_PROMPT_LENGTH));
    }
    if (updateTemperature && temperature != null
      && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)) {
      throw new IllegalArgumentException("temperature must be a number between 0 and 2, or null.");
    }
    repository.patchConfiguration(sessionId, prompts, updateTemperature, temperature,
      updateStreaming || updateValidation, effectiveStreaming, effectiveValidation);
  }

  private KnowledgeGraphDocument parseKnowledgeGraph(String knowledgeGraph) {
    String normalized = requiredMultiline(knowledgeGraph, "Knowledge graph", MAX_PROMPT_LENGTH);
    try {
      KnowledgeGraphDocument document = graphCodec.fromJson(normalized);
      graphValidator.validate(document);
      return document;
    } catch (JsonProcessingException | IllegalArgumentException ex) {
      throw new InvalidKnowledgeGraphException("Invalid knowledge graph: " + ex.getMessage(), ex);
    }
  }
}
