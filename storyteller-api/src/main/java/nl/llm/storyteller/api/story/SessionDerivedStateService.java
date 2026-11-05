package nl.llm.storyteller.api.story;

public final class SessionDerivedStateService implements AutoCloseable {
  private final SessionMemoryService memoryService;
  private final SessionKnowledgeGraphService graphService;

  public SessionDerivedStateService(
    SessionMemoryService memoryService,
    SessionKnowledgeGraphService graphService
  ) {
    this.memoryService = memoryService;
    this.graphService = graphService;
  }

  public void requestUpdate(String sessionId, boolean graphEnabled) {
    memoryService.requestUpdate(sessionId);
    graphService.requestUpdate(sessionId, graphEnabled);
  }

  @Override
  public void close() {
    memoryService.close();
    graphService.close();
  }
}
