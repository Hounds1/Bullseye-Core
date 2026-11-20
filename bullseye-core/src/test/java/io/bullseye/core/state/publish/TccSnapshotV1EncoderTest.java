package io.bullseye.core.state.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.Observation;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ObservationStatus;
import io.bullseye.common.diagnostic.DiagnosticSnapshot.ResourceDiagnosis;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.common.workload.WorkloadIdentity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

class TccSnapshotV1EncoderTest {

    private static final String NODE_ID = "node-001";
    private static final UUID RUN_ID = UUID.fromString("46e5fffd-6650-452d-8476-ac41e2453c2a");

    private final TccSnapshotV1Encoder encoder = new TccSnapshotV1Encoder();

    @Test
    void matchesInitializingContractFixture() throws IOException {
        String json = encoder.encode(initialSnapshot(1_000), NODE_ID, RUN_ID, 1);

        assertEquals(fixture("initializing.json"), json);
    }

    @Test
    void matchesEvaluatedContractFixtureWithAllResourcesAndPartialCoverage() throws IOException {
        String json = encoder.encode(evaluatedSnapshot(2_000, 2_000), NODE_ID, RUN_ID, 2);

        assertEquals(fixture("evaluated.json"), json);
    }

    @Test
    void keepsRetryDeterministicAndSequenceIndependentOfClocksAndDiagnosisVersion() {
        DiagnosticSnapshot before = evaluatedSnapshot(2_000, 2_000);
        DiagnosticSnapshot after = evaluatedSnapshot(2_500, 1_500);

        String first = encoder.encode(before, NODE_ID, RUN_ID, 2);
        String retry = encoder.encode(before, NODE_ID, RUN_ID, 2);
        String next = encoder.encode(after, NODE_ID, RUN_ID, 3);

        assertEquals(first, retry);
        assertTrue(first.contains("\"sequence\": 2,"));
        assertTrue(first.contains("\"evaluatedAt\": 2000,"));
        assertTrue(first.contains("\"wallClockAt\": 2000,"));
        assertTrue(first.contains("\"version\": 7\n"));
        assertTrue(next.contains("\"sequence\": 3,"));
        assertTrue(next.contains("\"evaluatedAt\": 2500,"));
        assertTrue(next.contains("\"wallClockAt\": 1500,"));
        assertTrue(next.contains("\"version\": 7\n"));
    }

    @Test
    void includesNewRunIdWhenSequenceRestartsForSameNode() throws IOException {
        UUID restartedRunId = UUID.fromString("4bc0fb0c-159f-4165-b000-4e2e377ac3c7");

        String restarted = encoder.encode(initialSnapshot(1_000), NODE_ID, restartedRunId, 1);

        assertEquals(
                fixture("initializing.json").replace(RUN_ID.toString(), restartedRunId.toString()),
                restarted);
    }

    @Test
    void escapesNodeIdentityInEnvelope() {
        String json = encoder.encode(initialSnapshot(1_000), "노드\"\\\n", RUN_ID, 1);

        assertTrue(json.contains("\"nodeId\": \"노드\\\"\\\\\\n\","));
    }

    @Test
    void rejectsMissingIdentityAndNonPositiveSequence() {
        DiagnosticSnapshot snapshot = initialSnapshot(1_000);

        assertThrows(
                IllegalArgumentException.class, () -> encoder.encode(snapshot, " \t", RUN_ID, 1));
        assertThrows(NullPointerException.class, () -> encoder.encode(snapshot, NODE_ID, null, 1));
        assertThrows(
                IllegalArgumentException.class, () -> encoder.encode(snapshot, NODE_ID, RUN_ID, 0));
        assertThrows(
                IllegalArgumentException.class, () -> encoder.encode(snapshot, NODE_ID, RUN_ID, -1));
    }

    private static DiagnosticSnapshot initialSnapshot(long now) {
        return DiagnosticSnapshot.initial("edge-01", "orders", now);
    }

    private static DiagnosticSnapshot evaluatedSnapshot(long evaluatedAt, long wallClockAt) {
        WorkloadAttribution memorySource = WorkloadAttribution.identified(
                WorkloadIdentity.fromCgroupPath("/system.slice/orders.service"),
                53,
                WorkloadAttribution.Confidence.LOW,
                List.of(new DiagnosticEvidence("memory.current", 1_048_576, "B")),
                new WorkloadAttribution.Coverage(2, 1, false, true),
                1_900);
        ResourceDiagnosis memory = new ResourceDiagnosis(
                Severity.HIGH,
                ResourceState.SATURATION_RISK,
                "Critical memory pressure detected.",
                List.of(new DiagnosticEvidence("usage", 88.5, "%")),
                memorySource,
                1_800);
        ResourceDiagnosis cpu = new ResourceDiagnosis(
                Severity.NORMAL,
                ResourceState.NORMAL,
                "System status nominal.",
                List.of(),
                WorkloadAttribution.unresolved(),
                1_000);
        ResourceDiagnosis io = new ResourceDiagnosis(
                Severity.ELEVATED,
                ResourceState.PRESSURE,
                "I/O pressure detected.",
                List.of(new DiagnosticEvidence("psi.some.avg10", 12.5, "%")),
                WorkloadAttribution.unavailable(
                        WorkloadAttribution.Status.NO_CANDIDATE,
                        WorkloadAttribution.Coverage.complete(2),
                        1_700L),
                1_600);

        return new DiagnosticSnapshot(
                "edge-01",
                "orders",
                memory.severity(),
                ResourceType.HOST_MEMORY,
                memory.state(),
                memory.attribution(),
                memory.evidence(),
                memory.since(),
                7,
                evaluatedAt,
                ObservationStatus.STALE,
                Map.of(
                        ResourceType.HOST_CPU,
                        new Observation(ObservationStatus.FRESH, 1_950L,
                                List.of(new DiagnosticEvidence("HOST_CPU_USAGE", 15.25, "%"))),
                        ResourceType.HOST_MEMORY,
                        new Observation(ObservationStatus.FRESH, 1_950L,
                                List.of(new DiagnosticEvidence("HOST_MEMORY_USAGE", 88.5, "%"))),
                        ResourceType.DISK_IO,
                        new Observation(ObservationStatus.STALE, 1_200L, List.of())),
                Map.of(
                        ResourceType.HOST_CPU, cpu,
                        ResourceType.HOST_MEMORY, memory,
                        ResourceType.DISK_IO, io),
                wallClockAt);
    }

    private static String fixture(String name) throws IOException {
        InputStream fixture = TccSnapshotV1EncoderTest.class.getResourceAsStream("/tcc/v1/" + name);
        try (InputStream input = Objects.requireNonNull(fixture, name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
