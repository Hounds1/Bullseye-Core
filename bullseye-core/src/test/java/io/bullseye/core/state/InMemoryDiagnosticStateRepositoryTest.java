package io.bullseye.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ObservationStatus;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.workload.WorkloadAttribution;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class InMemoryDiagnosticStateRepositoryTest {

    @Test
    void acceptsHeartbeatWithoutAllowingDiagnosticOrTimeRegression() {
        var repository = new InMemoryDiagnosticStateRepository(
                DiagnosticSnapshot.initial("host", "test", 0));
        repository.update(snapshot(Severity.NORMAL, 1, 1_000));
        assertEquals(1_000, repository.current().evaluatedAt());
        assertThrows(IllegalArgumentException.class,
                () -> repository.update(snapshot(Severity.CRITICAL, 1, 2_000)));
        assertThrows(IllegalArgumentException.class,
                () -> repository.update(snapshot(Severity.NORMAL, 1, 500)));
        assertThrows(IllegalArgumentException.class,
                () -> repository.update(snapshot(Severity.NORMAL, 0, 2_000)));
        repository.update(snapshot(Severity.CRITICAL, 2, 2_000));
        assertEquals(Severity.CRITICAL, repository.current().severity());
    }

    private static DiagnosticSnapshot snapshot(Severity severity, long version, long evaluatedAt) {
        return new DiagnosticSnapshot(
                "host", "test", severity, ResourceType.UNKNOWN, ResourceState.NORMAL,
                WorkloadAttribution.unresolved(), List.of(), 0, version, evaluatedAt,
                ObservationStatus.FRESH, Map.of());
    }
}
