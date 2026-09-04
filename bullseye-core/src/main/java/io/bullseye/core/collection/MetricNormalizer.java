package io.bullseye.core.collection;

import io.bullseye.common.MetricSample;

import java.util.Collection;

public interface MetricNormalizer {

    Collection<MetricSample> normalize(Collection<MetricSample> samples);
}
