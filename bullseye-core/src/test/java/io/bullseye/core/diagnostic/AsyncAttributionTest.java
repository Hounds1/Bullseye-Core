package io.bullseye.core.diagnostic;

import static io.bullseye.common.workload.WorkloadAttribution.Status.IDENTIFIED;
import static io.bullseye.common.workload.WorkloadAttribution.Status.INSUFFICIENT_DATA;
import static io.bullseye.common.workload.WorkloadAttribution.Status.NOT_ATTEMPTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.diagnostic.rule.DiagnosticRule;
import io.bullseye.core.linux.cgroup.CgroupMonitor;
import io.bullseye.core.logging.BullseyeLogger;
import io.bullseye.core.metric.InMemoryRollingMetricWindow;
import io.bullseye.core.state.InMemoryDiagnosticStateRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Timeout(15)
class AsyncAttributionTest {

    private static final ResourceType CPU = ResourceType.HOST_CPU;
    private static final Path ROOT = Path.of("cgroup");
    private static final Path GROUP = ROOT.resolve("worker.service");

    @Test
    void diagnosesRecoveryAndReentryWhileCgroupReadsBlockAndDiscardsOldAttribution()
            throws InterruptedException {
        try (Scenario scenario = new Scenario()) {
            try (CpuRead firstBaseline = scenario.nextRead(0)) {
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> scenario.tick(1_000, 95));
                firstBaseline.awaitRead();
            }
            scenario.awaitAttribution(INSUFFICIENT_DATA);

            try (CpuRead oldPressure = scenario.nextRead(3_000_000);
                    CpuRead newBaseline = scenario.nextRead(6_000_000)) {
                scenario.tick(2_000, 95);
                oldPressure.awaitRead();

                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    scenario.tick(3_000, 20);
                    assertEquals(3_000, scenario.snapshot().evaluatedAt());
                    assertEquals(Severity.NORMAL, scenario.snapshot().diagnosis(CPU).severity());
                    assertEquals(NOT_ATTEMPTED, scenario.attribution().status());

                    scenario.tick(4_000, 95);
                    assertEquals(4_000, scenario.snapshot().evaluatedAt());
                    assertEquals(Severity.CRITICAL, scenario.snapshot().diagnosis(CPU).severity());
                    assertEquals(NOT_ATTEMPTED, scenario.attribution().status());
                });

                oldPressure.close();
                newBaseline.awaitRead();
                scenario.coordinator.evaluate(4_000);
                assertEquals(NOT_ATTEMPTED, scenario.attribution().status());
            }
            WorkloadAttribution baseline = scenario.awaitAttribution(INSUFFICIENT_DATA);
            assertEquals(4_000L, baseline.collectedAt());

            try (CpuRead freshPressure = scenario.nextRead(9_000_000)) {
                scenario.tick(5_000, 95);
                freshPressure.awaitRead();
            }
            WorkloadAttribution current = scenario.awaitAttribution(IDENTIFIED);
            assertEquals("worker.service", current.workload().name());
            assertEquals(5_000L, current.collectedAt());
            assertEquals(75, current.score(), 0.001);
        }
    }

    private static final class Scenario implements AutoCloseable {
        private final ManualClock clock = new ManualClock();
        private final InMemoryRollingMetricWindow window =
                new InMemoryRollingMetricWindow(Duration.ofMinutes(1), Duration.ofSeconds(1));
        private final InMemoryDiagnosticStateRepository repository =
                new InMemoryDiagnosticStateRepository(DiagnosticSnapshot.initial("host", "test", 0));
        private final ConcurrentLinkedQueue<CpuRead> reads = new ConcurrentLinkedQueue<>();
        private final DiagnosticCoordinator coordinator;

        Scenario() {
            var output = new PrintStream(OutputStream.nullOutputStream());
            var logger = new BullseyeLogger(output, output);
            var monitor = new CgroupMonitor(
                    new BullseyeConfiguration.Cgroup(
                            true, ROOT, Duration.ofMinutes(1), 8, 512, 5, 1.2),
                    new CgroupMonitor.FileAccess() {
                        @Override
                        public String read(Path path) throws IOException {
                            if (path.equals(ROOT.resolve("cgroup.controllers"))) {
                                return "cpu";
                            }
                            if (path.equals(GROUP.resolve("cpu.stat"))) {
                                CpuRead read = reads.poll();
                                assertNotNull(read, "Unexpected cgroup CPU read");
                                return read.counters();
                            }
                            throw new NoSuchFileException(path.toString());
                        }

                        @Override
                        public List<Path> directories(Path path) {
                            return path.equals(ROOT) ? List.of(GROUP) : List.of();
                        }
                    }, clock, logger);
            coordinator = new DiagnosticCoordinator(
                    window,
                    new DiagnosticEngine(List.of(new DiagnosticRule() {
                        @Override
                        public ResourceType resource() {
                            return CPU;
                        }

                        @Override
                        public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
                            double usage = context.metrics().get(MetricType.HOST_CPU_USAGE)
                                    .getLast().value();
                            return Optional.of(usage >= 90
                                    ? new DiagnosticDecision(CPU, ResourceState.SATURATED,
                                            Severity.CRITICAL, "CPU pressure detected.")
                                    : DiagnosticDecision.normal(CPU, "CPU pressure cleared."));
                        }
                    })),
                    repository,
                    () -> "event",
                    null,
                    new CpuPressureAttributor(monitor, 5, 1.2, Duration.ofSeconds(7)),
                    null,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    new AttributionWorker(clock, logger));
            coordinator.startAttribution(monitor::initialize);
        }

        CpuRead nextRead(long counter) {
            CpuRead read = new CpuRead(counter);
            reads.add(read);
            return read;
        }

        void tick(long evaluatedAt, double usage) {
            clock.now = evaluatedAt;
            window.appendAll(List.of(
                    new MetricSample(MetricType.HOST_CPU_USAGE, usage, evaluatedAt),
                    new MetricSample(MetricType.HOST_CPU_LOGICAL_COUNT, 4, evaluatedAt)));
            coordinator.evaluate(evaluatedAt);
        }

        DiagnosticSnapshot snapshot() {
            return repository.current();
        }

        WorkloadAttribution attribution() {
            return snapshot().diagnosis(CPU).attribution();
        }

        WorkloadAttribution awaitAttribution(WorkloadAttribution.Status expected)
                throws InterruptedException {
            long evaluatedAt = snapshot().evaluatedAt();
            long start = System.nanoTime();
            while (attribution().status() != expected
                    && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3)) {
                coordinator.evaluate(evaluatedAt);
                Thread.sleep(5);
            }
            WorkloadAttribution attribution = attribution();
            assertEquals(expected, attribution.status());
            return attribution;
        }

        @Override
        public void close() {
            coordinator.closeAttribution();
        }
    }

    private static final class CpuRead implements AutoCloseable {
        private final long counter;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        CpuRead(long counter) {
            this.counter = counter;
        }

        void awaitRead() throws InterruptedException {
            assertTrue(entered.await(3, TimeUnit.SECONDS), "Cgroup read did not start");
        }

        String counters() throws IOException {
            entered.countDown();
            try {
                assertTrue(released.await(5, TimeUnit.SECONDS), "Cgroup read was not released");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Cgroup read interrupted", failure);
            }
            return "usage_usec " + counter + "\nuser_usec " + counter + "\nsystem_usec 0\n";
        }

        @Override
        public void close() {
            released.countDown();
        }
    }

    private static final class ManualClock extends Clock {
        private volatile long now;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }
}
