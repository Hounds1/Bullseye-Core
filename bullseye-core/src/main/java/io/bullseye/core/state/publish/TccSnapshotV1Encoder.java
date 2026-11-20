package io.bullseye.core.state.publish;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;

import java.util.Objects;
import java.util.UUID;

public final class TccSnapshotV1Encoder {
    private final DiagnosticSnapshotJsonEncoder snapshotEncoder = new DiagnosticSnapshotJsonEncoder();

    /**
     * Encodes one diagnostic record. The caller retains the record identity and payload for retries.
     */
    public String encode(DiagnosticSnapshot snapshot, String nodeId, UUID runId, long sequence) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        Objects.requireNonNull(runId, "runId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }

        String payload = snapshotEncoder.encode(snapshot).indent(2).stripLeading();
        return new StringBuilder(payload.length() + 128)
                .append("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"nodeId\": \"")
                .append(DiagnosticSnapshotJsonEncoder.escape(nodeId))
                .append("\",\n")
                .append("  \"runId\": \"")
                .append(runId)
                .append("\",\n")
                .append("  \"sequence\": ")
                .append(sequence)
                .append(",\n")
                .append("  \"snapshot\": ")
                .append(payload)
                .append("}\n")
                .toString();
    }
}
