package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.workload.WorkloadIdentity;
import io.bullseye.core.diagnostic.rule.DiagnosticRule;
import io.bullseye.core.linux.cgroup.CgroupResourceSnapshot;
import io.bullseye.core.metric.InMemoryRollingMetricWindow;
import io.bullseye.core.state.InMemoryDiagnosticStateRepository;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

class DiagnosticAttributionStateTest {

    @Test
    void primaryWorkloadChangeIncrementsSnapshotVersion() {
        AtomicReference<List<CgroupResourceSnapshot>> snapshots =
                new AtomicReference<>(List.of(memorySnapshot("first.service", 30, 1_000)));
        InMemoryDiagnosticStateRepository repository =
                new InMemoryDiagnosticStateRepository(
                        DiagnosticSnapshot.initial("host", "test", 0));
        DiagnosticCoordinator coordinator =
                new DiagnosticCoordinator(
                        new InMemoryRollingMetricWindow(
                                Duration.ofMinutes(1), Duration.ofSeconds(5)),
                        new DiagnosticEngine(List.of(new FixedMemoryRule())),
                        repository,
                        () -> "event",
                        snapshots::get,
                        new PressureAttributor(5, 1.2, Duration.ofSeconds(15)));

        var first = coordinator.evaluate(1_000).orElseThrow();
        assertEquals("first.service", first.current().attribution().workload().name());
        assertEquals(2, first.current().version());

        snapshots.set(List.of(memorySnapshot("second.service", 40, 2_000)));
        var second = coordinator.evaluate(2_000).orElseThrow();

        assertEquals("second.service", second.current().attribution().workload().name());
        assertEquals(3, second.current().version());
    }

    private static CgroupResourceSnapshot memorySnapshot(
            String name, double pressure, long timestamp) {
        return new CgroupResourceSnapshot(
                WorkloadIdentity.fromCgroupPath("/system.slice/" + name),
                0,
                800,
                1_000,
                0,
                pressure,
                5,
                0,
                0,
                timestamp);
    }

    private static final class FixedMemoryRule implements DiagnosticRule {
        @Override
        public ResourceType resource() {
            return ResourceType.HOST_MEMORY;
        }

        @Override
        public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
            return Optional.of(
                    new DiagnosticDecision(
                            ResourceType.HOST_MEMORY,
                            ResourceState.SATURATION_RISK,
                            Severity.HIGH,
                            "Memory pressure detected.",
                            List.of(),
                            100));
        }
    }
}
