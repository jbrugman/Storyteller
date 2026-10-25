package nl.llm.storyteller.core.service;

import nl.llm.storyteller.core.model.HistoryState;
import nl.llm.storyteller.core.model.Message;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

abstract class DerivedMemoryManager {
  protected final StoryHistory historyStore;
  protected final ChatClient client;
  protected final nl.llm.storyteller.core.config.AppConfig config;
  protected final PromptLoader promptResourceLoader;
  protected final PromptTemplateService promptTemplateService;

  private final DerivedMemoryTaskQueue taskQueue;
  private final TextMemory memory;
  private final boolean ownsTaskQueue;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean updateRequested = new AtomicBoolean(false);
  private final Object lock = new Object();

  DerivedMemoryManager(
    StoryHistory historyStore,
    ChatClient client,
    nl.llm.storyteller.core.config.AppConfig config,
    PromptLoader promptResourceLoader,
    PromptTemplateService promptTemplateService,
    TextMemory memory,
    DerivedMemoryTaskQueue taskQueue,
    boolean ownsTaskQueue
  ) {
    this.historyStore = historyStore;
    this.client = client;
    this.config = config;
    this.promptResourceLoader = promptResourceLoader;
    this.promptTemplateService = promptTemplateService;
    this.memory = memory;
    this.taskQueue = taskQueue;
    this.ownsTaskQueue = ownsTaskQueue;
  }

  public final void shutdown() {
    if (ownsTaskQueue) {
      taskQueue.close();
    }
  }

  protected final void triggerUpdateIfNeeded() {
    if (isDisabled()) {
      return;
    }

    synchronized (lock) {
      if (running.get()) {
        updateRequested.set(true);
        return;
      }

      DerivedMemoryJob job = prepareJob();
      if (job == null) {
        return;
      }

      running.set(true);
      try {
        taskQueue.submit(() -> runJob(job));
      } catch (RejectedExecutionException failure) {
        running.set(false);
        throw failure;
      }
    }
  }

  protected final String loadMemory() {
    if (isDisabled()) {
      return "";
    }
    return memory.load();
  }

  protected final int safeCursor(int cursor, int size) {
    return Math.clamp(cursor, 0, size);
  }

  protected final String formatHistory(List<Message> pendingMessages) {
    StringBuilder formattedHistory = new StringBuilder();
    for (Message message : pendingMessages) {
      if (!formattedHistory.isEmpty()) {
        formattedHistory.append('\n');
      }
      formattedHistory.append(message.role().toUpperCase()).append(": ").append(message.content());
    }
    return formattedHistory.toString();
  }

  private void runJob(DerivedMemoryJob job) {
    try {
      List<Message> prompt = buildUpdateMessages(job.existingContent(), job.pendingMessages());
      String updatedContent = client.chat(
        purpose(),
        prompt,
        config.summaryOptions(),
        config.summaryRequestTimeoutSeconds()
      );

      synchronized (lock) {
        HistoryState currentState = historyStore.load();
        if (safeCursor(currentCursor(currentState), currentState.messages().size()) != job.cursor()) {
          return;
        }

        memory.save(updatedContent);
        markUpdated(job.cutoffIndex());
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException _) {
      ignoreFailure();
    } finally {
      running.set(false);
      if (updateRequested.getAndSet(false)) {
        triggerUpdateIfNeeded();
      }
    }
  }

  protected abstract boolean isDisabled();

  protected abstract String purpose();

  protected abstract DerivedMemoryJob prepareJob();

  protected abstract List<Message> buildUpdateMessages(String existingContent, List<Message> pendingMessages);

  protected abstract int currentCursor(HistoryState state);

  protected abstract void markUpdated(int cutoffIndex);

  protected abstract void ignoreFailure();

  protected record DerivedMemoryJob(int cursor, int cutoffIndex, String existingContent,
                                    List<Message> pendingMessages) {
  }
}
