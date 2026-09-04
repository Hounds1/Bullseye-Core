package io.bullseye.core.collection;

public final class ProcStatParser {

    private ProcStatParser() {
    }

    public static CpuTicks parseCpuTicks(String content) {
        String firstLine = content.lines()
                .filter(line -> line.startsWith("cpu "))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing aggregate CPU line"));
        String[] fields = firstLine.trim().split("\\s+");
        if (fields.length < 5) {
            throw new IllegalArgumentException("Incomplete aggregate CPU line");
        }

        long total = 0;
        int lastIncludedField = Math.min(fields.length - 1, 8);
        for (int index = 1; index <= lastIncludedField; index++) {
            total = Math.addExact(total, Long.parseLong(fields[index]));
        }
        long idle = Long.parseLong(fields[4]);
        if (fields.length > 5) {
            idle = Math.addExact(idle, Long.parseLong(fields[5]));
        }
        return new CpuTicks(total, idle);
    }
}
