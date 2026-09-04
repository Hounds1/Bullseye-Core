package io.bullseye.core.collection;

import java.util.HashMap;
import java.util.Map;

public final class MemInfoParser {

    private MemInfoParser() {
    }

    public static double parseUsagePercentage(String content) {
        Map<String, Long> values = new HashMap<>();
        content.lines().forEach(line -> {
            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 2 && fields[0].endsWith(":")) {
                String key = fields[0].substring(0, fields[0].length() - 1);
                values.put(key, Long.parseLong(fields[1]));
            }
        });

        long total = required(values, "MemTotal");
        long available = values.getOrDefault(
                "MemAvailable",
                values.getOrDefault("MemFree", 0L)
                        + values.getOrDefault("Buffers", 0L)
                        + values.getOrDefault("Cached", 0L)
        );
        if (total <= 0 || available < 0) {
            throw new IllegalArgumentException("Invalid memory counters");
        }
        return (double) (total - Math.min(total, available)) * 100.0 / total;
    }

    private static long required(Map<String, Long> values, String key) {
        Long value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing memory counter: " + key);
        }
        return value;
    }
}
