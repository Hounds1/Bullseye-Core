package io.bullseye.core.metric;

import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
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
        requireTimestampOrder(sample, latestTimestamp(sample.type()));
        appendOrdered(sample);
    }

    @Override
    public synchronized void appendAll(Collection<MetricSample> incoming) {
        List<MetricSample> batch = List.copyOf(incoming);
        Map<MetricType, Long> batchTimestamps = new EnumMap<>(MetricType.class);
        for (MetricSample sample : batch) {
            Long previous = batchTimestamps.put(sample.type(), sample.timestamp());
            long previousTimestamp = previous == null ? latestTimestamp(sample.type()) : previous;
            requireTimestampOrder(sample, previousTimestamp);
        }
        batch.forEach(this::appendOrdered);
    }

    private long latestTimestamp(MetricType type) {
        ArrayDeque<MetricSample> metricSamples = samples.get(type);
        return metricSamples == null ? -1 : metricSamples.getLast().timestamp();
    }

    private static void requireTimestampOrder(MetricSample sample, long previousTimestamp) {
        if (sample.timestamp() < previousTimestamp) {
            throw new IllegalArgumentException("Metric samples must be appended in timestamp order");
        }
    }

    private void appendOrdered(MetricSample sample) {
        ArrayDeque<MetricSample> metricSamples =
                samples.computeIfAbsent(sample.type(), ignored -> new ArrayDeque<>());
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
    public synchronized MetricView snapshot(long evaluatedAt) {
        Map<MetricType, List<MetricSample>> captured = new EnumMap<>(MetricType.class);
        samples.forEach((type, values) -> captured.put(type, values.stream()
                .takeWhile(sample -> sample.timestamp() <= evaluatedAt)
                .toList()));
        Map<MetricType, List<MetricSample>> snapshot = Map.copyOf(captured);
        return type -> snapshot.getOrDefault(Objects.requireNonNull(type, "type"), List.of());
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative() || duration.toMillis() == 0) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
    }
}
