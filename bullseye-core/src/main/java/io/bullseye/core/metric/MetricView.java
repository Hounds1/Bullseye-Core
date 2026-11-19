package io.bullseye.core.metric;

import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;

import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface MetricView {

    List<MetricSample> get(MetricType type);

    default List<MetricSample> get(MetricType type, long from, long to) {
        Objects.requireNonNull(type, "type");
        if (from > to) {
            throw new IllegalArgumentException("from must not be greater than to");
        }
        return get(type).stream()
                .filter(sample -> sample.timestamp() >= from && sample.timestamp() <= to)
                .toList();
    }
}
