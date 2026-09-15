package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.linux.cgroup.CgroupResourceSnapshot;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class PressureAttributor {

    private final double minimumScore;
    private final double dominanceRatio;
    private final long maximumAgeMillis;

    public PressureAttributor(double minimumScore, double dominanceRatio, Duration maximumAge) {
        if (!Double.isFinite(minimumScore) || minimumScore < 0) {
            throw new IllegalArgumentException("minimumScore must be finite and non-negative");
        }
        if (!Double.isFinite(dominanceRatio) || dominanceRatio <= 1) {
            throw new IllegalArgumentException("dominanceRatio must be greater than 1");
        }
        this.minimumScore = minimumScore;
        this.dominanceRatio = dominanceRatio;
        this.maximumAgeMillis = Objects.requireNonNull(maximumAge, "maximumAge").toMillis();
    }

    public WorkloadAttribution attribute(
            ResourceType resource, List<CgroupResourceSnapshot> snapshots, long now) {
        List<Ranked> ranked =
                snapshots.stream()
                        .filter(
                                snapshot ->
                                        snapshot.timestamp() <= now
                                                && now - snapshot.timestamp() <= maximumAgeMillis)
                        .map(snapshot -> new Ranked(snapshot, score(resource, snapshot)))
                        .filter(candidate -> candidate.score() >= minimumScore)
                        .sorted(Comparator.comparingDouble(Ranked::score).reversed())
                        .toList();
        if (ranked.isEmpty()) {
            return WorkloadAttribution.unresolved();
        }

        Ranked winner = ranked.getFirst();
        double runnerScore = ranked.size() > 1 ? ranked.get(1).score() : 0;
        double ratio = runnerScore == 0 ? Double.POSITIVE_INFINITY : winner.score() / runnerScore;
        if (ratio < dominanceRatio) {
            return WorkloadAttribution.unresolved();
        }

        WorkloadAttribution.Confidence confidence;
        if (ratio >= 2 && winner.score() >= 20) {
            confidence = WorkloadAttribution.Confidence.HIGH;
        } else if (ratio >= 1.5) {
            confidence = WorkloadAttribution.Confidence.MEDIUM;
        } else {
            confidence = WorkloadAttribution.Confidence.LOW;
        }
        return new WorkloadAttribution(winner.snapshot().workload(), confidence);
    }

    private static double score(ResourceType resource, CgroupResourceSnapshot snapshot) {
        return switch (resource) {
            case HOST_CPU ->
                    available(snapshot.cpuPsiSome()) * 1.5
                            + Math.min(available(snapshot.cpuUsage()), 400) * 0.05
                            + positive(snapshot.cpuPsiTrend()) * 0.5;
            case HOST_MEMORY -> memoryScore(snapshot);
            case DISK_IO ->
                    available(snapshot.ioPsiSome()) * 1.2
                            + available(snapshot.ioPsiFull()) * 2
                            + positive(snapshot.ioPsiTrend()) * 0.5;
            default -> 0;
        };
    }

    private static double memoryScore(CgroupResourceSnapshot snapshot) {
        double pressure =
                available(snapshot.memoryPsiSome()) * 1.2
                        + available(snapshot.memoryPsiFull()) * 2
                        + positive(snapshot.memoryPsiTrend()) * 0.5;
        return pressure == 0 ? 0 : pressure + memoryUtilization(snapshot) * 10;
    }

    private static double memoryUtilization(CgroupResourceSnapshot snapshot) {
        if (snapshot.memoryCurrent() < 0 || snapshot.memoryMax() <= 0) {
            return 0;
        }
        return Math.min(1, (double) snapshot.memoryCurrent() / snapshot.memoryMax());
    }

    private static double available(double value) {
        return Double.isFinite(value) ? value : 0;
    }

    private static double positive(double value) {
        return Math.max(0, value);
    }

    private record Ranked(CgroupResourceSnapshot snapshot, double score) {}
}
