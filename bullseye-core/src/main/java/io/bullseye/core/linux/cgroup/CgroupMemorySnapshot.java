package io.bullseye.core.linux.cgroup;

import io.bullseye.common.workload.WorkloadIdentity;

import java.util.Objects;

public record CgroupMemorySnapshot(
        WorkloadIdentity workload,
        long currentBytes,
        Long highBytes,
        Long maxBytes,
        double psiSome,
        double psiFull,
        long highEvents,
        long maxEvents,
        long oomEvents,
        long oomKillEvents,
        long collectedAt) {

    public CgroupMemorySnapshot {
        Objects.requireNonNull(workload, "workload");
        if (currentBytes < 0
                || (highBytes != null && highBytes < 0)
                || (maxBytes != null && maxBytes < 0)
                || collectedAt < 0) {
            throw new IllegalArgumentException(
                    "memory counters and timestamp must not be negative");
        }
        requireMetric(psiSome, "psiSome");
        requireMetric(psiFull, "psiFull");
        if (highEvents < 0 || maxEvents < 0 || oomEvents < 0 || oomKillEvents < 0) {
            throw new IllegalArgumentException("memory event counters must not be negative");
        }
    }

    public CgroupMemorySnapshot(
            WorkloadIdentity workload,
            long currentBytes,
            Long maxBytes,
            double psiSome,
            double psiFull,
            long highEvents,
            long maxEvents,
            long oomEvents,
            long oomKillEvents,
            long collectedAt) {
        this(
                workload,
                currentBytes,
                null,
                maxBytes,
                psiSome,
                psiFull,
                highEvents,
                maxEvents,
                oomEvents,
                oomKillEvents,
                collectedAt);
    }

    private static void requireMetric(double value, String name) {
        if (!Double.isNaN(value) && (!Double.isFinite(value) || value < 0)) {
            throw new IllegalArgumentException(name + " must be non-negative or unavailable");
        }
    }
}
