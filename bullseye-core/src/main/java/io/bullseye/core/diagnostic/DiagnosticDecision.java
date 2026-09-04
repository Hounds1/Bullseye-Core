package io.bullseye.core.diagnostic;

import io.bullseye.common.ResourceState;
import io.bullseye.common.ResourceType;
import io.bullseye.common.Severity;

import java.util.Objects;

public record DiagnosticDecision(
        ResourceType resource,
        ResourceState state,
        Severity severity,
        String reason
) {

    public DiagnosticDecision {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(severity, "severity");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    public static DiagnosticDecision normal(String reason) {
        return new DiagnosticDecision(
                ResourceType.UNKNOWN,
                ResourceState.NORMAL,
                Severity.NORMAL,
                reason
        );
    }
}
