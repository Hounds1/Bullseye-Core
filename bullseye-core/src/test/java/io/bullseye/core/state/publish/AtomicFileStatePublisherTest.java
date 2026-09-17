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
    void publishesAttributionAndOnlyDecisionEvidence() {
        DiagnosticSnapshot snapshot =
                new DiagnosticSnapshot(
                        "was-01",
                        "orders",
                        Severity.HIGH,
                        ResourceType.HOST_MEMORY,
                        ResourceState.SATURATION_RISK,
                        new WorkloadAttribution(
                                WorkloadIdentity.fromCgroupPath("/system.slice/order-api.service"),
                                WorkloadAttribution.Confidence.HIGH),
                        List.of(
                                new DiagnosticEvidence("usage", 88.1, "%"),
                                new DiagnosticEvidence("psi.some.avg10", 24.3, "%")),
                        2_000,
                        12);

        String json = new DiagnosticSnapshotJsonEncoder().encode(snapshot);

        assertTrue(json.contains("\"primaryWorkload\": \"order-api.service\""));
        assertTrue(json.contains("\"attributionConfidence\": \"HIGH\""));
        assertTrue(json.contains("\"metric\": \"psi.some.avg10\""));
    }
}
