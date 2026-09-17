package io.bullseye.nativehost;

import io.bullseye.common.metric.MetricType;

import java.util.Map;
import java.util.Objects;

public record HostNativeSnapshot(long capturedAt, Map<MetricType, Double> metrics) {

    public HostNativeSnapshot {
        if (capturedAt < 0) {
            throw new IllegalArgumentException("capturedAt must not be negative");
        }
        metrics = Map.copyOf(Objects.requireNonNull(metrics, "metrics"));
    }
}
