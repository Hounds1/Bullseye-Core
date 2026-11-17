package io.bullseye.core.state.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.core.logging.BullseyeLogger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(5)
class AsyncStatePublisherTest {

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final ByteArrayOutputStream error = new ByteArrayOutputStream();
    private final BullseyeLogger logger =
            new BullseyeLogger(new PrintStream(output), new PrintStream(error));

    @Test
    void retriesWithoutAnotherPublishAndLogsFailureOnlyUntilRecovery() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch published = new CountDownLatch(1);
        try (AsyncStatePublisher publisher =
                publisher(
                        state -> {
                            if (attempts.incrementAndGet() <= 2) {
                                throw new IOException("disk temporarily unavailable");
                            }
                            published.countDown();
                        })) {
            publisher.publish(snapshot(1));
            await(published);
        }

        assertEquals(3, attempts.get());
        assertEquals(1, error.toString(StandardCharsets.UTF_8).lines().count());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("component restored"));
    }

    @Test
    void keepsOnlyLatestPendingSnapshotWhileWriterIsBusy() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch latestPublished = new CountDownLatch(1);
        List<Long> versions = new CopyOnWriteArrayList<>();
        try (AsyncStatePublisher publisher =
                publisher(
                        state -> {
                            versions.add(state.version());
                            if (state.version() == 1) {
                                firstStarted.countDown();
                                await(releaseFirst);
                            } else {
                                latestPublished.countDown();
                            }
                        })) {
            try {
                publisher.publish(snapshot(1));
                await(firstStarted);
                for (int version = 2; version <= 100; version++) {
                    publisher.publish(snapshot(version));
                }
            } finally {
                releaseFirst.countDown();
            }
            await(latestPublished);
        }

        assertEquals(List.of(1L, 100L), versions);
    }

    @Test
    void retriesLatestSnapshotInsteadOfRestoringFailedOlderWrite() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch failFirst = new CountDownLatch(1);
        CountDownLatch latestPublished = new CountDownLatch(1);
        List<Long> attempts = new CopyOnWriteArrayList<>();
        try (AsyncStatePublisher publisher =
                publisher(
                        state -> {
                            attempts.add(state.version());
                            if (state.version() == 1) {
                                firstStarted.countDown();
                                await(failFirst);
                                throw new IOException("first write failed");
                            }
                            latestPublished.countDown();
                        })) {
            try {
                publisher.publish(snapshot(1));
                await(firstStarted);
                publisher.publish(snapshot(2));
                publisher.publish(snapshot(3));
            } finally {
                failFirst.countDown();
            }
            await(latestPublished);
        }

        assertEquals(List.of(1L, 3L), attempts);
    }

    @Test
    void closeFlushesLatestPendingSnapshotAndTerminatesWorker() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        List<Long> versions = new CopyOnWriteArrayList<>();
        AsyncStatePublisher publisher =
                publisher(
                        state -> {
                            worker.set(Thread.currentThread());
                            versions.add(state.version());
                            if (state.version() == 1) {
                                firstStarted.countDown();
                                await(releaseFirst);
                            }
                        });
        try {
            publisher.publish(snapshot(1));
            await(firstStarted);
            publisher.publish(snapshot(2));
            Thread closer = new Thread(() -> publisher.close(Duration.ofSeconds(2)));
            closer.start();
            releaseFirst.countDown();
            closer.join(3_000);

            assertFalse(closer.isAlive());
            assertFalse(worker.get().isAlive());
            assertEquals(List.of(1L, 2L), versions);
        } finally {
            releaseFirst.countDown();
            publisher.close(Duration.ofMillis(100));
        }
    }

    @Test
    void closeInterruptsBlockedWriteAtDeadline() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch blockWrite = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AsyncStatePublisher publisher =
                publisher(
                        state -> {
                            worker.set(Thread.currentThread());
                            writeStarted.countDown();
                            try {
                                blockWrite.await();
                            } catch (InterruptedException failure) {
                                interrupted.countDown();
                                throw failure;
                            }
                        });
        try {
            publisher.publish(snapshot(1));
            await(writeStarted);
            publisher.close(Duration.ofMillis(50));
            await(interrupted);
            worker.get().join(1_000);

            assertFalse(worker.get().isAlive());
        } finally {
            blockWrite.countDown();
            publisher.close(Duration.ZERO);
        }
    }

    @Test
    void permanentFailureDoesNotPreventShutdown() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AsyncStatePublisher publisher =
                publisher(
                        state -> {
                            worker.set(Thread.currentThread());
                            attempted.countDown();
                            throw new IOException("disk unavailable");
                        });
        publisher.publish(snapshot(1));
        await(attempted);
        publisher.close(Duration.ofMillis(100));
        worker.get().join(1_000);

        assertFalse(worker.get().isAlive());
        assertEquals(1, error.toString(StandardCharsets.UTF_8).lines().count());
    }

    @Test
    void rejectsPublicationAfterClose() {
        AtomicInteger writes = new AtomicInteger();
        AsyncStatePublisher publisher = publisher(state -> writes.incrementAndGet());
        publisher.close();
        publisher.publish(snapshot(1));

        assertEquals(0, writes.get());
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("State publisher is closed"));
    }

    private AsyncStatePublisher publisher(StatePublisher delegate) {
        return new AsyncStatePublisher(delegate, logger, Duration.ofMillis(20));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Timed out waiting for publisher");
    }

    private static DiagnosticSnapshot snapshot(long version) {
        return new DiagnosticSnapshot(
                "host",
                "application",
                Severity.NORMAL,
                ResourceType.UNKNOWN,
                ResourceState.NORMAL,
                version * 1_000,
                version);
    }
}
