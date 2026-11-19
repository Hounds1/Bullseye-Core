package io.bullseye.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ObservationStatus;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.diagnostic.DiagnosticCoordinator;
import io.bullseye.core.diagnostic.DiagnosticEngine;
import io.bullseye.core.diagnostic.rule.CpuPressureRule;
import io.bullseye.core.logging.BullseyeLogger;
import io.bullseye.core.metric.HostMetricNormalizer;
import io.bullseye.core.metric.InMemoryRollingMetricWindow;
import io.bullseye.core.metric.MetricCollector;
import io.bullseye.core.state.InMemoryDiagnosticStateRepository;
import io.bullseye.core.state.publish.AsyncStatePublisher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

class BullseyeRuntimeTest {

    @Test
    void publishesFreshTelemetryWithoutRepeatingStateTransitionLogs() throws Exception {
        try (Harness harness = new Harness()) {
            harness.collector.observe(1_000, 20);
            harness.runtime.start();
            DiagnosticSnapshot first = harness.awaitObserved(1_000, 20);

            harness.collector.observe(1_500, 30);
            DiagnosticSnapshot second = harness.awaitObserved(1_500, 30);

            assertEquals(Severity.NORMAL, second.severity());
            assertEquals(ObservationStatus.FRESH, second.observationStatus());
            assertEquals(first.version(), second.version());
            assertEquals(first.since(), second.since());
            assertEquals(1_000, first.evaluatedAt());
            assertEquals(1_500, second.evaluatedAt());
            assertFalse(harness.output().contains(" previous="));
            assertTrue(harness.errors().isEmpty());

            assertTimeout(Duration.ofSeconds(3), harness.runtime::close);
            assertTrue(harness.output().contains("Bullseye diagnostic system offline."));
        }
    }

    @Test
    void publishesStaleOutageAndObservedRecoveryWithoutFalseNormalization() throws Exception {
        try (Harness harness = new Harness()) {
            harness.collector.observe(1_000, 95);
            harness.runtime.start();
            harness.awaitObserved(1_000, 95);

            harness.collector.observe(2_000, 96);
            DiagnosticSnapshot critical =
                    harness.await(snapshot -> snapshot.severity() == Severity.CRITICAL);

            harness.collector.observe(2_500, 97);
            harness.awaitObserved(2_500, 97);

            harness.collector.failAt(6_000);
            DiagnosticSnapshot stale =
                    harness.await(
                            snapshot -> snapshot.observationStatus() == ObservationStatus.STALE);

            assertEquals(6_000, stale.evaluatedAt());
            assertEquals(Severity.CRITICAL, stale.severity());
            assertEquals(critical.version(), stale.version());
            assertEquals(critical.since(), stale.since());
            assertEquals(
                    2_500L,
                    stale.observations().get(ResourceType.HOST_CPU).lastSampleAt().longValue());
            assertTrue(stale.observations().get(ResourceType.HOST_CPU).metrics().isEmpty());

            harness.collector.failAt(6_500);
            harness.await(snapshot -> snapshot.evaluatedAt() == 6_500);
            assertEquals(1, harness.errors().lines()
                    .filter(line -> line.contains("component=collector-runtime-test")).count());
            assertFalse(harness.output().contains("Telemetry component restored."));

            harness.collector.observe(7_000, 20);
            DiagnosticSnapshot fresh = harness.awaitObserved(7_000, 20);
            assertEquals(ObservationStatus.FRESH, fresh.observationStatus());
            assertEquals(Severity.CRITICAL, fresh.severity());
            assertEquals(critical.version(), fresh.version());

            harness.collector.observe(8_000, 20);
            DiagnosticSnapshot recovered =
                    harness.await(
                            snapshot ->
                                    snapshot.evaluatedAt() == 8_000
                                            && snapshot.severity() == Severity.NORMAL);

            assertEquals(ObservationStatus.FRESH, recovered.observationStatus());
            assertEquals(critical.version() + 1, recovered.version());
            assertEquals(8_000, recovered.since());

            harness.collector.failAt(9_000);
            harness.await(snapshot -> snapshot.evaluatedAt() == 9_000);
            harness.collector.failAt(9_500);
            harness.await(snapshot -> snapshot.evaluatedAt() == 9_500);
            assertTimeout(Duration.ofSeconds(3), harness.runtime::close);
            assertEquals(
                    2,
                    harness.output().lines().filter(line -> line.contains(" previous=")).count());
            assertEquals(2, harness.errors().lines()
                    .filter(line -> line.contains("component=collector-runtime-test")).count());
            assertEquals(1, harness.output().lines()
                    .filter(line -> line.contains(
                            "Telemetry component restored. component=collector-runtime-test"))
                    .count());
        }
    }

    @Test
    void failedCollectorDoesNotBlockOtherCollectionDiagnosticsOrPublication() throws Exception {
        MetricCollector failingCollector = new MetricCollector() {
            @Override
            public String name() {
                return "failed-sibling";
            }

            @Override
            public Collection<MetricSample> collect() throws IOException {
                throw new IOException("Independent collector unavailable");
            }
        };

        try (Harness harness = new Harness(failingCollector)) {
            harness.collector.observe(1_000, 95);
            harness.runtime.start();
            harness.awaitObserved(1_000, 95);

            harness.collector.observe(2_000, 96);
            DiagnosticSnapshot critical = harness.await(snapshot ->
                    snapshot.evaluatedAt() == 2_000 && snapshot.severity() == Severity.CRITICAL);

            assertEquals(ObservationStatus.FRESH, critical.observationStatus());
            assertEquals(2_000L,
                    critical.observations().get(ResourceType.HOST_CPU).lastSampleAt().longValue());
            assertEquals(1, harness.errors().lines()
                    .filter(line -> line.contains("component=collector-failed-sibling")).count());
            assertFalse(harness.errors().contains("component=collector-runtime-test"));
        }
    }

    @Test
    void commitsSamplesOnlyAfterAllCollectorsFinishTheRound() throws Exception {
        MetricSample previousMemory = new MetricSample(MetricType.HOST_MEMORY_USAGE, 40, 1_000);
        MetricSample nextMemory = new MetricSample(MetricType.HOST_MEMORY_USAGE, 50, 2_000);
        MetricSample previousPsi = new MetricSample(MetricType.HOST_CPU_PSI_SOME_AVG10, 1, 1_000);
        MetricSample nextPsi = new MetricSample(MetricType.HOST_CPU_PSI_SOME_AVG10, 2, 2_000);
        CountDownLatch psiCollecting = new CountDownLatch(1);
        CountDownLatch finishPsi = new CountDownLatch(1);
        MetricCollector memoryCollector = new MetricCollector() {
            @Override
            public String name() {
                return "memory-test";
            }

            @Override
            public Collection<MetricSample> collect() {
                return List.of(nextMemory);
            }
        };
        MetricCollector psiCollector = new MetricCollector() {
            @Override
            public String name() {
                return "psi-test";
            }

            @Override
            public Collection<MetricSample> collect() throws Exception {
                psiCollecting.countDown();
                if (!finishPsi.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to complete the collection round");
                }
                return List.of(nextPsi);
            }
        };

        try (Harness harness = new Harness(memoryCollector, psiCollector)) {
            harness.window.append(previousMemory);
            harness.window.append(previousPsi);
            harness.collector.observe(2_000, 25);
            harness.runtime.start();
            try {
                assertTrue(psiCollecting.await(3, TimeUnit.SECONDS));
                assertEquals(List.of(previousMemory), harness.window.get(previousMemory.type()));
                assertEquals(List.of(previousPsi), harness.window.get(previousPsi.type()));
            } finally {
                finishPsi.countDown();
            }

            DiagnosticSnapshot published = harness.awaitObserved(2_000, 25);
            assertEquals(nextMemory, harness.window.get(nextMemory.type()).getLast());
            assertEquals(nextPsi, harness.window.get(nextPsi.type()).getLast());
            assertTrue(published.observations().get(ResourceType.HOST_CPU).metrics().stream()
                    .anyMatch(metric -> metric.metric().equals(nextPsi.type().name())
                            && metric.value() == nextPsi.value()));
            assertTrue(harness.errors().isEmpty());
        }
    }

    private static final class Harness implements AutoCloseable {

        private static final Duration FRESHNESS = Duration.ofSeconds(2);
        private static final Duration INTERVAL = Duration.ofMillis(20);
        private static final Duration WINDOW_DURATION = Duration.ofMinutes(1);
        private final MutableClock clock = new MutableClock();
        private final ControlledCollector collector = new ControlledCollector(clock);
        private final BlockingQueue<DiagnosticSnapshot> published = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
        private final InMemoryRollingMetricWindow window =
                new InMemoryRollingMetricWindow(WINDOW_DURATION, INTERVAL);
        private final BullseyeRuntime runtime;

        private Harness(MetricCollector... precedingCollectors) {
            BullseyeLogger logger =
                    new BullseyeLogger(
                            new PrintStream(output, true, StandardCharsets.UTF_8),
                            new PrintStream(errors, true, StandardCharsets.UTF_8));
            InMemoryDiagnosticStateRepository repository =
                    new InMemoryDiagnosticStateRepository(
                            DiagnosticSnapshot.initial("test-host", "runtime-test", 0));
            BullseyeConfiguration.CpuDiagnostics configuration =
                    new BullseyeConfiguration.CpuDiagnostics(
                            70,
                            Duration.ofSeconds(1),
                            80,
                            Duration.ofSeconds(1),
                            0,
                            90,
                            Duration.ofSeconds(1),
                            65,
                            Duration.ofSeconds(1));
            DiagnosticCoordinator coordinator =
                    new DiagnosticCoordinator(
                            window,
                            new DiagnosticEngine(
                                    List.of(new CpuPressureRule(configuration, FRESHNESS))),
                            repository,
                            () -> "test-event",
                            null,
                            FRESHNESS);
            AsyncStatePublisher publisher = new AsyncStatePublisher(published::add, logger);
            List<MetricCollector> collectors = new ArrayList<>(List.of(precedingCollectors));
            collectors.add(collector);
            runtime =
                    new BullseyeRuntime(
                            collectors,
                            new HostMetricNormalizer(),
                            window,
                            coordinator,
                            repository,
                            publisher,
                            logger,
                            clock,
                            INTERVAL,
                            WINDOW_DURATION);
        }

        private DiagnosticSnapshot awaitObserved(long timestamp, double usage) throws Exception {
            return await(
                    snapshot ->
                            snapshot.evaluatedAt() == timestamp
                                    && snapshot.observationStatus() == ObservationStatus.FRESH
                                    && snapshot.observations()
                                            .get(ResourceType.HOST_CPU)
                                            .metrics()
                                            .stream()
                                            .anyMatch(
                                                    metric ->
                                                            metric.metric().equals("HOST_CPU_USAGE")
                                                                    && metric.value() == usage));
        }

        private DiagnosticSnapshot await(Predicate<DiagnosticSnapshot> condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                DiagnosticSnapshot snapshot = published.poll(remaining, TimeUnit.NANOSECONDS);
                if (snapshot == null) {
                    break;
                }
                if (condition.test(snapshot)) {
                    return snapshot;
                }
            }
            return fail("Expected runtime snapshot was not published. Errors: " + errors());
        }

        private String output() {
            return output.toString(StandardCharsets.UTF_8);
        }

        private String errors() {
            return errors.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            runtime.close();
        }
    }

    private static final class ControlledCollector implements MetricCollector {

        private final MutableClock clock;
        private final AtomicReference<Step> next = new AtomicReference<>(new Step(0, 0, false));

        private ControlledCollector(MutableClock clock) {
            this.clock = clock;
        }

        private void observe(long timestamp, double usage) {
            next.set(new Step(timestamp, usage, false));
        }

        private void failAt(long timestamp) {
            next.set(new Step(timestamp, 0, true));
        }

        @Override
        public String name() {
            return "runtime-test";
        }

        @Override
        public Collection<MetricSample> collect() throws IOException {
            Step step = next.get();
            clock.now.set(step.timestamp());
            if (step.failing()) {
                throw new IOException("Simulated collection outage");
            }
            return List.of(
                    new MetricSample(MetricType.HOST_CPU_USAGE, step.usage(), step.timestamp()));
        }

        private record Step(long timestamp, double usage, boolean failing) {}
    }

    private static final class MutableClock extends Clock {

        private final AtomicLong now = new AtomicLong();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported by this test clock");
            }
            return this;
        }

        @Override
        public long millis() {
            return now.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }
    }
}
