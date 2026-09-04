package io.bullseye.common;

import java.util.Objects;

public record MetricSample(MetricType type, double value, long timestamp) {

    public MetricSample {
        Objects.requireNonNull(type, "type");
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Metric value must be finite");
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("Metric timestamp must not be negative");
        }
    }
}
