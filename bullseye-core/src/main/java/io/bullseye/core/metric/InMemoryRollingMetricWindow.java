package io.bullseye.core.metric;

import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryRollingMetricWindow implements MetricWindow {

    private final long windowMillis;
    private final int maximumSamplesPerMetric;
    private final Map<MetricType, ArrayDeque<MetricSample>> samples =
            new EnumMap<>(MetricType.class);

    public InMemoryRollingMetricWindow(Duration windowDuration, Duration samplingInterval) {
        requirePositive(windowDuration, "windowDuration");
        requirePositive(samplingInterval, "samplingInterval");
        this.windowMillis = windowDuration.toMillis();
        long estimatedSamples = Math.ceilDiv(windowMillis, samplingInterval.toMillis()) + 2;
        this.maximumSamplesPerMetric =
                Math.toIntExact(Math.min(estimatedSamples, Integer.MAX_VALUE));
    }

    @Override
    public synchronized void append(MetricSample sample) {
        Objects.requireNonNull(sample, "sample");
        ArrayDeque<MetricSample> metricSamples =
                samples.computeIfAbsent(sample.type(), ignored -> new ArrayDeque<>());
        MetricSample latest = metricSamples.peekLast();
        if (latest != null && sample.timestamp() < latest.timestamp()) {
            throw new IllegalArgumentException(
                    "Metric samples must be appended in timestamp order");
        }

        metricSamples.addLast(sample);
        long cutoff = sample.timestamp() - windowMillis;
        while (!metricSamples.isEmpty() && metricSamples.peekFirst().timestamp() < cutoff) {
            metricSamples.removeFirst();
        }
        while (metricSamples.size() > maximumSamplesPerMetric) {
            metricSamples.removeFirst();
        }
    }

    @Override
    public synchronized List<MetricSample> get(MetricType type) {
        Objects.requireNonNull(type, "type");
        ArrayDeque<MetricSample> metricSamples = samples.get(type);
        return metricSamples == null ? List.of() : List.copyOf(metricSamples);
    }

    @Override
    public synchronized List<MetricSample> get(MetricType type, long from, long to) {
        Objects.requireNonNull(type, "type");
        if (from > to) {
            throw new IllegalArgumentException("from must not be greater than to");
        }
        ArrayDeque<MetricSample> metricSamples = samples.get(type);
        if (metricSamples == null) {
            return List.of();
        }

        List<MetricSample> result = new ArrayList<>();
        for (MetricSample sample : metricSamples) {
            if (sample.timestamp() >= from && sample.timestamp() <= to) {
                result.add(sample);
            }
        }
        return List.copyOf(result);
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative() || duration.toMillis() == 0) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
    }
}
