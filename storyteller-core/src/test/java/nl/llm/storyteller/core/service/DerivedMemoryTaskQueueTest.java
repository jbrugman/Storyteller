package nl.llm.storyteller.core.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DerivedMemoryTaskQueueTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Await idle includes follow-up work submitted from a task's finally block")
    void shouldWaitForFollowUpTasks(boolean blockingSubmission) throws Exception {
        DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch followUpStarted = new CountDownLatch(1);
        CountDownLatch releaseFollowUp = new CountDownLatch(1);
        CompletableFuture<Boolean> idle = new CompletableFuture<>();
        CompletableFuture<Void> submitted = new CompletableFuture<>();
        Runnable first = () -> {
            firstStarted.countDown();
            try {
                awaitRelease(releaseFirst);
            } finally {
                queue.submit(() -> {
                    followUpStarted.countDown();
                    awaitRelease(releaseFollowUp);
                });
            }
        };
        Thread submitter = Thread.ofPlatform().unstarted(() -> {
            try {
                if (blockingSubmission) {
                    queue.submitAndWait(() -> { first.run(); return null; });
                } else {
                    queue.submit(first);
                }
                submitted.complete(null);
            } catch (Exception failure) {
                submitted.completeExceptionally(failure);
            }
        });
        Thread waiter = Thread.ofPlatform().unstarted(() -> {
            try {
                idle.complete(queue.awaitIdle(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                idle.completeExceptionally(failure);
            }
        });
        try {
            submitter.start();
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            waiter.start();
            // Ensure awaitIdle is already waiting before the task schedules its successor.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (waiter.getState() != Thread.State.TIMED_WAITING && waiter.isAlive()
                && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(Thread.State.TIMED_WAITING, waiter.getState());
            releaseFirst.countDown();
            assertTrue(followUpStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> idle.get(100, TimeUnit.MILLISECONDS));
            releaseFollowUp.countDown();
            assertTrue(idle.get(5, TimeUnit.SECONDS));
            submitted.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            releaseFollowUp.countDown();
            queue.close();
            submitter.join(5000);
            waiter.join(5000);
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for task release");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    @DisplayName("Await idle times out while work is blocked and succeeds once it completes")
    void shouldTimeOutWhileWorkIsPending() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue()) {
            try {
                queue.submit(() -> awaitRelease(release));
                assertFalse(queue.awaitIdle(20, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertTrue(queue.awaitIdle(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("Failed background and blocking tasks still allow the queue to become idle")
    void shouldBecomeIdleAfterTaskFailures() throws Exception {
        try (DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue()) {
            queue.submit(() -> { throw new IllegalStateException("Background task failed"); });
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> queue.submitAndWait(() -> { throw new IllegalArgumentException("Callable failed"); }));
            assertEquals(IllegalArgumentException.class, failure.getCause().getClass());
            assertTrue(queue.awaitIdle(5, TimeUnit.SECONDS));
            assertEquals("result", queue.submitAndWait(() -> "result"));
        }
    }

    @Test
    @DisplayName("Rejected submissions do not leave the closed queue permanently busy")
    void shouldRemainIdleAfterRejectedSubmissions() throws Exception {
        DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue();
        queue.close();
        assertThrows(RejectedExecutionException.class, () -> queue.submit(() -> { }));
        assertThrows(RejectedExecutionException.class, () -> queue.submitAndWait(() -> null));
        assertTrue(queue.awaitIdle(0, TimeUnit.NANOSECONDS));
    }

    @Test
    @DisplayName("Await idle honors interruption even when the queue is already idle")
    void shouldHonorInterruption() {
        try (DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedException.class, () -> queue.awaitIdle(5, TimeUnit.SECONDS));
                assertFalse(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    @DisplayName("""
        Given a running derived-memory task and another queued task,
        When the first task has not completed,
        Then the queued task should not start until the first task finishes
        """)
    void shouldRunBackgroundTasksSequentially() throws Exception {
        DerivedMemoryTaskQueue queue = new DerivedMemoryTaskQueue();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        try {
            queue.submit(() -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });
            queue.submit(secondStarted::countDown);

            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            queue.close();
        }
    }
}
