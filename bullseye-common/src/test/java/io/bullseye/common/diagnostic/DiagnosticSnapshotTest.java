package io.bullseye.common.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticSnapshot.ObservationStatus;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ResourceDiagnosis;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.common.workload.WorkloadIdentity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

class DiagnosticSnapshotTest {

    @Test
    void capturesAnImmutableCopyOfResourceDiagnosesAndTheirEvidence() {
        List<DiagnosticEvidence> evidence = new ArrayList<>();
        evidence.add(new DiagnosticEvidence("usage", 90, "%"));
        ResourceDiagnosis memory = new ResourceDiagnosis(
                Severity.HIGH, ResourceState.SATURATION_RISK, "Memory pressure detected.",
                evidence, WorkloadAttribution.unresolved(), 1_000);
        Map<ResourceType, ResourceDiagnosis> resources = new EnumMap<>(ResourceType.class);
        resources.put(ResourceType.HOST_MEMORY, memory);
        DiagnosticSnapshot snapshot = snapshot(resources, 2_000);

        evidence.clear();
        resources.clear();

        assertEquals(1, snapshot.resources().size());
        assertEquals(90, snapshot.diagnosis(ResourceType.HOST_MEMORY).evidence().getFirst().value());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.resources().clear());
        assertThrows(UnsupportedOperationException.class, () -> memory.evidence().clear());
    }

    @Test
    void legacySnapshotConstructorsRetainPrimaryDiagnosisWithoutInventingOtherSources() {
        WorkloadAttribution source = source("worker.service", 50, 1_000);
        DiagnosticSnapshot legacy = new DiagnosticSnapshot(
                "host", "test", Severity.CRITICAL, ResourceType.HOST_CPU, ResourceState.SATURATED,
                source, List.of(new DiagnosticEvidence("usage", 95, "%")), 1_000, 2);

        assertTrue(legacy.resources().isEmpty());
        ResourceDiagnosis cpu = legacy.diagnosis(ResourceType.HOST_CPU);
        assertEquals(Severity.CRITICAL, cpu.severity());
        assertEquals(ResourceState.SATURATED, cpu.state());
        assertEquals(source, cpu.attribution());
        assertEquals(legacy.evidence(), cpu.evidence());
        assertEquals(1_000, cpu.since());

        ResourceDiagnosis memory = legacy.diagnosis(ResourceType.HOST_MEMORY);
        assertEquals(Severity.NORMAL, memory.severity());
        assertEquals(ResourceState.NORMAL, memory.state());
        assertEquals(WorkloadAttribution.Status.NOT_ATTEMPTED, memory.attribution().status());
        assertTrue(memory.evidence().isEmpty());
        assertEquals(1_000, memory.since());

        DiagnosticSnapshot observed = new DiagnosticSnapshot(
                "host", "test", Severity.NORMAL, ResourceType.UNKNOWN, ResourceState.NORMAL,
                WorkloadAttribution.unresolved(), List.of(), 0, 1, 2_000,
                ObservationStatus.FRESH, Map.of());
        assertTrue(observed.resources().isEmpty());
        assertEquals(Severity.NORMAL, observed.diagnosis(ResourceType.DISK_IO).severity());
        assertThrows(NullPointerException.class, () -> observed.diagnosis(null));
    }

    @Test
    void rejectsInvalidResourceDiagnosesAndFutureStartTimes() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceDiagnosis(
                Severity.HIGH, ResourceState.SATURATION_RISK, " ", List.of(),
                WorkloadAttribution.unresolved(), 0));
        assertThrows(IllegalArgumentException.class, () -> diagnosis(
                Severity.HIGH, ResourceState.SATURATION_RISK, WorkloadAttribution.unresolved(), -1));
        assertThrows(NullPointerException.class, () -> diagnosis(
                null, ResourceState.NORMAL, WorkloadAttribution.unresolved(), 0));
        assertThrows(NullPointerException.class, () -> diagnosis(
                Severity.NORMAL, null, WorkloadAttribution.unresolved(), 0));
        assertThrows(NullPointerException.class, () -> diagnosis(
                Severity.NORMAL, ResourceState.NORMAL, null, 0));
        assertThrows(NullPointerException.class, () -> snapshot(null, 2_000));

        ResourceDiagnosis future = diagnosis(
                Severity.HIGH, ResourceState.SATURATION_RISK, WorkloadAttribution.unresolved(), 2_001);
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(Map.of(ResourceType.HOST_MEMORY, future), 2_000));
        assertEquals(2_001, snapshot(Map.of(ResourceType.HOST_MEMORY, future), 2_001)
                .diagnosis(ResourceType.HOST_MEMORY).since());
    }

    @Test
    void semanticComparisonIgnoresMeasurementAndExplanationRefreshes() {
        WorkloadIdentity workload = WorkloadIdentity.fromCgroupPath("/writer.service");
        WorkloadAttribution beforeSource = WorkloadAttribution.identified(
                workload, 60, WorkloadAttribution.Confidence.LOW,
                List.of(new DiagnosticEvidence("score.activity", 60, "points")),
                new WorkloadAttribution.Coverage(2, 1, false, false), 1_000);
        WorkloadAttribution afterSource = WorkloadAttribution.identified(
                workload, 70, WorkloadAttribution.Confidence.LOW,
                List.of(new DiagnosticEvidence("score.activity", 70, "points")),
                WorkloadAttribution.Coverage.complete(3), 2_000);
        ResourceDiagnosis before = new ResourceDiagnosis(
                Severity.HIGH, ResourceState.SATURATION_RISK, "IO pressure detected.",
                List.of(new DiagnosticEvidence("psi.some", 25, "%")), beforeSource, 1_000);
        ResourceDiagnosis after = new ResourceDiagnosis(
                Severity.HIGH, ResourceState.SATURATION_RISK, "IO pressure remains elevated.",
                List.of(new DiagnosticEvidence("psi.some", 30, "%")), afterSource, 2_000);

        assertTrue(beforeSource.sameSourceAs(afterSource));
        assertTrue(before.sameStateAs(after));
    }

    @Test
    void semanticComparisonDetectsStateSourceConfidenceAndStatusChanges() {
        WorkloadAttribution firstSource = source("writer.service", 60, 1_000);
        ResourceDiagnosis first = diagnosis(
                Severity.HIGH, ResourceState.SATURATION_RISK, firstSource, 1_000);
        assertFalse(first.sameStateAs(diagnosis(
                Severity.CRITICAL, ResourceState.SATURATION_RISK, firstSource, 1_000)));
        assertFalse(first.sameStateAs(diagnosis(
                Severity.HIGH, ResourceState.SATURATED, firstSource, 1_000)));
        assertFalse(first.sameStateAs(diagnosis(
                Severity.HIGH, ResourceState.SATURATION_RISK,
                source("reader.service", 60, 1_000), 1_000)));
        WorkloadAttribution higherConfidence = WorkloadAttribution.identified(
                firstSource.workload(), 60, WorkloadAttribution.Confidence.HIGH);
        assertFalse(first.sameStateAs(diagnosis(
                Severity.HIGH, ResourceState.SATURATION_RISK, higherConfidence, 1_000)));
        assertFalse(WorkloadAttribution.unavailable(WorkloadAttribution.Status.NO_CANDIDATE)
                .sameSourceAs(WorkloadAttribution.unavailable(WorkloadAttribution.Status.ACCESS_DENIED)));
    }

    private static ResourceDiagnosis diagnosis(
            Severity severity, ResourceState state, WorkloadAttribution attribution, long since) {
        return new ResourceDiagnosis(
                severity, state, "Resource condition evaluated.", List.of(), attribution, since);
    }

    private static WorkloadAttribution source(String name, double score, long collectedAt) {
        return WorkloadAttribution.identified(
                WorkloadIdentity.fromCgroupPath("/" + name), score, WorkloadAttribution.Confidence.LOW,
                List.of(), WorkloadAttribution.Coverage.complete(1), collectedAt);
    }

    private static DiagnosticSnapshot snapshot(
            Map<ResourceType, ResourceDiagnosis> resources, long evaluatedAt) {
        return new DiagnosticSnapshot(
                "host", "test", Severity.NORMAL, ResourceType.UNKNOWN, ResourceState.NORMAL,
                WorkloadAttribution.unresolved(), List.of(), 0, 1, evaluatedAt,
                ObservationStatus.FRESH, Map.of(), resources);
    }
}
