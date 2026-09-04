package io.bullseye.core.collection;

import io.bullseye.common.MetricSample;

import java.util.Collection;

public interface MetricCollector {

    String name();

    Collection<MetricSample> collect() throws Exception;
}
