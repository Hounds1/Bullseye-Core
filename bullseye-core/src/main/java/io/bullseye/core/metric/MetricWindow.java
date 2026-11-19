package io.bullseye.core.metric;

import io.bullseye.common.metric.MetricSample;

import java.util.Collection;

public interface MetricWindow extends MetricView {

    void append(MetricSample sample);

    /** Appends one collection cycle atomically. Invalid samples leave the window unchanged. */
    void appendAll(Collection<MetricSample> samples);

    /** Captures the retained samples through the given timestamp, inclusive. */
    MetricView snapshot(long evaluatedAt);
}
