package io.bullseye.core.linux.cgroup;

import io.bullseye.common.workload.WorkloadIdentity;

import java.util.Objects;

// A null quota with a present period means unlimited; both null means cpu.max is unavailable.
public record CgroupCpuSnapshot(
        WorkloadIdentity workload,
        long usageUsec,
        long userUsec,
        long systemUsec,
        Long quotaUsec,
        Long periodUsec,
        Long nrPeriods,
        Long nrThrottled,
        Long throttledUsec,
        double psiSome,
        long collectedAt) {

    public CgroupCpuSnapshot {
        Objects.requireNonNull(workload, "workload");
        if (usageUsec < 0 || userUsec < 0 || systemUsec < 0 || collectedAt < 0) {
            throw new IllegalArgumentException("CPU counters and timestamp must not be negative");
        }
        if ((quotaUsec != null && (quotaUsec <= 0 || periodUsec == null))
                || (periodUsec != null && periodUsec <= 0)) {
            throw new IllegalArgumentException(
                    "CPU quota and period must be positive when present");
        }
        requireCounter(nrPeriods, "nrPeriods");
        requireCounter(nrThrottled, "nrThrottled");
        requireCounter(throttledUsec, "throttledUsec");
        if (!Double.isNaN(psiSome) && (!Double.isFinite(psiSome) || psiSome < 0)) {
            throw new IllegalArgumentException("CPU PSI must be non-negative or unavailable");
        }
    }

    private static void requireCounter(Long value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
