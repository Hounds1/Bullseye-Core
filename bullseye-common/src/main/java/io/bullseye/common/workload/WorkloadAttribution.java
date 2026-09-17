package io.bullseye.common.workload;

import java.util.Objects;

public record WorkloadAttribution(WorkloadIdentity workload, Confidence confidence) {

    public enum Confidence {
        UNRESOLVED,
        LOW,
        MEDIUM,
        HIGH
    }

    public WorkloadAttribution {
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(confidence, "confidence");
        if ((workload.type() == WorkloadIdentity.Type.UNKNOWN)
                != (confidence == Confidence.UNRESOLVED)) {
            throw new IllegalArgumentException(
                    "UNKNOWN workload and UNRESOLVED confidence must match");
        }
    }

    public static WorkloadAttribution unresolved() {
        return new WorkloadAttribution(WorkloadIdentity.unknown(), Confidence.UNRESOLVED);
    }

    public boolean resolved() {
        return confidence != Confidence.UNRESOLVED;
    }
}
