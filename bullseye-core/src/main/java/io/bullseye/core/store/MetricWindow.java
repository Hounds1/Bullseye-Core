package io.bullseye.core.store;

import io.bullseye.common.MetricSample;
import io.bullseye.common.MetricType;

import java.util.List;

public interface MetricWindow {

    void append(MetricSample sample);

    List<MetricSample> get(MetricType type);

    List<MetricSample> get(MetricType type, long from, long to);
}
