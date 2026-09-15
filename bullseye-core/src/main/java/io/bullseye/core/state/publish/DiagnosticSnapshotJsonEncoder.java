package io.bullseye.core.state.publish;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;

import java.util.Objects;

public final class DiagnosticSnapshotJsonEncoder {

    public String encode(DiagnosticSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder json =
                new StringBuilder(384)
                        .append("{\n")
                        .append("  \"host\": \"")
                        .append(escape(snapshot.host()))
                        .append("\",\n")
                        .append("  \"application\": \"")
                        .append(escape(snapshot.application()))
                        .append("\",\n")
                        .append("  \"severity\": \"")
                        .append(snapshot.severity())
                        .append("\",\n")
                        .append("  \"resource\": \"")
                        .append(snapshot.resource())
                        .append("\",\n")
                        .append("  \"state\": \"")
                        .append(snapshot.state())
                        .append("\",\n")
                        .append("  \"primaryWorkload\": \"")
                        .append(escape(snapshot.attribution().workload().name()))
                        .append("\",\n")
                        .append("  \"workloadType\": \"")
                        .append(snapshot.attribution().workload().type())
                        .append("\",\n")
                        .append("  \"cgroupPath\": \"")
                        .append(escape(snapshot.attribution().workload().cgroupPath()))
                        .append("\",\n")
                        .append("  \"attributionConfidence\": \"")
                        .append(snapshot.attribution().confidence())
                        .append("\",\n")
                        .append("  \"evidence\": [");
        for (int index = 0; index < snapshot.evidence().size(); index++) {
            var evidence = snapshot.evidence().get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("\n    {\"metric\": \"")
                    .append(escape(evidence.metric()))
                    .append("\", \"value\": ")
                    .append(evidence.value())
                    .append(", \"unit\": \"")
                    .append(escape(evidence.unit()))
                    .append("\"}");
        }
        if (!snapshot.evidence().isEmpty()) {
            json.append('\n').append("  ");
        }
        return json.append("],\n")
                .append("  \"since\": ")
                .append(snapshot.since())
                .append(",\n")
                .append("  \"version\": ")
                .append(snapshot.version())
                .append('\n')
                .append("}\n")
                .toString();
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.toString();
    }
}
