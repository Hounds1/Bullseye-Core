package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.common.workload.WorkloadIdentity;
import io.bullseye.core.diagnostic.rule.DiagnosticRule;
import io.bullseye.core.linux.cgroup.CgroupMemorySnapshot;
import io.bullseye.core.linux.cgroup.CgroupMonitor;
import io.bullseye.core.metric.InMemoryRollingMetricWindow;
import io.bullseye.core.state.InMemoryDiagnosticStateRepository;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class DiagnosticAttributionStateTest {

    private static final long GIB = 1_024L * 1_024 * 1_024;

    @Test
    void attributesOnHighEntryButNotRepeatedHighEvaluation() {
        AtomicInteger collections = new AtomicInteger();
        AtomicReference<List<CgroupMemorySnapshot>> snapshots =
                new AtomicReference<>(List.of(memorySnapshot("order-api.service", 7, 35)));
        MemoryPressureAttributor attributor =
                new MemoryPressureAttributor(
                        () -> {
                            collections.incrementAndGet();
                            return new CgroupMonitor.CollectionResult(
                                    CgroupMonitor.Status.AVAILABLE, snapshots.get(), Map.of());
                        },
                        5,
                        1.2);
        InMemoryRollingMetricWindow window =
                new InMemoryRollingMetricWindow(Duration.ofMinutes(1), Duration.ofSeconds(5));
        appendHostMemory(window, 1_000);
        InMemoryDiagnosticStateRepository repository =
                new InMemoryDiagnosticStateRepository(
                        DiagnosticSnapshot.initial("host", "test", 0));
        MutableMemoryRule rule = new MutableMemoryRule();
        DiagnosticCoordinator coordinator =
                new DiagnosticCoordinator(
                        window,
                        new DiagnosticEngine(List.of(rule)),
                        repository,
                        () -> "event",
                        attributor);

        var first = coordinator.evaluate(1_000).orElseThrow();
        assertEquals("order-api.service", first.current().attribution().workload().name());
        assertEquals(1, collections.get());

        assertTrue(coordinator.evaluate(2_000).isEmpty());
        assertEquals(1, collections.get());
    }

    @Test
    void criticalMemoryReevaluatesAttribution() {
        AtomicInteger collections = new AtomicInteger();
        AtomicReference<List<CgroupMemorySnapshot>> snapshots =
                new AtomicReference<>(List.of(memorySnapshot("first.service", 7, 35)));
        MemoryPressureAttributor attributor =
                new MemoryPressureAttributor(
                        () -> {
                            collections.incrementAndGet();
                            return new CgroupMonitor.CollectionResult(
                                    CgroupMonitor.Status.AVAILABLE, snapshots.get(), Map.of());
                        },
                        5,
                        1.2);
        InMemoryRollingMetricWindow window =
                new InMemoryRollingMetricWindow(Duration.ofMinutes(1), Duration.ofSeconds(5));
        appendHostMemory(window, 1_000);
        InMemoryDiagnosticStateRepository repository =
                new InMemoryDiagnosticStateRepository(
                        DiagnosticSnapshot.initial("host", "test", 0));
        MutableMemoryRule rule = new MutableMemoryRule();
        DiagnosticCoordinator coordinator =
                new DiagnosticCoordinator(
                        window,
                        new DiagnosticEngine(List.of(rule)),
                        repository,
                        () -> "event",
                        attributor);
        coordinator.evaluate(1_000).orElseThrow();

        rule.severity = Severity.CRITICAL;
        snapshots.set(List.of(memorySnapshot("second.service", 8, 45)));
        var critical = coordinator.evaluate(2_000).orElseThrow();

        assertEquals("second.service", critical.current().attribution().workload().name());
        assertEquals(2, collections.get());

        assertTrue(coordinator.evaluate(3_000).isEmpty());
        assertEquals(3, collections.get());

        double previousScore = repository.current().attribution().score();
        snapshots.set(List.of(memorySnapshot("second.service", 8, 46)));
        assertTrue(coordinator.evaluate(4_000).isEmpty());
        assertTrue(repository.current().attribution().score() > previousScore);
        long version = repository.current().version();

        snapshots.set(List.of(memorySnapshot("third.service", 9, 48)));
        assertTrue(coordinator.evaluate(12_000).isEmpty());
        assertEquals(4, collections.get());
        assertEquals(version, repository.current().version());
        assertEquals("second.service", repository.current().attribution().workload().name());

        appendHostMemory(window, 13_000);
        assertEquals("third.service", coordinator.evaluate(13_000).orElseThrow()
                .current().attribution().workload().name());
        assertEquals(5, collections.get());
    }

    @Test
    void memoryHighTriggersAttributionEvenWhenAnotherResourceIsPrimary() {
        AtomicInteger collections = new AtomicInteger();
        MemoryPressureAttributor attributor =
                new MemoryPressureAttributor(
                        () -> {
                            collections.incrementAndGet();
                            return new CgroupMonitor.CollectionResult(
                                    CgroupMonitor.Status.AVAILABLE,
                                    List.of(memorySnapshot("order-api.service", 7, 35)),
                                    Map.of());
                        },
                        5,
                        1.2);
        InMemoryRollingMetricWindow window =
                new InMemoryRollingMetricWindow(Duration.ofMinutes(1), Duration.ofSeconds(5));
        appendHostMemory(window, 1_000);
        InMemoryDiagnosticStateRepository repository =
                new InMemoryDiagnosticStateRepository(
                        DiagnosticSnapshot.initial("host", "test", 0));
        DiagnosticCoordinator coordinator =
                new DiagnosticCoordinator(
                        window,
                        new DiagnosticEngine(
                                List.of(new MutableMemoryRule(), new CriticalCpuRule())),
                        repository,
                        () -> "event",
                        attributor);

        var transition = coordinator.evaluate(1_000).orElseThrow();

        assertEquals(ResourceType.HOST_CPU, transition.current().resource());
        assertEquals(1, collections.get());
    }

    private static void appendHostMemory(InMemoryRollingMetricWindow window, long timestamp) {
        window.append(new MetricSample(MetricType.HOST_MEMORY_TOTAL_BYTES, 16 * GIB, timestamp));
        window.append(new MetricSample(MetricType.HOST_MEMORY_USAGE, 95, timestamp));
        window.append(new MetricSample(MetricType.HOST_MEMORY_PSI_SOME_AVG10, 45, timestamp));
    }

    private static CgroupMemorySnapshot memorySnapshot(
            String name, double memoryGib, double pressure) {
        return new CgroupMemorySnapshot(
                WorkloadIdentity.fromCgroupPath("/system.slice/" + name),
                (long) (memoryGib * GIB),
                null,
                pressure,
                5,
                0,
                0,
                0,
                0,
                1_000);
    }

    private static final class MutableMemoryRule implements DiagnosticRule {
        private Severity severity = Severity.HIGH;

        @Override
        public ResourceType resource() {
            return ResourceType.HOST_MEMORY;
        }

        @Override
        public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
            ResourceState state =
                    severity == Severity.CRITICAL
                            ? ResourceState.SATURATED
                            : ResourceState.SATURATION_RISK;
            return Optional.of(
                    new DiagnosticDecision(
                            ResourceType.HOST_MEMORY,
                            state,
                            severity,
                            "Critical memory pressure detected.",
                            List.of(),
                            100));
        }
    }

    private static final class CriticalCpuRule implements DiagnosticRule {
        @Override
        public ResourceType resource() {
            return ResourceType.HOST_CPU;
        }

        @Override
        public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
            return Optional.of(
                    new DiagnosticDecision(
                            ResourceType.HOST_CPU,
                            ResourceState.SATURATED,
                            Severity.CRITICAL,
                            "Host resource capacity compromised.",
                            List.of(),
                            200));
        }
    }
}
