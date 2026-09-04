package io.bullseye.core.publish;

import io.bullseye.common.DiagnosticSnapshot;
import io.bullseye.common.ResourceState;
import io.bullseye.common.ResourceType;
import io.bullseye.common.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFileStatePublisherTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsJsonSnapshotAndAtomicallyReplacesIt() throws Exception {
        Path output = temporaryDirectory.resolve("state.json");
        AtomicFileStatePublisher publisher = new AtomicFileStatePublisher(
                output,
                new DiagnosticSnapshotJsonEncoder()
        );
        DiagnosticSnapshot initial = DiagnosticSnapshot.initial("was-01", "orders", 1_000);
        DiagnosticSnapshot updated = new DiagnosticSnapshot(
                "was-01",
                "orders",
                Severity.HIGH,
                ResourceType.HOST_CPU,
                ResourceState.SATURATION_RISK,
                2_000,
                2
        );

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
}
