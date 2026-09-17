package io.bullseye.core.metric;

import io.bullseye.common.metric.MetricSample;

import java.util.Collection;

public interface MetricNormalizer {

    Collection<MetricSample> normalize(Collection<MetricSample> samples);
}
