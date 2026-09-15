package io.bullseye.common.diagnostic;

import java.util.Objects;

public record DiagnosticEvidence(String metric, double value, String unit) {

    public DiagnosticEvidence {
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("metric must not be blank");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
        Objects.requireNonNull(unit, "unit");
    }
}
