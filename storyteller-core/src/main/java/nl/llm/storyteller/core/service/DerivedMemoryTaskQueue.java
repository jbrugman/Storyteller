package nl.llm.storyteller.core.service;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class DerivedMemoryTaskQueue implements AutoCloseable {
  private final Object idleLock = new Object();
  private long pendingTasks;
  private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "derived-memory-worker");
    thread.setDaemon(true);
    return thread;
  });

  public void submit(Runnable task) {
    submitTracked(Executors.callable(task));
  }

  /**
   * Submits a task and waits for its result.
   * Must not be called from a task on this queue: the single worker would block waiting for itself.
   */
  public <T> T submitAndWait(Callable<T> task) throws InterruptedException, ExecutionException {
    return submitTracked(task).get();
  }

  /**
   * Waits for all pending work, including tasks submitted by running tasks.
   * Must not be called from a task on this queue: that task remains pending until this call returns.
   */
  public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
    long remaining = unit.toNanos(timeout);
    long started = System.nanoTime();
    synchronized (idleLock) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      while (pendingTasks != 0) {
        if (remaining <= 0) {
          return false;
        }
        TimeUnit.NANOSECONDS.timedWait(idleLock, remaining);
        remaining = unit.toNanos(timeout) - (System.nanoTime() - started);
      }
      return true;
    }
  }

  private <T> Future<T> submitTracked(Callable<T> task) {
    Objects.requireNonNull(task);
    synchronized (idleLock) {
      pendingTasks++;
    }
    try {
      return executor.submit(() -> {
        try {
          return task.call();
        } finally {
          taskFinished();
        }
      });
    } catch (RuntimeException | Error failure) {
      taskFinished();
      throw failure;
    }
  }

  private void taskFinished() {
    synchronized (idleLock) {
      if (--pendingTasks == 0) {
        idleLock.notifyAll();
      }
    }
  }

  @Override
  public void close() {
    executor.close();
  }
}
