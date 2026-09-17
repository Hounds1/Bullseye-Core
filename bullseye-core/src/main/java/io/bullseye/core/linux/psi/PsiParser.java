package io.bullseye.core.linux.psi;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PsiParser {

    private PsiParser() {}

    public static Snapshot parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("PSI content must not be blank");
        }

        Line some = null;
        Line full = null;
        for (String rawLine : raw.lines().toList()) {
            String value = rawLine.trim();
            if (value.isEmpty()) {
                continue;
            }
            String[] tokens = value.split("\\s+");
            if (tokens.length != 5 || !("some".equals(tokens[0]) || "full".equals(tokens[0]))) {
                throw new IllegalArgumentException("Malformed PSI line: " + value);
            }
            Map<String, String> fields = new HashMap<>(4);
            for (int index = 1; index < tokens.length; index++) {
                String[] pair = tokens[index].split("=", 2);
                if (pair.length != 2 || fields.put(pair[0], pair[1]) != null) {
                    throw new IllegalArgumentException("Malformed PSI field: " + tokens[index]);
                }
            }
            Line parsed =
                    new Line(
                            decimal(fields, "avg10"),
                            decimal(fields, "avg60"),
                            decimal(fields, "avg300"),
                            whole(fields, "total"));
            if ("some".equals(tokens[0])) {
                if (some != null) {
                    throw new IllegalArgumentException("Duplicate PSI some line");
                }
                some = parsed;
            } else {
                if (full != null) {
                    throw new IllegalArgumentException("Duplicate PSI full line");
                }
                full = parsed;
            }
        }
        if (some == null) {
            throw new IllegalArgumentException("PSI some line is required");
        }
        return new Snapshot(some, Optional.ofNullable(full));
    }

    private static double decimal(Map<String, String> fields, String key) {
        String raw = required(fields, key);
        double parsed = Double.parseDouble(raw);
        if (!Double.isFinite(parsed) || parsed < 0) {
            throw new IllegalArgumentException("Invalid PSI field: " + key);
        }
        return parsed;
    }

    private static long whole(Map<String, String> fields, String key) {
        String raw = required(fields, key);
        long parsed = Long.parseLong(raw);
        if (parsed < 0) {
            throw new IllegalArgumentException("Invalid PSI field: " + key);
        }
        return parsed;
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing PSI field: " + key);
        }
        return value;
    }

    public record Line(double avg10, double avg60, double avg300, long total) {}

    public record Snapshot(Line some, Optional<Line> full) {

        public Snapshot {
            Objects.requireNonNull(some, "some");
            Objects.requireNonNull(full, "full");
        }
    }
}
