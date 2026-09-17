package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.core.diagnostic.rule.DiagnosticRule;
import io.bullseye.core.metric.InMemoryRollingMetricWindow;
import io.bullseye.core.state.InMemoryDiagnosticStateRepository;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

class SeverityAggregationTest {

    @Test
    void memoryHighOutranksCpuElevated() {
        MutableRule cpu = rule(ResourceType.HOST_CPU, Severity.ELEVATED, 300);
        MutableRule memory = rule(ResourceType.HOST_MEMORY, Severity.HIGH, 100);
        Harness harness = new Harness(cpu, memory);

        var transition = harness.coordinator.evaluate(1_000).orElseThrow();

        assertEquals(Severity.HIGH, transition.current().severity());
        assertEquals(ResourceType.HOST_MEMORY, transition.current().resource());
    }

    @Test
    void equalSeverityUsesClearlyStrongerCurrentPressure() {
        MutableRule cpu = rule(ResourceType.HOST_CPU, Severity.HIGH, 100);
        MutableRule memory = rule(ResourceType.HOST_MEMORY, Severity.HIGH, 160);
        Harness harness = new Harness(cpu, memory);

        var transition = harness.coordinator.evaluate(1_000).orElseThrow();

        assertEquals(ResourceType.HOST_MEMORY, transition.current().resource());
    }

    @Test
    void recoveringPrimaryKeepsOtherActiveResource() {
        MutableRule cpu = rule(ResourceType.HOST_CPU, Severity.HIGH, 200);
        MutableRule memory = rule(ResourceType.HOST_MEMORY, Severity.HIGH, 100);
        Harness harness = new Harness(cpu, memory);
        assertEquals(
                ResourceType.HOST_CPU,
                harness.coordinator.evaluate(1_000).orElseThrow().current().resource());

        cpu.decision.set(DiagnosticDecision.normal(ResourceType.HOST_CPU, "CPU pressure cleared."));
        var transition = harness.coordinator.evaluate(2_000).orElseThrow();

        assertEquals(Severity.HIGH, transition.current().severity());
        assertEquals(ResourceType.HOST_MEMORY, transition.current().resource());
        assertEquals(
                Severity.NORMAL,
                harness.coordinator.resourceStates().get(ResourceType.HOST_CPU).severity());
    }

    @Test
    void rawEvidenceChangeDoesNotIncrementVersion() {
        MutableRule cpu = rule(ResourceType.HOST_CPU, Severity.HIGH, 100);
        Harness harness = new Harness(cpu);
        assertEquals(2, harness.coordinator.evaluate(1_000).orElseThrow().current().version());

        cpu.decision.set(
                new DiagnosticDecision(
                        ResourceType.HOST_CPU,
                        ResourceState.SATURATION_RISK,
                        Severity.HIGH,
                        "same state, different metric",
                        List.of(new DiagnosticEvidence("usage", 99, "%")),
                        150));

        assertTrue(harness.coordinator.evaluate(2_000).isEmpty());
        assertEquals(2, harness.repository.current().version());
    }

    private static MutableRule rule(ResourceType resource, Severity severity, double score) {
        ResourceState state =
                switch (severity) {
                    case NORMAL -> ResourceState.NORMAL;
                    case ELEVATED -> ResourceState.PRESSURE;
                    case HIGH -> ResourceState.SATURATION_RISK;
                    case CRITICAL -> ResourceState.SATURATED;
                };
        return new MutableRule(
                resource,
                new DiagnosticDecision(
                        resource, state, severity, resource + " decision", List.of(), score));
    }

    private static final class MutableRule implements DiagnosticRule {
        private final ResourceType resource;
        private final AtomicReference<DiagnosticDecision> decision;

        private MutableRule(ResourceType resource, DiagnosticDecision decision) {
            this.resource = resource;
            this.decision = new AtomicReference<>(decision);
        }

        @Override
        public ResourceType resource() {
            return resource;
        }

        @Override
        public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
            return Optional.of(decision.get());
        }
    }

    private static final class Harness {
        private final InMemoryDiagnosticStateRepository repository =
                new InMemoryDiagnosticStateRepository(
                        DiagnosticSnapshot.initial("host", "test", 0));
        private final DiagnosticCoordinator coordinator;

        private Harness(MutableRule... rules) {
            coordinator =
                    new DiagnosticCoordinator(
                            new InMemoryRollingMetricWindow(
                                    Duration.ofMinutes(1), Duration.ofSeconds(5)),
                            new DiagnosticEngine(List.of(rules)),
                            repository,
                            () -> "event");
        }
    }
}
