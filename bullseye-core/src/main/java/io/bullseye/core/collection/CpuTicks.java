package io.bullseye.core.collection;

public record CpuTicks(long total, long idle) {

    public CpuTicks {
        if (total < 0 || idle < 0 || idle > total) {
            throw new IllegalArgumentException("Invalid CPU ticks");
        }
    }

    public double usageSince(CpuTicks previous) {
        long totalDelta = total - previous.total;
        long idleDelta = idle - previous.idle;
        if (totalDelta <= 0 || idleDelta < 0 || idleDelta > totalDelta) {
            throw new IllegalArgumentException("CPU ticks must increase monotonically");
        }
        return (double) (totalDelta - idleDelta) * 100.0 / totalDelta;
    }
}
