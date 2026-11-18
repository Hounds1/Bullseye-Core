package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.config.BullseyeConfiguration.AttributionConfidence;
import io.bullseye.core.linux.cgroup.CgroupMemorySnapshot;
import io.bullseye.core.linux.cgroup.CgroupMonitor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class MemoryPressureAttributor {

    private static final double USAGE_WEIGHT = 65;
    private static final double GROWTH_WEIGHT = 35;
    // Same scale as 10% of host memory gained in the original five-second sample interval.
    private static final double HOST_GROWTH_PER_SECOND_SATURATION = 0.02;

    private final Supplier<CgroupMonitor.CollectionResult<CgroupMemorySnapshot>> collections;
    private final Runnable historyReset;
    private final double minimumScore;
    private final long maximumObservationGapMillis;
    private final AttributionConfidenceTracker confidenceTracker;
    private Long previousHostTotalBytes;
    private boolean discardPrevious;

    public MemoryPressureAttributor(
            CgroupMonitor monitor, double minimumScore, double dominanceRatio) {
        this(monitor, minimumScore, dominanceRatio, Duration.ofSeconds(40),
                AttributionConfidence.defaults());
    }

    public MemoryPressureAttributor(
            CgroupMonitor monitor,
            double minimumScore,
            double dominanceRatio,
            Duration maximumObservationGap,
            AttributionConfidence confidence) {
        this(
                Objects.requireNonNull(monitor, "monitor")::collectMemory,
                monitor::clearMemoryHistory,
                minimumScore,
                dominanceRatio,
                maximumObservationGap,
                confidence);
    }

    MemoryPressureAttributor(
            Supplier<CgroupMonitor.CollectionResult<CgroupMemorySnapshot>> collections,
            double minimumScore,
            double dominanceRatio) {
        this(collections, minimumScore, dominanceRatio, Duration.ofSeconds(40),
                AttributionConfidence.defaults());
    }

    MemoryPressureAttributor(
            Supplier<CgroupMonitor.CollectionResult<CgroupMemorySnapshot>> collections,
            double minimumScore,
            double dominanceRatio,
            Duration maximumObservationGap,
            AttributionConfidence confidence) {
        this(collections, () -> {}, minimumScore, dominanceRatio, maximumObservationGap, confidence);
    }

    private MemoryPressureAttributor(
            Supplier<CgroupMonitor.CollectionResult<CgroupMemorySnapshot>> collections,
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
        this.minimumScore = minimumScore;
        Objects.requireNonNull(maximumObservationGap, "maximumObservationGap");
        maximumObservationGapMillis = maximumObservationGap.toMillis();
        if (maximumObservationGapMillis < 1) {
            throw new IllegalArgumentException("maximumObservationGap must be at least 1ms");
        }
        confidenceTracker =
                new AttributionConfidenceTracker(confidence, dominanceRatio, maximumObservationGap);
    }

    public WorkloadAttribution attribute(long hostTotalBytes) {
        CgroupMonitor.CollectionResult<CgroupMemorySnapshot> collection = collections.get();
        if (collection.status() == CgroupMonitor.Status.CGROUP_V2_NOT_AVAILABLE) {
            return unavailable(WorkloadAttribution.Status.CGROUP_V2_NOT_AVAILABLE, collection);
        }
        if (collection.status() == CgroupMonitor.Status.ACCESS_DENIED) {
            return unavailable(WorkloadAttribution.Status.ACCESS_DENIED, collection);
        }
        if (hostTotalBytes <= 0) {
            return unavailable(WorkloadAttribution.Status.INSUFFICIENT_DATA, collection);
        }

        boolean capacityChanged =
                previousHostTotalBytes != null && previousHostTotalBytes != hostTotalBytes;
        previousHostTotalBytes = hostTotalBytes;
        Map<String, CgroupMemorySnapshot> previous =
                capacityChanged || discardPrevious ? Map.of() : collection.previousSnapshots();
        discardPrevious = false;

        List<Candidate> candidates =
                collection.snapshots().stream()
                        .map(
                                snapshot ->
                                        candidate(
                                                snapshot,
                                                previous,
                                                hostTotalBytes))
                        .sorted(
                                Comparator.comparingDouble(Candidate::score)
                                        .reversed()
                                        .thenComparing(c -> c.snapshot().workload().cgroupPath()))
                        .toList();
        if (candidates.isEmpty()
                || candidates.getFirst().score() <= 0
                || candidates.getFirst().score() < minimumScore) {
            return unavailable(WorkloadAttribution.Status.NO_CANDIDATE, collection);
        }

        Candidate winner = candidates.getFirst();
        double runnerScore = candidates.size() > 1 ? candidates.get(1).score() : 0;
        CgroupMemorySnapshot snapshot = winner.snapshot();
        CgroupMemorySnapshot before = previous.get(snapshot.workload().cgroupPath());
        boolean countersReset = before != null
                && (snapshot.highEvents() < before.highEvents()
                        || snapshot.maxEvents() < before.maxEvents()
                        || snapshot.oomEvents() < before.oomEvents()
                        || snapshot.oomKillEvents() < before.oomKillEvents());
        List<DiagnosticEvidence> evidence = new ArrayList<>(winner.evidence());
        WorkloadAttribution.Confidence confidence = confidenceTracker.observe(
                snapshot.workload(), winner.score(), runnerScore, collection.collectedAt(), null,
                collection.coverage().complete() && !capacityChanged && !countersReset
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
        confidenceTracker.reset();
        previousHostTotalBytes = null;
        discardPrevious = true;
        historyReset.run();
    }

    public boolean needsConfirmation() {
        return confidenceTracker.needsConfirmation();
    }

    private Candidate candidate(
            CgroupMemorySnapshot current,
            Map<String, CgroupMemorySnapshot> previousSnapshots,
            long hostTotalBytes) {
        CgroupMemorySnapshot previous = previousSnapshots.get(current.workload().cgroupPath());
        boolean comparable =
                previous != null
                        && current.workload().equals(previous.workload())
                        && current.collectedAt() > previous.collectedAt()
                        && current.collectedAt() - previous.collectedAt() <= maximumObservationGapMillis;
        double hostShare = (double) current.currentBytes() / hostTotalBytes;
        List<DiagnosticEvidence> evidence = new ArrayList<>();
        evidence.add(metric("memory.current", current.currentBytes(), "bytes"));
        limitEvidence("high", current.highBytes(), current.currentBytes(), evidence);
        limitEvidence("max", current.maxBytes(), current.currentBytes(), evidence);
        evidence.add(metric("host.share", hostShare * 100, "%"));
        double growth = 0;
        if (comparable) {
            long elapsedMillis = current.collectedAt() - previous.collectedAt();
            long growthBytes = current.currentBytes() - previous.currentBytes();
            double bytesPerSecond = (double) growthBytes * 1_000 / elapsedMillis;
            evidence.add(metric("observation.duration", elapsedMillis, "ms"));
            evidence.add(metric("growth.bytes", growthBytes, "bytes"));
            evidence.add(metric("growth.bytes-per-second", bytesPerSecond, "bytes/s"));
            growth = growthScore(bytesPerSecond, hostTotalBytes);
            eventEvidence(current, previous, evidence);
        }
        if (Double.isFinite(current.psiSome())) {
            evidence.add(metric("psi.some.avg10", current.psiSome(), "%"));
        }
        if (Double.isFinite(current.psiFull())) {
            evidence.add(metric("psi.full.avg10", current.psiFull(), "%"));
        }
        double usage = usageScore(hostShare);
        evidence.add(metric("score.usage", usage, "points"));
        evidence.add(metric("score.growth", growth, "points"));
        // Keep the existing score keys; local stalls/events do not prove host contribution.
        evidence.add(metric("score.pressure", 0, "points"));
        evidence.add(metric("score.events", 0, "points"));
        return new Candidate(current, usage + growth, List.copyOf(evidence));
    }

    private static double usageScore(double hostShare) {
        return normalized(hostShare) * USAGE_WEIGHT;
    }

    private static double growthScore(double bytesPerSecond, long hostTotalBytes) {
        double growthSharePerSecond = bytesPerSecond / hostTotalBytes;
        return normalized(growthSharePerSecond / HOST_GROWTH_PER_SECOND_SATURATION)
                * GROWTH_WEIGHT;
    }

    private static void limitEvidence(
            String boundary, Long limitBytes, long currentBytes, List<DiagnosticEvidence> evidence) {
        if (limitBytes == null) {
            return;
        }
        evidence.add(metric("memory." + boundary, limitBytes, "bytes"));
        // These are this cgroup's limits, not effective ancestor limits or host capacity.
        if (limitBytes > 0) {
            evidence.add(
                    metric(
                            "limit." + boundary + ".usage",
                            (double) currentBytes / limitBytes * 100,
                            "%"));
        }
    }

    private static void eventEvidence(
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
    }

    private static long increase(long current, long previous) {
        return Math.max(0, current - previous);
    }

    private static double normalized(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static DiagnosticEvidence metric(String name, double value, String unit) {
        return new DiagnosticEvidence(name, value, unit);
    }

    private WorkloadAttribution unavailable(
            WorkloadAttribution.Status status,
            CgroupMonitor.CollectionResult<CgroupMemorySnapshot> collection) {
        confidenceTracker.reset();
        discardPrevious = true;
        return WorkloadAttribution.unavailable(
                status, collection.coverage(), collection.collectedAt());
    }

    private record Candidate(
            CgroupMemorySnapshot snapshot, double score, List<DiagnosticEvidence> evidence) {}
}
