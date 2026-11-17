package io.bullseye.core.diagnostic;

import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.linux.cgroup.CgroupMemorySnapshot;
import io.bullseye.core.linux.cgroup.CgroupMonitor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class MemoryPressureAttributor {

    private static final double USAGE_WEIGHT = 25;
    private static final double GROWTH_WEIGHT = 35;
    private static final double PRESSURE_WEIGHT = 30;
    private static final double EVENT_WEIGHT = 10;
    private static final double HOST_SHARE_SATURATION = 0.25;
    private static final double HOST_GROWTH_SATURATION = 0.10;
    private static final double PSI_SOME_SATURATION = 50;
    private static final double PSI_FULL_SATURATION = 10;

    private final Supplier<CgroupMonitor.CollectionResult> collections;
    private final Runnable historyReset;
    private final double minimumScore;
    private final double dominanceRatio;

    public MemoryPressureAttributor(
            CgroupMonitor monitor, double minimumScore, double dominanceRatio) {
        this(
                Objects.requireNonNull(monitor, "monitor")::collectMemory,
                monitor::clearHistory,
                minimumScore,
                dominanceRatio);
    }

    MemoryPressureAttributor(
            Supplier<CgroupMonitor.CollectionResult> collections,
            double minimumScore,
            double dominanceRatio) {
        this(collections, () -> {}, minimumScore, dominanceRatio);
    }

    private MemoryPressureAttributor(
            Supplier<CgroupMonitor.CollectionResult> collections,
            Runnable historyReset,
            double minimumScore,
            double dominanceRatio) {
        this.collections = Objects.requireNonNull(collections, "collections");
        this.historyReset = Objects.requireNonNull(historyReset, "historyReset");
        if (!Double.isFinite(minimumScore) || minimumScore < 0) {
            throw new IllegalArgumentException("minimumScore must be finite and non-negative");
        }
        if (!Double.isFinite(dominanceRatio) || dominanceRatio <= 1) {
            throw new IllegalArgumentException("dominanceRatio must be greater than 1");
        }
        this.minimumScore = minimumScore;
        this.dominanceRatio = dominanceRatio;
    }

    public WorkloadAttribution attribute(long hostTotalBytes) {
        CgroupMonitor.CollectionResult collection = collections.get();
        if (collection.status() == CgroupMonitor.Status.CGROUP_V2_NOT_AVAILABLE) {
            return unavailable(WorkloadAttribution.Status.CGROUP_V2_NOT_AVAILABLE);
        }
        if (collection.status() == CgroupMonitor.Status.ACCESS_DENIED) {
            return unavailable(WorkloadAttribution.Status.ACCESS_DENIED);
        }
        if (hostTotalBytes <= 0) {
            return unavailable(WorkloadAttribution.Status.INSUFFICIENT_DATA);
        }

        List<Candidate> candidates =
                collection.snapshots().stream()
                        .map(
                                snapshot ->
                                        candidate(
                                                snapshot,
                                                collection.previousSnapshots(),
                                                hostTotalBytes))
                        .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                        .toList();
        if (candidates.isEmpty() || candidates.getFirst().score() < minimumScore) {
            return unavailable(WorkloadAttribution.Status.NO_CANDIDATE);
        }

        Candidate winner = candidates.getFirst();
        double runnerScore = candidates.size() > 1 ? candidates.get(1).score() : 0;
        double ratio = runnerScore == 0 ? Double.POSITIVE_INFINITY : winner.score() / runnerScore;
        WorkloadAttribution.Confidence confidence = confidence(winner.score(), ratio);
        return WorkloadAttribution.identified(
                winner.snapshot().workload(), winner.score(), confidence);
    }

    public void reset() {
        historyReset.run();
    }

    private Candidate candidate(
            CgroupMemorySnapshot current,
            Map<String, CgroupMemorySnapshot> previousSnapshots,
            long hostTotalBytes) {
        CgroupMemorySnapshot previous = previousSnapshots.get(current.workload().cgroupPath());
        Long growthBytes =
                previous == null ? null : current.currentBytes() - previous.currentBytes();
        double hostShare = (double) current.currentBytes() / hostTotalBytes;
        double score =
                usageScore(hostShare)
                        + growthScore(growthBytes, hostTotalBytes)
                        + pressureScore(current)
                        + eventScore(current, previous);
        return new Candidate(current, growthBytes, hostShare, score);
    }

    private WorkloadAttribution.Confidence confidence(double score, double ratio) {
        if (ratio >= Math.max(2, dominanceRatio) && score >= 45) {
            return WorkloadAttribution.Confidence.HIGH;
        }
        if (ratio >= dominanceRatio && score >= 20) {
            return WorkloadAttribution.Confidence.MEDIUM;
        }
        return WorkloadAttribution.Confidence.LOW;
    }

    private static double usageScore(double hostShare) {
        return normalized(hostShare / HOST_SHARE_SATURATION) * USAGE_WEIGHT;
    }

    private static double growthScore(Long growthBytes, long hostTotalBytes) {
        if (growthBytes == null || growthBytes <= 0) {
            return 0;
        }
        double growthShare = (double) growthBytes / hostTotalBytes;
        return normalized(growthShare / HOST_GROWTH_SATURATION) * GROWTH_WEIGHT;
    }

    private static double pressureScore(CgroupMemorySnapshot snapshot) {
        double some = available(snapshot.psiSome()) / PSI_SOME_SATURATION;
        double full = available(snapshot.psiFull()) / PSI_FULL_SATURATION;
        return normalized(Math.max(some, full)) * PRESSURE_WEIGHT;
    }

    private static double eventScore(CgroupMemorySnapshot current, CgroupMemorySnapshot previous) {
        if (previous == null) {
            return 0;
        }
        long high = increase(current.highEvents(), previous.highEvents());
        long max = increase(current.maxEvents(), previous.maxEvents());
        long oom = increase(current.oomEvents(), previous.oomEvents());
        long oomKill = increase(current.oomKillEvents(), previous.oomKillEvents());
        double impact = high * 0.02 + max * 0.1 + oom * 0.5 + oomKill;
        return normalized(impact) * EVENT_WEIGHT;
    }

    private static long increase(long current, long previous) {
        return Math.max(0, current - previous);
    }

    private static double available(double value) {
        return Double.isFinite(value) ? value : 0;
    }

    private static double normalized(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static WorkloadAttribution unavailable(WorkloadAttribution.Status status) {
        return WorkloadAttribution.unavailable(status);
    }

    public record Candidate(
            CgroupMemorySnapshot snapshot, Long growthBytes, double hostShare, double score) {

        public Candidate {
            Objects.requireNonNull(snapshot, "snapshot");
            if (!Double.isFinite(hostShare) || hostShare < 0) {
                throw new IllegalArgumentException("hostShare must be finite and non-negative");
            }
            if (!Double.isFinite(score) || score < 0) {
                throw new IllegalArgumentException("score must be finite and non-negative");
            }
        }
    }
}
