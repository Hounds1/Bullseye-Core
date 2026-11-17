package io.bullseye.common.workload;

import io.bullseye.common.diagnostic.DiagnosticEvidence;

import java.util.List;
import java.util.Objects;

public record WorkloadAttribution(
        WorkloadIdentity workload,
        double score,
        Confidence confidence,
        Status status,
        List<DiagnosticEvidence> evidence,
        Coverage coverage,
        Long collectedAt) {

    public record Coverage(
            int collectedGroups,
            int failedGroups,
            boolean discoveryFailed,
            boolean discoveryLimited) {

        public Coverage {
            if (collectedGroups < 0 || failedGroups < 0) {
                throw new IllegalArgumentException("cgroup counts must not be negative");
            }
        }

        public boolean complete() {
            return failedGroups == 0 && !discoveryFailed && !discoveryLimited;
        }

        public static Coverage complete(int groups) {
            return new Coverage(groups, 0, false, false);
        }
    }

    public enum Confidence {
        UNRESOLVED,
        LOW,
        MEDIUM,
        HIGH
    }

    public enum Status {
        NOT_ATTEMPTED,
        IDENTIFIED,
        CGROUP_V2_NOT_AVAILABLE,
        ACCESS_DENIED,
        NO_CANDIDATE,
        INSUFFICIENT_DATA
    }

    public WorkloadAttribution {
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(status, "status");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (collectedAt != null && collectedAt < 0) {
            throw new IllegalArgumentException("collectedAt must not be negative");
        }
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException("score must be finite and non-negative");
        }
        boolean identified = status == Status.IDENTIFIED;
        if (identified
                != (workload.type() != WorkloadIdentity.Type.UNKNOWN
                        && confidence != Confidence.UNRESOLVED)) {
            throw new IllegalArgumentException(
                    "identified attribution requires a workload and confidence");
        }
        if (!identified
                && (score != 0
                        || confidence != Confidence.UNRESOLVED
                        || workload.type() != WorkloadIdentity.Type.UNKNOWN
                        || !evidence.isEmpty())) {
            throw new IllegalArgumentException(
                    "unresolved attribution must not contain score or confidence");
        }
    }

    public WorkloadAttribution(
            WorkloadIdentity workload, double score, Confidence confidence, Status status) {
        this(workload, score, confidence, status, List.of(), null, null);
    }

    public static WorkloadAttribution unresolved() {
        return unavailable(Status.NOT_ATTEMPTED);
    }

    public static WorkloadAttribution identified(
            WorkloadIdentity workload, double score, Confidence confidence) {
        return new WorkloadAttribution(workload, score, confidence, Status.IDENTIFIED);
    }

    public static WorkloadAttribution identified(
            WorkloadIdentity workload,
            double score,
            Confidence confidence,
            List<DiagnosticEvidence> evidence,
            Coverage coverage,
            long collectedAt) {
        return new WorkloadAttribution(
                workload, score, confidence, Status.IDENTIFIED, evidence, coverage, collectedAt);
    }

    public static WorkloadAttribution unavailable(Status status) {
        return unavailable(status, null, null);
    }

    public static WorkloadAttribution unavailable(
            Status status, Coverage coverage, Long collectedAt) {
        if (status == Status.IDENTIFIED) {
            throw new IllegalArgumentException("identified status requires a workload");
        }
        return new WorkloadAttribution(
                WorkloadIdentity.unknown(), 0, Confidence.UNRESOLVED, status,
                List.of(), coverage, collectedAt);
    }

    public boolean resolved() {
        return status == Status.IDENTIFIED;
    }
}
