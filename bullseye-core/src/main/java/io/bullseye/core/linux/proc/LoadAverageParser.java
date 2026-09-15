package io.bullseye.core.linux.proc;

public final class LoadAverageParser {

    private LoadAverageParser() {}

    public static double parseOneMinuteLoad(String content) {
        String[] fields = content.trim().split("\\s+");
        if (fields.length == 0 || fields[0].isBlank()) {
            throw new IllegalArgumentException("Missing load average");
        }
        double value = Double.parseDouble(fields[0]);
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Invalid load average");
        }
        return value;
    }
}
