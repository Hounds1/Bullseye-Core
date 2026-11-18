package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.common.workload.WorkloadAttribution;
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
    void refreshesHighAtConfiguredBoundaryAndFindsReplacementWorkload() {
        RefreshScenario scenario = new RefreshScenario();
        scenario.observeAt(1_000);
        long version = scenario.repository.current().version();
        scenario.snapshots.set(List.of(memorySnapshot("replacement.service", 8, 40)));

        assertTrue(scenario.observeAt(5_999).isEmpty());
        assertEquals(1, scenario.collections.get());
        assertEquals("first.service", scenario.workload());

        var refreshed = scenario.observeAt(6_000).orElseThrow();
        assertEquals("replacement.service", refreshed.current().attribution().workload().name());
        assertEquals(version + 1, refreshed.current().version());
        assertEquals(2, scenario.collections.get());
    }

    @Test
    void retriesNoCandidateAfterRefreshIntervalWithoutSeverityChange() {
        RefreshScenario scenario = new RefreshScenario();
        scenario.snapshots.set(List.of());
        scenario.observeAt(1_000);
        assertEquals(WorkloadAttribution.Status.NO_CANDIDATE,
                scenario.repository.current().attribution().status());

        scenario.snapshots.set(List.of(memorySnapshot("new.service", 8, 40)));
        assertTrue(scenario.observeAt(2_000).isEmpty());
        assertEquals(1, scenario.collections.get());

        var refreshed = scenario.observeAt(6_000).orElseThrow();
        assertEquals(Severity.HIGH, refreshed.current().severity());
        assertEquals("new.service", scenario.workload());
        assertEquals(2, scenario.collections.get());
    }

    @Test
    void retriesUnavailableCollectionAtTheSameBoundedInterval() {
        RefreshScenario scenario = new RefreshScenario();
        scenario.status.set(CgroupMonitor.Status.ACCESS_DENIED);
        scenario.observeAt(1_000);
        assertEquals(WorkloadAttribution.Status.ACCESS_DENIED,
                scenario.repository.current().attribution().status());

        scenario.status.set(CgroupMonitor.Status.AVAILABLE);
        assertTrue(scenario.observeAt(5_999).isEmpty());
        assertEquals(1, scenario.collections.get());

        scenario.observeAt(6_000).orElseThrow();
        assertEquals("first.service", scenario.workload());
        assertEquals(2, scenario.collections.get());
    }

    @Test
    void staleInputsDeferRefreshWithoutPostponingTheNextFreshAttempt() {
        RefreshScenario scenario = new RefreshScenario();
        scenario.observeAt(1_000);
        scenario.snapshots.set(List.of(memorySnapshot("replacement.service", 8, 40)));

        assertTrue(scenario.coordinator.evaluate(6_000).isEmpty());
        assertEquals(1, scenario.collections.get());
        assertEquals("first.service", scenario.workload());

        scenario.observeAt(6_500).orElseThrow();
        assertEquals(2, scenario.collections.get());
        assertEquals("replacement.service", scenario.workload());
    }

    @Test
    void lostMemoryFullPsiDefersAttributionWithoutAdvancingTheRefreshTimer() {
        RefreshScenario scenario = new RefreshScenario();
        scenario.window.append(new MetricSample(
                MetricType.HOST_MEMORY_PSI_FULL_AVG10, 5, 1_000));
        scenario.observeAt(1_000);
        scenario.snapshots.set(List.of(memorySnapshot("replacement.service", 8, 40)));

        assertTrue(scenario.observeAt(6_000).isEmpty());
        assertEquals(1, scenario.collections.get());
        assertEquals("first.service", scenario.workload());

        scenario.window.append(new MetricSample(
                MetricType.HOST_MEMORY_PSI_FULL_AVG10, 5, 6_500));
        scenario.observeAt(6_500).orElseThrow();
        assertEquals(2, scenario.collections.get());
        assertEquals("replacement.service", scenario.workload());
    }

    @Test
    void skippedFirstHighAttemptRunsAsSoonAsInputsBecomeFresh() {
        RefreshScenario scenario = new RefreshScenario();
        scenario.coordinator.evaluate(1_000).orElseThrow();
        assertEquals(0, scenario.collections.get());

        scenario.observeAt(1_500).orElseThrow();

        assertEquals(1, scenario.collections.get());
        assertEquals("first.service", scenario.workload());
    }

    @Test
    void recoveryClearsRefreshTimerBeforeTheNextPressureEpisode() {
        RefreshScenario scenario = new RefreshScenario();
        scenario.observeAt(1_000);
        scenario.rule.severity = Severity.NORMAL;
        scenario.coordinator.evaluate(2_000).orElseThrow();
        assertEquals(WorkloadAttribution.Status.NOT_ATTEMPTED,
                scenario.repository.current().attribution().status());

        scenario.rule.severity = Severity.HIGH;
        scenario.coordinator.evaluate(4_000).orElseThrow();
        assertEquals(1, scenario.collections.get());

        scenario.observeAt(4_500).orElseThrow();
        assertEquals(2, scenario.collections.get());
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

    private static final class RefreshScenario {

        private final AtomicInteger collections = new AtomicInteger();
        private final AtomicReference<List<CgroupMemorySnapshot>> snapshots =
                new AtomicReference<>(List.of(memorySnapshot("first.service", 7, 35)));
        private final AtomicReference<CgroupMonitor.Status> status =
                new AtomicReference<>(CgroupMonitor.Status.AVAILABLE);
        private final InMemoryRollingMetricWindow window =
                new InMemoryRollingMetricWindow(Duration.ofMinutes(1), Duration.ofSeconds(1));
        private final InMemoryDiagnosticStateRepository repository =
                new InMemoryDiagnosticStateRepository(
                        DiagnosticSnapshot.initial("host", "test", 0));
        private final MutableMemoryRule rule = new MutableMemoryRule();
        private final DiagnosticCoordinator coordinator =
                new DiagnosticCoordinator(
                        window,
                        new DiagnosticEngine(List.of(rule)),
                        repository,
                        () -> "event",
                        new MemoryPressureAttributor(
                                () -> {
                                    collections.incrementAndGet();
                                    if (status.get() != CgroupMonitor.Status.AVAILABLE) {
                                        return CgroupMonitor.CollectionResult.unavailable(
                                                status.get());
                                    }
                                    return new CgroupMonitor.CollectionResult(
                                            status.get(), snapshots.get(), Map.of());
                                },
                                5,
                                1.2),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5));

        private Optional<DiagnosticTransition> observeAt(long timestamp) {
            appendHostMemory(window, timestamp);
            return coordinator.evaluate(timestamp);
        }

        private String workload() {
            return repository.current().attribution().workload().name();
        }
    }

    private static final class MutableMemoryRule implements DiagnosticRule {
        private Severity severity = Severity.HIGH;

        @Override
        public ResourceType resource() {
            return ResourceType.HOST_MEMORY;
        }

        @Override
        public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
            ResourceState state = switch (severity) {
                case NORMAL -> ResourceState.NORMAL;
                case ELEVATED -> ResourceState.PRESSURE;
                case HIGH -> ResourceState.SATURATION_RISK;
                case CRITICAL -> ResourceState.SATURATED;
            };
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
