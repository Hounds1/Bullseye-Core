package io.bullseye.common.workload;

import java.util.Objects;

public record WorkloadAttribution(
        WorkloadIdentity workload, double score, Confidence confidence, Status status) {

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
        if (!identified && (score != 0 || confidence != Confidence.UNRESOLVED)) {
            throw new IllegalArgumentException(
                    "unresolved attribution must not contain score or confidence");
        }
    }

    public static WorkloadAttribution unresolved() {
        return unavailable(Status.NOT_ATTEMPTED);
    }

    public static WorkloadAttribution identified(
            WorkloadIdentity workload, double score, Confidence confidence) {
        return new WorkloadAttribution(workload, score, confidence, Status.IDENTIFIED);
    }

    public static WorkloadAttribution unavailable(Status status) {
        if (status == Status.IDENTIFIED) {
            throw new IllegalArgumentException("identified status requires a workload");
        }
        return new WorkloadAttribution(
                WorkloadIdentity.unknown(), 0, Confidence.UNRESOLVED, status);
    }

    public boolean resolved() {
        return status == Status.IDENTIFIED;
    }
}
