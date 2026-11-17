package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ObservationStatus;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.diagnostic.rule.CpuPressureRule;
import io.bullseye.core.diagnostic.rule.DiagnosticRule;
import io.bullseye.core.metric.InMemoryRollingMetricWindow;
import io.bullseye.core.state.InMemoryDiagnosticStateRepository;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

class DiagnosticFreshnessTest {

    private final InMemoryRollingMetricWindow window =
            new InMemoryRollingMetricWindow(Duration.ofMinutes(2), Duration.ofSeconds(5));
    private final InMemoryDiagnosticStateRepository repository =
            new InMemoryDiagnosticStateRepository(DiagnosticSnapshot.initial("host", "test", 0));
    private final AtomicInteger events = new AtomicInteger();

    @Test
    void refreshesNormalTelemetryWithoutAStateTransition() {
        DiagnosticCoordinator coordinator = coordinator(List.of(cpuRule()));
        assertEquals(ObservationStatus.INITIALIZING, repository.current().observationStatus());
        append(MetricType.HOST_CPU_USAGE, 10, 0);
        assertTrue(coordinator.evaluate(0).isEmpty());

        append(MetricType.HOST_CPU_USAGE, 20, 5_000);
        assertTrue(coordinator.evaluate(5_000).isEmpty());

        DiagnosticSnapshot current = repository.current();
        assertEquals(1, current.version());
        assertEquals(0, current.since());
        assertEquals(5_000, current.evaluatedAt());
        assertEquals(ObservationStatus.FRESH, current.observationStatus());
        var observation = current.observations().get(ResourceType.HOST_CPU);
        assertEquals(5_000L, observation.lastSampleAt());
        assertEquals(20, observation.metrics().getFirst().value());
        assertEquals(0, events.get());
    }

    @Test
    void refreshesEvidenceAtTheSameSeverityWithoutIncrementingVersion() {
        DiagnosticCoordinator coordinator = coordinator(List.of(cpuRule()));
        append(MetricType.HOST_CPU_USAGE, 95, 0);
        append(MetricType.HOST_CPU_USAGE, 96, 5_000);
        DiagnosticSnapshot first = coordinator.evaluate(5_000).orElseThrow().current();
        append(MetricType.HOST_CPU_USAGE, 97, 10_000);

        assertTrue(coordinator.evaluate(10_000).isEmpty());

        DiagnosticSnapshot current = repository.current();
        assertEquals(Severity.CRITICAL, current.severity());
        assertEquals(first.version(), current.version());
        assertEquals(first.since(), current.since());
        assertEquals(97, current.evidence().getFirst().value());
        assertEquals(10_000, current.evaluatedAt());
        assertEquals(1, events.get());
    }

    @Test
    void marksCollectionOutageStaleWithoutClearingCriticalDiagnosis() {
        DiagnosticCoordinator coordinator = coordinator(List.of(cpuRule()));
        append(MetricType.HOST_CPU_USAGE, 95, 0);
        append(MetricType.HOST_CPU_USAGE, 96, 5_000);
        DiagnosticSnapshot critical = coordinator.evaluate(5_000).orElseThrow().current();

        assertTrue(coordinator.evaluate(15_001).isEmpty());
        DiagnosticSnapshot stale = repository.current();
        assertEquals(ObservationStatus.STALE, stale.observationStatus());
        assertEquals(Severity.CRITICAL, stale.severity());
        assertEquals(critical.version(), stale.version());
        assertEquals(critical.since(), stale.since());
        var observation = stale.observations().get(ResourceType.HOST_CPU);
        assertEquals(5_000L, observation.lastSampleAt());
        assertTrue(observation.metrics().isEmpty());

        append(MetricType.HOST_CPU_USAGE, 20, 20_000);
        assertTrue(coordinator.evaluate(20_000).isEmpty());
        assertEquals(ObservationStatus.FRESH, repository.current().observationStatus());
        assertEquals(Severity.CRITICAL, repository.current().severity());
        assertEquals(20, repository.current().observations()
                .get(ResourceType.HOST_CPU).metrics().getFirst().value());

        for (long timestamp = 25_000; timestamp <= 50_000; timestamp += 5_000) {
            append(MetricType.HOST_CPU_USAGE, 20, timestamp);
        }
        assertEquals(Severity.NORMAL, coordinator.evaluate(50_000).orElseThrow()
                .current().severity());
    }

    @Test
    void missingInputTimesOutAndLaterBecomesFresh() {
        DiagnosticCoordinator coordinator = coordinator(List.of(cpuRule()));
        coordinator.evaluate(10_000);
        assertEquals(ObservationStatus.INITIALIZING, repository.current().observationStatus());

        coordinator.evaluate(10_001);
        assertEquals(ObservationStatus.STALE, repository.current().observationStatus());
        assertNull(repository.current().observations().get(ResourceType.HOST_CPU).lastSampleAt());

        append(MetricType.HOST_CPU_USAGE, 20, 15_000);
        coordinator.evaluate(15_000);
        assertEquals(ObservationStatus.FRESH, repository.current().observationStatus());
        assertEquals(1, repository.current().version());
    }

    @Test
    void freshCpuCannotHideMissingMemoryPsi() {
        DiagnosticCoordinator coordinator = coordinator(
                List.of(cpuRule(), retainedRule(ResourceType.HOST_MEMORY)));
        append(MetricType.HOST_CPU_USAGE, 20, 15_000);
        append(MetricType.HOST_MEMORY_USAGE, 30, 15_000);
        coordinator.evaluate(15_000);

        assertEquals(ObservationStatus.STALE, repository.current().observationStatus());
        assertEquals(ObservationStatus.FRESH, repository.current().observations()
                .get(ResourceType.HOST_CPU).status());
        assertEquals(ObservationStatus.STALE, repository.current().observations()
                .get(ResourceType.HOST_MEMORY).status());

        append(MetricType.HOST_MEMORY_PSI_SOME_AVG10, 0, 15_000);
        coordinator.evaluate(15_000);
        assertEquals(ObservationStatus.FRESH, repository.current().observationStatus());
    }

    @Test
    void previouslyAvailableOptionalPsiBecomesStaleWhenItStops() {
        DiagnosticCoordinator coordinator = coordinator(List.of(cpuRule()));
        append(MetricType.HOST_CPU_USAGE, 20, 0);
        append(MetricType.HOST_CPU_PSI_SOME_AVG10, 0, 0);
        coordinator.evaluate(0);

        append(MetricType.HOST_CPU_USAGE, 30, 15_000);
        coordinator.evaluate(15_000);
        var observation = repository.current().observations().get(ResourceType.HOST_CPU);
        assertEquals(ObservationStatus.STALE, observation.status());
        assertEquals(0L, observation.lastSampleAt());
        assertEquals(1, observation.metrics().size());
        assertEquals(30, observation.metrics().getFirst().value());
    }

    private DiagnosticCoordinator coordinator(List<DiagnosticRule> rules) {
        return new DiagnosticCoordinator(
                window, new DiagnosticEngine(rules), repository,
                () -> "event-" + events.incrementAndGet(), null, Duration.ofSeconds(10));
    }

    private static CpuPressureRule cpuRule() {
        return new CpuPressureRule(
                new BullseyeConfiguration.CpuDiagnostics(
                        70, Duration.ofSeconds(10), 80, Duration.ofSeconds(10), 0,
                        90, Duration.ofSeconds(5), 65, Duration.ofSeconds(30)),
                Duration.ofSeconds(10));
    }

    private static DiagnosticRule retainedRule(ResourceType resource) {
        return new DiagnosticRule() {
            @Override
            public ResourceType resource() {
                return resource;
            }

            @Override
            public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
                return Optional.empty();
            }
        };
    }

    private void append(MetricType type, double value, long timestamp) {
        window.append(new MetricSample(type, value, timestamp));
    }
}
