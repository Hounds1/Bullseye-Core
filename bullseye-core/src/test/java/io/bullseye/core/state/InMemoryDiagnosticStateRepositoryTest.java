package io.bullseye.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ObservationStatus;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ResourceDiagnosis;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.common.workload.WorkloadIdentity;

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

    @Test
    void acceptsInitialNormalResourcePublicationWithoutIncreasingTheVersion() {
        var repository = new InMemoryDiagnosticStateRepository(
                DiagnosticSnapshot.initial("host", "test", 0));
        ResourceDiagnosis normal = resource(
                Severity.NORMAL, ResourceState.NORMAL, WorkloadAttribution.unresolved(), 0);
        DiagnosticSnapshot current = new DiagnosticSnapshot(
                "host", "test", Severity.NORMAL, ResourceType.UNKNOWN, ResourceState.NORMAL,
                WorkloadAttribution.unresolved(), List.of(), 0, 1, 1_000,
                ObservationStatus.FRESH, Map.of(), Map.of(
                        ResourceType.HOST_CPU, normal,
                        ResourceType.HOST_MEMORY, normal,
                        ResourceType.DISK_IO, normal));

        repository.update(current);

        assertEquals(3, repository.current().resources().size());
        assertEquals(1, repository.current().version());
    }

    @Test
    void requiresANewVersionForASecondaryStateChangeEvenWhenThePrimaryIsUnchanged() {
        ResourceDiagnosis normal = resource(
                Severity.NORMAL, ResourceState.NORMAL, WorkloadAttribution.unresolved(), 0);
        var repository = new InMemoryDiagnosticStateRepository(
                primaryCpuSnapshot(2, 1_000, normal));
        ResourceDiagnosis high = resource(
                Severity.HIGH, ResourceState.SATURATION_RISK, WorkloadAttribution.unresolved(), 2_000);

        assertThrows(IllegalArgumentException.class,
                () -> repository.update(primaryCpuSnapshot(2, 2_000, high)));
        repository.update(primaryCpuSnapshot(3, 2_000, high));

        assertEquals(ResourceType.HOST_CPU, repository.current().resource());
        assertEquals(1_000, repository.current().since());
        assertEquals(Severity.HIGH, repository.current().diagnosis(ResourceType.HOST_MEMORY).severity());
    }

    @Test
    void requiresANewVersionForSecondarySourceConfidenceOrStartTimeChanges() {
        WorkloadAttribution source = source("first.service", 50, WorkloadAttribution.Confidence.LOW, 1_000);
        ResourceDiagnosis initial = resource(Severity.HIGH, ResourceState.SATURATION_RISK, source, 1_000);
        var repository = new InMemoryDiagnosticStateRepository(primaryCpuSnapshot(2, 1_000, initial));

        List<ResourceDiagnosis> changes = List.of(
                resource(Severity.HIGH, ResourceState.SATURATION_RISK,
                        source("second.service", 50, WorkloadAttribution.Confidence.LOW, 2_000), 1_000),
                resource(Severity.HIGH, ResourceState.SATURATION_RISK,
                        source("first.service", 50, WorkloadAttribution.Confidence.HIGH, 2_000), 1_000),
                resource(Severity.HIGH, ResourceState.SATURATION_RISK,
                        WorkloadAttribution.unavailable(WorkloadAttribution.Status.ACCESS_DENIED), 1_000),
                resource(Severity.HIGH, ResourceState.SATURATION_RISK, source, 2_000));
        for (ResourceDiagnosis changed : changes) {
            assertThrows(IllegalArgumentException.class,
                    () -> repository.update(primaryCpuSnapshot(2, 2_000, changed)));
        }
    }

    @Test
    void acceptsSecondaryEvidenceScoreCoverageAndCollectionTimeRefreshesAtTheSameVersion() {
        ResourceDiagnosis initial = resource(
                Severity.HIGH, ResourceState.SATURATION_RISK,
                source("writer.service", 50, WorkloadAttribution.Confidence.LOW, 1_000), 1_000);
        var repository = new InMemoryDiagnosticStateRepository(primaryCpuSnapshot(2, 1_000, initial));
        WorkloadAttribution refreshedSource = WorkloadAttribution.identified(
                WorkloadIdentity.fromCgroupPath("/writer.service"), 55, WorkloadAttribution.Confidence.LOW,
                List.of(new DiagnosticEvidence("score.usage", 55, "points")),
                new WorkloadAttribution.Coverage(3, 1, false, false), 2_000);
        ResourceDiagnosis refreshed = new ResourceDiagnosis(
                Severity.HIGH, ResourceState.SATURATION_RISK, "Current memory pressure observed.",
                List.of(new DiagnosticEvidence("usage", 91, "%")), refreshedSource, 1_000);

        repository.update(primaryCpuSnapshot(2, 2_000, refreshed));

        assertEquals(2, repository.current().version());
        assertEquals(55, repository.current().diagnosis(ResourceType.HOST_MEMORY).attribution().score());
        assertEquals(2_000, repository.current().diagnosis(ResourceType.HOST_MEMORY)
                .attribution().collectedAt());
    }

    @Test
    void refusesToSilentlyRemoveASecondaryDiagnosisAtTheSameVersion() {
        ResourceDiagnosis high = resource(
                Severity.HIGH, ResourceState.SATURATION_RISK, WorkloadAttribution.unresolved(), 1_000);
        var repository = new InMemoryDiagnosticStateRepository(primaryCpuSnapshot(2, 1_000, high));
        DiagnosticSnapshot withoutResources = new DiagnosticSnapshot(
                "host", "test", Severity.CRITICAL, ResourceType.HOST_CPU, ResourceState.SATURATED,
                WorkloadAttribution.unresolved(), List.of(), 1_000, 2, 2_000,
                ObservationStatus.FRESH, Map.of());

        assertThrows(IllegalArgumentException.class, () -> repository.update(withoutResources));
    }

    @Test
    void acceptsMaterializingTheLegacyPrimaryDiagnosisAtTheSameVersion() {
        DiagnosticSnapshot legacy = new DiagnosticSnapshot(
                "host", "test", Severity.CRITICAL, ResourceType.HOST_CPU, ResourceState.SATURATED,
                WorkloadAttribution.unresolved(), List.of(), 1_000, 2);
        var repository = new InMemoryDiagnosticStateRepository(legacy);
        ResourceDiagnosis normalMemory = resource(
                Severity.NORMAL, ResourceState.NORMAL, WorkloadAttribution.unresolved(), 1_000);

        repository.update(primaryCpuSnapshot(2, 2_000, normalMemory));

        assertEquals(Severity.CRITICAL, repository.current().diagnosis(ResourceType.HOST_CPU).severity());
        assertEquals(2, repository.current().version());
    }

    private static DiagnosticSnapshot primaryCpuSnapshot(
            long version, long evaluatedAt, ResourceDiagnosis memory) {
        ResourceDiagnosis cpu = resource(
                Severity.CRITICAL, ResourceState.SATURATED, WorkloadAttribution.unresolved(), 1_000);
        return new DiagnosticSnapshot(
                "host", "test", Severity.CRITICAL, ResourceType.HOST_CPU, ResourceState.SATURATED,
                WorkloadAttribution.unresolved(), List.of(), 1_000, version, evaluatedAt,
                ObservationStatus.FRESH, Map.of(), Map.of(
                        ResourceType.HOST_CPU, cpu,
                        ResourceType.HOST_MEMORY, memory));
    }

    private static ResourceDiagnosis resource(
            Severity severity, ResourceState state, WorkloadAttribution attribution, long since) {
        return new ResourceDiagnosis(
                severity, state, "Resource condition evaluated.", List.of(), attribution, since);
    }

    private static WorkloadAttribution source(
            String name, double score, WorkloadAttribution.Confidence confidence, long collectedAt) {
        return WorkloadAttribution.identified(
                WorkloadIdentity.fromCgroupPath("/" + name), score, confidence,
                List.of(), WorkloadAttribution.Coverage.complete(1), collectedAt);
    }

    private static DiagnosticSnapshot snapshot(Severity severity, long version, long evaluatedAt) {
        return new DiagnosticSnapshot(
                "host", "test", severity, ResourceType.UNKNOWN, ResourceState.NORMAL,
                WorkloadAttribution.unresolved(), List.of(), 0, version, evaluatedAt,
                ObservationStatus.FRESH, Map.of());
    }
}
