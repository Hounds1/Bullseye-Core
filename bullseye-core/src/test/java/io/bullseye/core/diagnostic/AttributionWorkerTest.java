package io.bullseye.core.diagnostic;

import static io.bullseye.common.diagnostic.ResourceType.DISK_IO;
import static io.bullseye.common.diagnostic.ResourceType.HOST_CPU;
import static io.bullseye.common.diagnostic.ResourceType.HOST_MEMORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.logging.BullseyeLogger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Timeout(10)
class AttributionWorkerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private final BullseyeLogger logger = new BullseyeLogger(
            new PrintStream(output), new PrintStream(errors));

    @Test
    void initializesBeforeRunningCollectionsAndKeepsLatestRequestInResourceQueueOrder()
            throws InterruptedException {
        List<String> calls = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (AttributionWorker worker = new AttributionWorker(CLOCK, logger)) {
            worker.start(() -> calls.add("initialize"));
            assertTrue(request(worker, HOST_CPU, () -> {
                calls.add("first CPU");
                firstStarted.countDown();
                waitForRelease(releaseFirst);
                return observation(1_000);
            }, false));
            try {
                await(firstStarted);
                assertFalse(request(worker, HOST_CPU, () -> observation(1_001), false));
                assertTrue(request(worker, HOST_MEMORY, () -> {
                    calls.add("superseded memory");
                    return observation(1_001);
                }, false));
                for (int index = 0; index < 20; index++) {
                    assertTrue(request(worker, HOST_CPU, () -> {
                        calls.add("superseded CPU");
                        return observation(1_001);
                    }, true));
                }
                assertTrue(request(worker, DISK_IO, () -> {
                    calls.add("I/O");
                    return observation(1_003);
                }, false));
                assertTrue(request(worker, HOST_CPU, () -> {
                    calls.add("latest CPU");
                    return observation(1_002);
                }, true));
                assertTrue(request(worker, HOST_MEMORY, () -> {
                    calls.add("latest memory");
                    return observation(1_002);
                }, true));
            } finally {
                releaseFirst.countDown();
            }

            awaitResult(worker, DISK_IO);
            assertEquals(
                    List.of("initialize", "first CPU", "latest memory", "latest CPU", "I/O"),
                    calls);
            assertEquals(observation(1_002), worker.take(HOST_CPU, 1_003).attribution());
            assertEquals(observation(1_002), worker.take(HOST_MEMORY, 1_003).attribution());
        }
    }

    @Test
    void invalidationDropsPendingAndRunningResultsAndResetsTheNextCollection()
            throws InterruptedException {
        List<String> calls = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);

        try (AttributionWorker worker = new AttributionWorker(CLOCK, logger)) {
            worker.start(() -> {});
            try {
                assertTrue(request(worker, HOST_CPU, () -> {
                    calls.add("old collection");
                    firstStarted.countDown();
                    waitForRelease(releaseFirst);
                    return observation(1_000);
                }, false));
                await(firstStarted);
                assertTrue(request(worker, HOST_CPU, () -> {
                    calls.add("invalidated pending collection");
                    return observation(1_001);
                }, true));

                worker.invalidate(HOST_CPU);
                assertTrue(worker.request(
                        HOST_CPU,
                        () -> calls.add("reset"),
                        () -> {
                            calls.add("new collection");
                            secondStarted.countDown();
                            waitForRelease(releaseSecond);
                            return observation(1_002);
                        },
                        () -> true,
                        true));
                assertEquals(List.of("old collection"), calls);
                releaseFirst.countDown();
                await(secondStarted);

                assertNull(worker.take(HOST_CPU, Long.MAX_VALUE));
                assertEquals(List.of("old collection", "reset", "new collection"), calls);
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
            }

            assertEquals(
                    new AttributionWorker.Result(observation(1_002), true, 1_002),
                    awaitResult(worker, HOST_CPU));
        }
    }

    @Test
    void keepsFutureCompletionUntilEvaluationCanUseIt() throws InterruptedException {
        try (AttributionWorker worker = new AttributionWorker(CLOCK, logger)) {
            assertTrue(request(worker, HOST_CPU, () -> observation(2_000), false));
            assertTrue(request(worker, HOST_MEMORY, WorkloadAttribution::unresolved, false));
            worker.start(() -> {});
            awaitResult(worker, HOST_MEMORY);

            assertNull(worker.take(HOST_CPU, 1_999));
            assertFalse(request(worker, HOST_CPU, () -> observation(2_001), false));
            assertEquals(
                    new AttributionWorker.Result(observation(2_000), false, 2_000),
                    worker.take(HOST_CPU, 2_000));
            assertNull(worker.take(HOST_CPU, 2_000));
            assertTrue(request(worker, HOST_CPU, () -> observation(2_001), false));
        }
    }

    @Test
    void survivesCollectionFailureAndResetsOnlyTheFailedResourceOnRetry()
            throws InterruptedException {
        AtomicInteger cpuResets = new AtomicInteger();
        AtomicInteger memoryResets = new AtomicInteger();
        try (AttributionWorker worker = new AttributionWorker(CLOCK, logger)) {
            assertTrue(request(worker, HOST_CPU, () -> {
                throw new IllegalStateException("temporary collection failure");
            }, false));
            assertTrue(worker.request(
                    HOST_MEMORY,
                    memoryResets::incrementAndGet,
                    WorkloadAttribution::unresolved,
                    () -> false,
                    false));
            worker.start(() -> {});

            assertEquals(
                    new AttributionWorker.Result(WorkloadAttribution.unresolved(), false, 1_000),
                    awaitResult(worker, HOST_MEMORY));
            assertNull(worker.take(HOST_CPU, Long.MAX_VALUE));
            assertTrue(errors.toString(StandardCharsets.UTF_8)
                    .contains("component=cgroup-attribution-HOST_CPU"));

            assertTrue(worker.request(
                    HOST_CPU, cpuResets::incrementAndGet, () -> observation(1_100), () -> false, false));
            assertEquals(observation(1_100), awaitResult(worker, HOST_CPU).attribution());
            assertEquals(1, cpuResets.get());
            assertEquals(0, memoryResets.get());
            assertTrue(output.toString(StandardCharsets.UTF_8)
                    .contains("Telemetry component restored. component=cgroup-attribution-HOST_CPU"));
        }
    }

    @Test
    void closeInterruptsRunningWorkAndDiscardsPendingCollections() throws InterruptedException {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger memoryRequests = new AtomicInteger();

        try (AttributionWorker worker = new AttributionWorker(CLOCK, logger)) {
            worker.start(() -> {});
            assertTrue(request(worker, HOST_CPU, () -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException stopping) {
                    interrupted.countDown();
                    waitForRelease(release);
                    Thread.currentThread().interrupt();
                }
                return observation(1_000);
            }, false));
            try {
                await(running);
                assertTrue(request(worker, HOST_MEMORY, () -> {
                    memoryRequests.incrementAndGet();
                    return observation(1_001);
                }, false));

                worker.close();

                await(interrupted);
                assertFalse(request(worker, DISK_IO, () -> observation(1_002), false));
                assertNull(worker.take(HOST_CPU, Long.MAX_VALUE));
                assertNull(worker.take(HOST_MEMORY, Long.MAX_VALUE));
                assertEquals(0, memoryRequests.get());
            } finally {
                release.countDown();
            }
        }
    }

    private static boolean request(
            AttributionWorker worker,
            ResourceType resource,
            Supplier<WorkloadAttribution> collection,
            boolean queueWhileBusy) {
        return worker.request(resource, () -> {}, collection, () -> false, queueWhileBusy);
    }

    private static WorkloadAttribution observation(long collectedAt) {
        return WorkloadAttribution.unavailable(
                WorkloadAttribution.Status.NO_CANDIDATE, null, collectedAt);
    }

    private static AttributionWorker.Result awaitResult(
            AttributionWorker worker, ResourceType resource) throws InterruptedException {
        AttributionWorker.Result result;
        while ((result = worker.take(resource, Long.MAX_VALUE)) == null) {
            Thread.sleep(1);
        }
        return result;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Attribution worker did not reach the latch");
    }

    private static void waitForRelease(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
    }
}
