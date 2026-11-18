package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.workload.WorkloadAttribution.Confidence;
import io.bullseye.common.workload.WorkloadIdentity;
import io.bullseye.core.config.BullseyeConfiguration.AttributionConfidence;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

final class AttributionConfidenceTracker {

    private final AttributionConfidence configuration;
    private final double dominanceRatio;
    private final long maximumGapMillis;
    private WorkloadIdentity candidate;
    private Confidence eligibleConfidence = Confidence.LOW;
    private long firstObservedAt;
    private long lastObservedAt;
    private int observations;
    private int confirmationsRemaining;

    AttributionConfidenceTracker(
            AttributionConfidence configuration,
            double dominanceRatio,
            Duration maximumObservationGap) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.dominanceRatio = dominanceRatio;
        Objects.requireNonNull(maximumObservationGap, "maximumObservationGap");
        maximumGapMillis = maximumObservationGap.toMillis();
        if (maximumGapMillis < 1) {
            throw new IllegalArgumentException("maximumObservationGap must be at least 1ms");
        }
    }

    Confidence observe(
            WorkloadIdentity workload,
            double score,
            double runnerScore,
            long collectedAt,
            Long intervalStartedAt,
            boolean reliable,
            List<DiagnosticEvidence> evidence) {
        boolean confirmation = needsConfirmation();
        if (confirmation) {
            confirmationsRemaining--;
        }
        double margin = score - runnerScore;
        Confidence eligible = eligibleConfidence(score, runnerScore, margin);
        if (!reliable) {
            reset();
        } else if (observations > 0 && collectedAt <= lastObservedAt) {
            // A replay cannot add evidence or restart the confirmation budget.
            if (collectedAt < lastObservedAt
                    || !workload.equals(candidate)
                    || eligible != eligibleConfidence) {
                reset();
            }
        } else {
            boolean continuous = observations > 0
                    && workload.equals(candidate)
                    && eligible == eligibleConfidence
                    && collectedAt - lastObservedAt <= maximumGapMillis
                    && (intervalStartedAt == null || intervalStartedAt == lastObservedAt);
            if (!continuous) {
                candidate = workload;
                eligibleConfidence = eligible;
                firstObservedAt = collectedAt;
                observations = 0;
                // Candidate churn during a confirmation round cannot replenish its budget.
                if (!confirmation) {
                    confirmationsRemaining = configuration.maximumConfirmationCollections();
                }
            }
            lastObservedAt = collectedAt;
            if (observations < Integer.MAX_VALUE) {
                observations++;
            }
        }

        Confidence result = confirmed() ? eligibleConfidence : Confidence.LOW;
        if (eligibleConfidence == Confidence.LOW || confirmed()) {
            confirmationsRemaining = 0;
        }
        evidence.add(metric("confidence.observations", observations, "count"));
        evidence.add(metric("confidence.duration", observationMillis() / 1_000.0, "s"));
        evidence.add(metric("confidence.margin", margin, "points"));
        evidence.add(metric("confidence.runner-score", runnerScore, "points"));
        evidence.add(metric("confidence.confirmations-remaining", confirmationsRemaining, "count"));
        return result;
    }

    boolean needsConfirmation() {
        return confirmationsRemaining > 0 && eligibleConfidence != Confidence.LOW && !confirmed();
    }

    void reset() {
        candidate = null;
        eligibleConfidence = Confidence.LOW;
        firstObservedAt = 0;
        lastObservedAt = 0;
        observations = 0;
        confirmationsRemaining = 0;
    }

    private Confidence eligibleConfidence(double score, double runnerScore, double margin) {
        if (margin < configuration.minimumMargin()) {
            return Confidence.LOW;
        }
        double ratio = runnerScore == 0 ? Double.POSITIVE_INFINITY : score / runnerScore;
        if (score >= 45 && ratio >= Math.max(2, dominanceRatio)) {
            return Confidence.HIGH;
        }
        if (score >= 20 && ratio >= dominanceRatio) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }

    private boolean confirmed() {
        return observations >= configuration.minimumObservations()
                && observationMillis() >= configuration.minimumDuration().toMillis();
    }

    private long observationMillis() {
        return observations == 0 ? 0 : lastObservedAt - firstObservedAt;
    }

    private static DiagnosticEvidence metric(String name, double value, String unit) {
        return new DiagnosticEvidence(name, value, unit);
    }
}
