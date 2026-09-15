package io.bullseye.core.metric;

import io.bullseye.common.metric.MetricSample;

import java.util.Collection;

public interface MetricCollector {

    String name();

    Collection<MetricSample> collect() throws Exception;
}
