package io.bullseye.core.metric;

import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;

import java.util.List;

public interface MetricWindow {

    void append(MetricSample sample);

    List<MetricSample> get(MetricType type);

    List<MetricSample> get(MetricType type, long from, long to);
}
