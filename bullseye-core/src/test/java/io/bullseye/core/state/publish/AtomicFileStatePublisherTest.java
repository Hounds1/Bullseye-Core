package io.bullseye.core.state.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.common.workload.WorkloadIdentity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class AtomicFileStatePublisherTest {

    @TempDir Path temporaryDirectory;

    @Test
    void createsJsonSnapshotAndAtomicallyReplacesIt() throws Exception {
        Path output = temporaryDirectory.resolve("state.json");
        AtomicFileStatePublisher publisher =
                new AtomicFileStatePublisher(output, new DiagnosticSnapshotJsonEncoder());
        DiagnosticSnapshot initial = DiagnosticSnapshot.initial("was-01", "orders", 1_000);
        DiagnosticSnapshot updated =
                new DiagnosticSnapshot(
                        "was-01",
                        "orders",
                        Severity.HIGH,
                        ResourceType.HOST_CPU,
                        ResourceState.SATURATION_RISK,
                        2_000,
                        2);

        publisher.publish(initial);
        publisher.publish(updated);

        String json = Files.readString(output, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"severity\": \"HIGH\""));
        assertTrue(json.contains("\"resource\": \"HOST_CPU\""));
        assertTrue(json.contains("\"version\": 2"));
        assertFalse(Files.exists(temporaryDirectory.resolve("state.json.tmp")));
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void escapesJsonText() {
        DiagnosticSnapshot snapshot = DiagnosticSnapshot.initial("host\"one", "line\nbreak", 0);

        String json = new DiagnosticSnapshotJsonEncoder().encode(snapshot);

        assertTrue(json.contains("host\\\"one"));
        assertTrue(json.contains("line\\nbreak"));
    }

    @Test
    void publishesObservationFreshnessAndNullableSampleTimestamp() {
        DiagnosticSnapshot snapshot = new DiagnosticSnapshot(
                "host", "test", Severity.NORMAL, ResourceType.UNKNOWN, ResourceState.NORMAL,
                WorkloadAttribution.unresolved(), List.of(), 0, 1, 15_000,
                DiagnosticSnapshot.ObservationStatus.STALE,
                Map.of(ResourceType.HOST_MEMORY, new DiagnosticSnapshot.Observation(
                        DiagnosticSnapshot.ObservationStatus.STALE, null,
                        List.of(new DiagnosticEvidence("HOST_MEMORY_USAGE", 30, "%")))));

        String json = new DiagnosticSnapshotJsonEncoder().encode(snapshot);

        assertTrue(json.contains("\"evaluatedAt\": 15000"));
        assertTrue(json.contains("\"observationStatus\": \"STALE\""));
        assertTrue(json.contains("\"lastSampleAt\": null"));
        assertTrue(json.contains("\"metric\": \"HOST_MEMORY_USAGE\", \"value\": 30.0"));
    }

    @Test
    void publishesAttributionAndOnlyDecisionEvidence() {
        DiagnosticSnapshot snapshot =
                new DiagnosticSnapshot(
                        "was-01",
                        "orders",
                        Severity.HIGH,
                        ResourceType.HOST_MEMORY,
                        ResourceState.SATURATION_RISK,
                        WorkloadAttribution.identified(
                                WorkloadIdentity.fromCgroupPath("/system.slice/order-api.service"),
                                91,
                                WorkloadAttribution.Confidence.HIGH),
                        List.of(
                                new DiagnosticEvidence("usage", 88.1, "%"),
                                new DiagnosticEvidence("psi.some.avg10", 24.3, "%")),
                        2_000,
                        12);

        String json = new DiagnosticSnapshotJsonEncoder().encode(snapshot);

        assertTrue(json.contains("\"primaryWorkload\": \"order-api.service\""));
        assertTrue(json.contains("\"attributionConfidence\": \"HIGH\""));
        assertTrue(json.contains("\"attributionStatus\": \"IDENTIFIED\""));
        assertTrue(json.contains("\"attributionScore\": 91.0"));
        assertTrue(json.contains("\"metric\": \"psi.some.avg10\""));
    }

    @Test
    void keepsWorkloadSelectionEvidenceSeparateFromHostDecisionEvidence() {
        WorkloadAttribution attribution =
                WorkloadAttribution.identified(
                        WorkloadIdentity.fromCgroupPath("/system.slice/order-api.service"),
                        53,
                        WorkloadAttribution.Confidence.HIGH,
                        List.of(
                                new DiagnosticEvidence("memory.current", 1_048_576, "B"),
                                new DiagnosticEvidence("score.growth", 18, "points")),
                        WorkloadAttribution.Coverage.complete(3),
                        1_000);
        DiagnosticSnapshot snapshot = snapshot(attribution);

        String json = new DiagnosticSnapshotJsonEncoder().encode(snapshot);
        String selectionEvidence =
                json.substring(
                        json.indexOf("\"attributionEvidence\""),
                        json.indexOf("\"evidence\""));
        String hostEvidence =
                json.substring(json.indexOf("\"evidence\""), json.indexOf("\"evaluatedAt\""));

        assertTrue(json.contains("\"attributionCollectedAt\": 1000"));
        assertTrue(json.contains("\"complete\": true"));
        assertTrue(json.contains("\"collectedGroups\": 3"));
        assertTrue(json.contains("\"failedGroups\": 0"));
        assertTrue(json.contains("\"discoveryFailed\": false"));
        assertTrue(json.contains("\"discoveryLimited\": false"));
        assertTrue(selectionEvidence.contains("\"metric\": \"memory.current\""));
        assertTrue(selectionEvidence.contains("\"metric\": \"score.growth\""));
        assertFalse(selectionEvidence.contains("\"metric\": \"usage\""));
        assertTrue(hostEvidence.contains("\"metric\": \"usage\""));
        assertFalse(hostEvidence.contains("\"metric\": \"memory.current\""));
    }

    @Test
    void publishesIncompleteCoverageAlongsideAnIdentifiedCandidate() {
        WorkloadAttribution attribution =
                WorkloadAttribution.identified(
                        WorkloadIdentity.fromCgroupPath("/system.slice/order-api.service"),
                        53,
                        WorkloadAttribution.Confidence.LOW,
                        List.of(new DiagnosticEvidence("score.usage", 25, "points")),
                        new WorkloadAttribution.Coverage(2, 1, true, true),
                        1_000);

        String json = new DiagnosticSnapshotJsonEncoder().encode(snapshot(attribution));

        assertTrue(json.contains("\"attributionStatus\": \"IDENTIFIED\""));
        assertTrue(json.contains("\"attributionConfidence\": \"LOW\""));
        assertTrue(json.contains("\"complete\": false"));
        assertTrue(json.contains("\"collectedGroups\": 2"));
        assertTrue(json.contains("\"failedGroups\": 1"));
        assertTrue(json.contains("\"discoveryFailed\": true"));
        assertTrue(json.contains("\"discoveryLimited\": true"));
    }

    @Test
    void preservesCoverageWhenCollectionProducesNoCandidate() {
        WorkloadAttribution attribution =
                WorkloadAttribution.unavailable(
                        WorkloadAttribution.Status.NO_CANDIDATE,
                        new WorkloadAttribution.Coverage(0, 1, false, false),
                        1_000L);

        String json = new DiagnosticSnapshotJsonEncoder().encode(snapshot(attribution));

        assertTrue(json.contains("\"attributionStatus\": \"NO_CANDIDATE\""));
        assertTrue(json.contains("\"attributionEvidence\": []"));
        assertTrue(json.contains("\"attributionCollectedAt\": 1000"));
        assertTrue(json.contains("\"complete\": false"));
        assertTrue(json.contains("\"failedGroups\": 1"));
    }

    @Test
    void unattemptedAttributionDoesNotClaimCollectionCoverage() {
        String json = new DiagnosticSnapshotJsonEncoder()
                .encode(DiagnosticSnapshot.initial("host", "test", 0));

        assertTrue(json.contains("\"attributionStatus\": \"NOT_ATTEMPTED\""));
        assertTrue(json.contains("\"attributionCollectedAt\": null"));
        assertTrue(json.contains("\"attributionCoverage\": null"));
        assertTrue(json.contains("\"attributionEvidence\": []"));
    }

    @Test
    void escapesAttributionEvidenceText() {
        WorkloadAttribution attribution =
                WorkloadAttribution.identified(
                        WorkloadIdentity.fromCgroupPath("/system.slice/order-api.service"),
                        25,
                        WorkloadAttribution.Confidence.LOW,
                        List.of(new DiagnosticEvidence("metric\"name", 25, "point\nunit")),
                        WorkloadAttribution.Coverage.complete(1),
                        1_000);

        String json = new DiagnosticSnapshotJsonEncoder().encode(snapshot(attribution));

        assertTrue(json.contains("metric\\\"name"));
        assertTrue(json.contains("point\\nunit"));
    }

    private static DiagnosticSnapshot snapshot(WorkloadAttribution attribution) {
        return new DiagnosticSnapshot(
                "host",
                "orders",
                Severity.HIGH,
                ResourceType.HOST_MEMORY,
                ResourceState.SATURATION_RISK,
                attribution,
                List.of(new DiagnosticEvidence("usage", 88.1, "%")),
                2_000,
                2);
    }
}
