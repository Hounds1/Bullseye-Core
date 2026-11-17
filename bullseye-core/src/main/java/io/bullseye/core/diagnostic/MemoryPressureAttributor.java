package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.linux.cgroup.CgroupMemorySnapshot;
import io.bullseye.core.linux.cgroup.CgroupMonitor;

import java.util.ArrayList;
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
    // Same scale as 10% of host memory gained in the original five-second sample interval.
    private static final double HOST_GROWTH_PER_SECOND_SATURATION = 0.02;
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
            return unavailable(WorkloadAttribution.Status.CGROUP_V2_NOT_AVAILABLE, collection);
        }
        if (collection.status() == CgroupMonitor.Status.ACCESS_DENIED) {
            return unavailable(WorkloadAttribution.Status.ACCESS_DENIED, collection);
        }
        if (hostTotalBytes <= 0) {
            return unavailable(WorkloadAttribution.Status.INSUFFICIENT_DATA, collection);
        }

        List<Candidate> candidates =
                collection.snapshots().stream()
                        .map(
                                snapshot ->
                                        candidate(
                                                snapshot,
                                                collection.previousSnapshots(),
                                                hostTotalBytes))
                        .sorted(
                                Comparator.comparingDouble(Candidate::score)
                                        .reversed()
                                        .thenComparing(c -> c.snapshot().workload().cgroupPath()))
                        .toList();
        if (candidates.isEmpty() || candidates.getFirst().score() < minimumScore) {
            return unavailable(WorkloadAttribution.Status.NO_CANDIDATE, collection);
        }

        Candidate winner = candidates.getFirst();
        double runnerScore = candidates.size() > 1 ? candidates.get(1).score() : 0;
        double ratio = runnerScore == 0 ? Double.POSITIVE_INFINITY : winner.score() / runnerScore;
        WorkloadAttribution.Confidence confidence = confidence(winner.score(), ratio);
        if (!collection.coverage().complete()) {
            confidence = WorkloadAttribution.Confidence.LOW;
        }
        return WorkloadAttribution.identified(
                winner.snapshot().workload(),
                winner.score(),
                confidence,
                winner.evidence(),
                collection.coverage(),
                collection.collectedAt());
    }

    public void reset() {
        historyReset.run();
    }

    private Candidate candidate(
            CgroupMemorySnapshot current,
            Map<String, CgroupMemorySnapshot> previousSnapshots,
            long hostTotalBytes) {
        CgroupMemorySnapshot previous = previousSnapshots.get(current.workload().cgroupPath());
        boolean comparable = previous != null && current.collectedAt() > previous.collectedAt();
        double hostShare = (double) current.currentBytes() / hostTotalBytes;
        List<DiagnosticEvidence> evidence = new ArrayList<>();
        evidence.add(metric("memory.current", current.currentBytes(), "bytes"));
        if (current.maxBytes() != null) {
            evidence.add(metric("memory.max", current.maxBytes(), "bytes"));
        }
        evidence.add(metric("host.share", hostShare * 100, "%"));
        double growth = 0;
        double events = 0;
        if (comparable) {
            long elapsedMillis = current.collectedAt() - previous.collectedAt();
            long growthBytes = current.currentBytes() - previous.currentBytes();
            double bytesPerSecond = (double) growthBytes * 1_000 / elapsedMillis;
            evidence.add(metric("observation.duration", elapsedMillis, "ms"));
            evidence.add(metric("growth.bytes", growthBytes, "bytes"));
            evidence.add(metric("growth.bytes-per-second", bytesPerSecond, "bytes/s"));
            growth = growthScore(bytesPerSecond, hostTotalBytes);
            events = eventScore(current, previous, evidence);
        }
        if (Double.isFinite(current.psiSome())) {
            evidence.add(metric("psi.some.avg10", current.psiSome(), "%"));
        }
        if (Double.isFinite(current.psiFull())) {
            evidence.add(metric("psi.full.avg10", current.psiFull(), "%"));
        }
        double usage = usageScore(hostShare);
        double pressure = pressureScore(current);
        evidence.add(metric("score.usage", usage, "points"));
        evidence.add(metric("score.growth", growth, "points"));
        evidence.add(metric("score.pressure", pressure, "points"));
        evidence.add(metric("score.events", events, "points"));
        return new Candidate(current, usage + growth + pressure + events, List.copyOf(evidence));
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

    private static double growthScore(double bytesPerSecond, long hostTotalBytes) {
        double growthSharePerSecond = bytesPerSecond / hostTotalBytes;
        return normalized(growthSharePerSecond / HOST_GROWTH_PER_SECOND_SATURATION)
                * GROWTH_WEIGHT;
    }

    private static double pressureScore(CgroupMemorySnapshot snapshot) {
        double some = available(snapshot.psiSome()) / PSI_SOME_SATURATION;
        double full = available(snapshot.psiFull()) / PSI_FULL_SATURATION;
        return normalized(Math.max(some, full)) * PRESSURE_WEIGHT;
    }

    private static double eventScore(
            CgroupMemorySnapshot current,
            CgroupMemorySnapshot previous,
            List<DiagnosticEvidence> evidence) {
        long high = increase(current.highEvents(), previous.highEvents());
        long max = increase(current.maxEvents(), previous.maxEvents());
        long oom = increase(current.oomEvents(), previous.oomEvents());
        long oomKill = increase(current.oomKillEvents(), previous.oomKillEvents());
        evidence.add(metric("events.high.delta", high, "count"));
        evidence.add(metric("events.max.delta", max, "count"));
        evidence.add(metric("events.oom.delta", oom, "count"));
        evidence.add(metric("events.oom_kill.delta", oomKill, "count"));
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

    private static DiagnosticEvidence metric(String name, double value, String unit) {
        return new DiagnosticEvidence(name, value, unit);
    }

    private static WorkloadAttribution unavailable(
            WorkloadAttribution.Status status, CgroupMonitor.CollectionResult collection) {
        return WorkloadAttribution.unavailable(
                status, collection.coverage(), collection.collectedAt());
    }

    private record Candidate(
            CgroupMemorySnapshot snapshot, double score, List<DiagnosticEvidence> evidence) {}
}
