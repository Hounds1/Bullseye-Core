package io.bullseye.core.publish;

import io.bullseye.common.DiagnosticSnapshot;

import java.util.Objects;

public final class DiagnosticSnapshotJsonEncoder {

    public String encode(DiagnosticSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return "{\n"
                + "  \"host\": \"" + escape(snapshot.host()) + "\",\n"
                + "  \"application\": \"" + escape(snapshot.application()) + "\",\n"
                + "  \"severity\": \"" + snapshot.severity() + "\",\n"
                + "  \"resource\": \"" + snapshot.resource() + "\",\n"
                + "  \"state\": \"" + snapshot.state() + "\",\n"
                + "  \"since\": " + snapshot.since() + ",\n"
                + "  \"version\": " + snapshot.version() + "\n"
                + "}\n";
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
