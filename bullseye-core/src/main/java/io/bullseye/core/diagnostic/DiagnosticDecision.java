package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;

import java.util.List;
import java.util.Objects;

public record DiagnosticDecision(
        ResourceType resource,
        ResourceState state,
        Severity severity,
        String reason,
        List<DiagnosticEvidence> evidence,
        double pressureScore) {

    public DiagnosticDecision {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(severity, "severity");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (!Double.isFinite(pressureScore) || pressureScore < 0) {
            throw new IllegalArgumentException("pressureScore must be finite and non-negative");
        }
    }

    public DiagnosticDecision(
            ResourceType resource, ResourceState state, Severity severity, String reason) {
        this(resource, state, severity, reason, List.of(), 0);
    }

    public static DiagnosticDecision normal(ResourceType resource, String reason) {
        return new DiagnosticDecision(
                resource, ResourceState.NORMAL, Severity.NORMAL, reason, List.of(), 0);
    }

    public static DiagnosticDecision normal(String reason) {
        return normal(ResourceType.UNKNOWN, reason);
    }
}
