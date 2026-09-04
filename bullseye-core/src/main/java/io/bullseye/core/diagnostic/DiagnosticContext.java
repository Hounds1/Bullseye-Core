package io.bullseye.core.diagnostic;

import io.bullseye.common.DiagnosticSnapshot;
import io.bullseye.core.store.MetricWindow;

import java.util.Objects;

public record DiagnosticContext(
        MetricWindow metrics,
        DiagnosticSnapshot currentState,
        long evaluatedAt
) {

    public DiagnosticContext {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(currentState, "currentState");
        if (evaluatedAt < 0) {
            throw new IllegalArgumentException("evaluatedAt must not be negative");
        }
    }
}
