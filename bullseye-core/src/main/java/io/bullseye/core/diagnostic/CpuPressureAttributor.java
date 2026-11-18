package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.config.BullseyeConfiguration.AttributionConfidence;
import io.bullseye.core.linux.cgroup.CgroupCpuSnapshot;
import io.bullseye.core.linux.cgroup.CgroupMonitor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class CpuPressureAttributor {

    private final Supplier<CgroupMonitor.CollectionResult<CgroupCpuSnapshot>> collections;
    private final Runnable historyReset;
    private final double minimumScore;
    private final long maximumObservationGapMillis;
    private final AttributionConfidenceTracker confidenceTracker;
    private Integer previousHostCpuCount;

    public CpuPressureAttributor(
            CgroupMonitor monitor,
            double minimumScore,
            double dominanceRatio,
            Duration maximumObservationGap) {
        this(monitor, minimumScore, dominanceRatio, maximumObservationGap,
                AttributionConfidence.defaults());
    }

    public CpuPressureAttributor(
            CgroupMonitor monitor,
            double minimumScore,
            double dominanceRatio,
            Duration maximumObservationGap,
            AttributionConfidence confidence) {
        this(
                Objects.requireNonNull(monitor, "monitor")::collectCpu,
                monitor::clearCpuHistory,
                minimumScore,
                dominanceRatio,
                maximumObservationGap,
                confidence);
    }

    CpuPressureAttributor(
            Supplier<CgroupMonitor.CollectionResult<CgroupCpuSnapshot>> collections,
            double minimumScore,
            double dominanceRatio,
            Duration maximumObservationGap) {
        this(collections, minimumScore, dominanceRatio, maximumObservationGap,
                AttributionConfidence.defaults());
    }

    CpuPressureAttributor(
            Supplier<CgroupMonitor.CollectionResult<CgroupCpuSnapshot>> collections,
            double minimumScore,
            double dominanceRatio,
            Duration maximumObservationGap,
            AttributionConfidence confidence) {
        this(collections, () -> {}, minimumScore, dominanceRatio, maximumObservationGap, confidence);
    }

    private CpuPressureAttributor(
            Supplier<CgroupMonitor.CollectionResult<CgroupCpuSnapshot>> collections,
            Runnable historyReset,
            double minimumScore,
            double dominanceRatio,
            Duration maximumObservationGap,
            AttributionConfidence confidence) {
        this.collections = Objects.requireNonNull(collections, "collections");
        this.historyReset = Objects.requireNonNull(historyReset, "historyReset");
        if (!Double.isFinite(minimumScore) || minimumScore < 0) {
            throw new IllegalArgumentException("minimumScore must be finite and non-negative");
        }
        if (!Double.isFinite(dominanceRatio) || dominanceRatio <= 1) {
            throw new IllegalArgumentException("dominanceRatio must be greater than 1");
        }
        Objects.requireNonNull(maximumObservationGap, "maximumObservationGap");
        maximumObservationGapMillis = maximumObservationGap.toMillis();
        if (maximumObservationGapMillis < 1) {
            throw new IllegalArgumentException("maximumObservationGap must be at least 1ms");
        }
        this.minimumScore = minimumScore;
        confidenceTracker =
                new AttributionConfidenceTracker(confidence, dominanceRatio, maximumObservationGap);
    }

    public WorkloadAttribution attribute(int hostCpuCount) {
        CgroupMonitor.CollectionResult<CgroupCpuSnapshot> collection = collections.get();
        if (collection.status() != CgroupMonitor.Status.AVAILABLE) {
            reset();
            WorkloadAttribution.Status status =
                    collection.status() == CgroupMonitor.Status.ACCESS_DENIED
                            ? WorkloadAttribution.Status.ACCESS_DENIED
                            : WorkloadAttribution.Status.CGROUP_V2_NOT_AVAILABLE;
            return unavailable(status, collection);
        }
        if (hostCpuCount <= 0) {
            reset();
            return unavailable(WorkloadAttribution.Status.INSUFFICIENT_DATA, collection);
        }

        boolean capacityChanged =
                previousHostCpuCount != null && previousHostCpuCount != hostCpuCount;
        previousHostCpuCount = hostCpuCount;
        if (capacityChanged) {
            return unavailable(WorkloadAttribution.Status.INSUFFICIENT_DATA, collection);
        }

        List<Candidate> candidates = new ArrayList<>();
        int unmeasured = 0;
        for (CgroupCpuSnapshot current : collection.snapshots()) {
            CgroupCpuSnapshot previous =
                    collection.previousSnapshots().get(current.workload().cgroupPath());
            if (!comparable(current, previous)) {
                unmeasured++;
                continue;
            }
            candidates.add(candidate(current, previous, hostCpuCount));
        }
        candidates.sort(
                Comparator.comparingDouble(Candidate::score)
                        .reversed()
                        .thenComparing(candidate -> candidate.snapshot().workload().cgroupPath()));
        if (candidates.isEmpty()
                || candidates.getFirst().score() <= 0
                || candidates.getFirst().score() < minimumScore) {
            WorkloadAttribution.Status status =
                    unmeasured > 0
                            ? WorkloadAttribution.Status.INSUFFICIENT_DATA
                            : WorkloadAttribution.Status.NO_CANDIDATE;
            return unavailable(status, collection);
        }

        Candidate winner = candidates.getFirst();
        double runnerScore = candidates.size() > 1 ? candidates.get(1).score() : 0;
        List<DiagnosticEvidence> evidence = new ArrayList<>(winner.evidence());
        evidence.add(metric("candidates.measured", candidates.size(), "count"));
        evidence.add(metric("candidates.unmeasured", unmeasured, "count"));
        CgroupCpuSnapshot snapshot = winner.snapshot();
        WorkloadAttribution.Confidence confidence = confidenceTracker.observe(
                snapshot.workload(), winner.score(), runnerScore, collection.collectedAt(),
                collection.previousSnapshots().get(snapshot.workload().cgroupPath()).collectedAt(),
                collection.coverage().complete() && unmeasured == 0
                        && snapshot.collectedAt() == collection.collectedAt(),
                evidence);
        return WorkloadAttribution.identified(
                winner.snapshot().workload(),
                winner.score(),
                confidence,
                evidence,
                collection.coverage(),
                collection.collectedAt());
    }

    public void reset() {
        previousHostCpuCount = null;
        confidenceTracker.reset();
        historyReset.run();
    }

    public boolean needsConfirmation() {
        return confidenceTracker.needsConfirmation();
    }

    private boolean comparable(CgroupCpuSnapshot current, CgroupCpuSnapshot previous) {
        if (previous == null
                || !current.workload().equals(previous.workload())
                || current.collectedAt() <= previous.collectedAt()
                || current.collectedAt() - previous.collectedAt() > maximumObservationGapMillis) {
            return false;
        }
        return current.usageUsec() >= previous.usageUsec()
                && current.userUsec() >= previous.userUsec()
                && current.systemUsec() >= previous.systemUsec();
    }

    private static Candidate candidate(
            CgroupCpuSnapshot current, CgroupCpuSnapshot previous, int hostCpuCount) {
        long elapsedMillis = current.collectedAt() - previous.collectedAt();
        long usageDelta = current.usageUsec() - previous.usageUsec();
        double usedCores = usageDelta / (elapsedMillis * 1_000.0);
        double hostShare = usedCores / hostCpuCount * 100;
        double score = Math.min(100, hostShare);
        List<DiagnosticEvidence> evidence = new ArrayList<>();
        evidence.add(metric("cpu.usage.cores", usedCores, "cores"));
        evidence.add(metric("host.cpu.count", hostCpuCount, "cores"));
        evidence.add(metric("host.share", hostShare, "%"));
        evidence.add(metric("usage.delta", usageDelta, "us"));
        evidence.add(metric("user.delta", current.userUsec() - previous.userUsec(), "us"));
        evidence.add(metric("system.delta", current.systemUsec() - previous.systemUsec(), "us"));
        evidence.add(metric("observation.duration", elapsedMillis, "ms"));
        addQuotaEvidence(current, previous, usedCores, evidence);
        addDelta(
                "throttling.periods.delta",
                current.nrPeriods(), previous.nrPeriods(), "count", evidence);
        addDelta(
                "throttling.throttled-periods.delta",
                current.nrThrottled(), previous.nrThrottled(), "count", evidence);
        addDelta(
                "throttling.duration.delta",
                current.throttledUsec(), previous.throttledUsec(), "us", evidence);
        if (Double.isFinite(current.psiSome())) {
            evidence.add(metric("psi.some.avg10", current.psiSome(), "%"));
        }
        evidence.add(metric("score.usage", score, "points"));
        return new Candidate(current, score, List.copyOf(evidence));
    }

    private static void addQuotaEvidence(
            CgroupCpuSnapshot current,
            CgroupCpuSnapshot previous,
            double usedCores,
            List<DiagnosticEvidence> evidence) {
        if (current.quotaUsec() == null || current.periodUsec() == null) {
            return;
        }
        double quotaCores = (double) current.quotaUsec() / current.periodUsec();
        evidence.add(metric("quota.cores", quotaCores, "cores"));
        // This is the local configured limit, not an effective ancestor or cpuset limit.
        if (Objects.equals(current.quotaUsec(), previous.quotaUsec())
                && Objects.equals(current.periodUsec(), previous.periodUsec())) {
            evidence.add(metric("quota.usage", usedCores / quotaCores * 100, "%"));
        }
    }

    private static void addDelta(
            String name,
            Long current,
            Long previous,
            String unit,
            List<DiagnosticEvidence> evidence) {
        if (current != null && previous != null && current >= previous) {
            evidence.add(metric(name, current - previous, unit));
        }
    }

    private static DiagnosticEvidence metric(String name, double value, String unit) {
        return new DiagnosticEvidence(name, value, unit);
    }

    private WorkloadAttribution unavailable(
            WorkloadAttribution.Status status,
            CgroupMonitor.CollectionResult<CgroupCpuSnapshot> collection) {
        confidenceTracker.reset();
        return WorkloadAttribution.unavailable(
                status, collection.coverage(), collection.collectedAt());
    }

    private record Candidate(
            CgroupCpuSnapshot snapshot, double score, List<DiagnosticEvidence> evidence) {}
}
