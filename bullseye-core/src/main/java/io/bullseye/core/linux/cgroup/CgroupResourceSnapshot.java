package io.bullseye.core.linux.cgroup;

import io.bullseye.common.workload.WorkloadIdentity;

import java.util.Objects;

public record CgroupResourceSnapshot(
        WorkloadIdentity workload,
        double cpuUsage,
        long memoryCurrent,
        long memoryMax,
        double cpuPsiSome,
        double memoryPsiSome,
        double memoryPsiFull,
        double ioPsiSome,
        double ioPsiFull,
        double cpuPsiTrend,
        double memoryPsiTrend,
        double ioPsiTrend,
        long timestamp) {

    public CgroupResourceSnapshot {
        Objects.requireNonNull(workload, "workload");
        requireMetric(cpuUsage, "cpuUsage");
        requireMetric(cpuPsiSome, "cpuPsiSome");
        requireMetric(memoryPsiSome, "memoryPsiSome");
        requireMetric(memoryPsiFull, "memoryPsiFull");
        requireMetric(ioPsiSome, "ioPsiSome");
        requireMetric(ioPsiFull, "ioPsiFull");
        requireTrend(cpuPsiTrend, "cpuPsiTrend");
        requireTrend(memoryPsiTrend, "memoryPsiTrend");
        requireTrend(ioPsiTrend, "ioPsiTrend");
        if (memoryCurrent < -1 || memoryMax < -1 || timestamp < 0) {
            throw new IllegalArgumentException("cgroup counters and timestamp must not be invalid");
        }
    }

    public CgroupResourceSnapshot(
            WorkloadIdentity workload,
            double cpuUsage,
            long memoryCurrent,
            long memoryMax,
            double cpuPsiSome,
            double memoryPsiSome,
            double memoryPsiFull,
            double ioPsiSome,
            double ioPsiFull,
            long timestamp) {
        this(
                workload,
                cpuUsage,
                memoryCurrent,
                memoryMax,
                cpuPsiSome,
                memoryPsiSome,
                memoryPsiFull,
                ioPsiSome,
                ioPsiFull,
                0,
                0,
                0,
                timestamp);
    }

    public boolean hasAnyPressureMetric() {
        return Double.isFinite(cpuPsiSome)
                || Double.isFinite(memoryPsiSome)
                || Double.isFinite(memoryPsiFull)
                || Double.isFinite(ioPsiSome)
                || Double.isFinite(ioPsiFull);
    }

    private static void requireMetric(double value, String name) {
        if (Double.isFinite(value) && value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative or unavailable");
        }
    }

    private static void requireTrend(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
