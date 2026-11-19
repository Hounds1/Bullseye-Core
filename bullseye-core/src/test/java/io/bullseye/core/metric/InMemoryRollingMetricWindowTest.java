package io.bullseye.core.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

class InMemoryRollingMetricWindowTest {

    private final InMemoryRollingMetricWindow window =
            new InMemoryRollingMetricWindow(Duration.ofSeconds(10), Duration.ofSeconds(1));

    @Test
    void appendsSamples() {
        MetricSample sample = sample(MetricType.HOST_CPU_USAGE, 42, 1_000);

        window.append(sample);

        assertEquals(List.of(sample), window.get(MetricType.HOST_CPU_USAGE));
    }

    @Test
    void removesSamplesOutsideWindow() {
        window.append(sample(MetricType.HOST_CPU_USAGE, 10, 0));
        window.append(sample(MetricType.HOST_CPU_USAGE, 20, 10_000));
        window.append(sample(MetricType.HOST_CPU_USAGE, 30, 10_001));

        assertEquals(2, window.get(MetricType.HOST_CPU_USAGE).size());
        assertEquals(20, window.get(MetricType.HOST_CPU_USAGE).getFirst().value());
    }

    @Test
    void separatesMetricTypes() {
        window.append(sample(MetricType.HOST_CPU_USAGE, 20, 1_000));
        window.append(sample(MetricType.HOST_MEMORY_USAGE, 70, 1_000));

        assertEquals(1, window.get(MetricType.HOST_CPU_USAGE).size());
        assertEquals(1, window.get(MetricType.HOST_MEMORY_USAGE).size());
        assertEquals(70, window.get(MetricType.HOST_MEMORY_USAGE).getFirst().value());
    }

    @Test
    void returnsRequestedPeriodOnly() {
        window.append(sample(MetricType.HOST_CPU_USAGE, 10, 1_000));
        window.append(sample(MetricType.HOST_CPU_USAGE, 20, 2_000));
        window.append(sample(MetricType.HOST_CPU_USAGE, 30, 3_000));

        var result = window.get(MetricType.HOST_CPU_USAGE, 1_500, 2_500);

        assertEquals(1, result.size());
        assertEquals(20, result.getFirst().value());
    }

    @Test
    void rejectsOutOfOrderSamples() {
        window.append(sample(MetricType.HOST_CPU_USAGE, 10, 2_000));

        assertThrows(
                IllegalArgumentException.class,
                () -> window.append(sample(MetricType.HOST_CPU_USAGE, 20, 1_000)));
    }

    @Test
    void rejectsEntireBatchIfAnyTimestampIsOutOfOrder() {
        MetricType cpu = MetricType.HOST_CPU_USAGE;
        MetricType memory = MetricType.HOST_MEMORY_USAGE;
        MetricSample existingCpu = sample(cpu, 40, 2_000);
        window.append(existingCpu);

        assertThrows(IllegalArgumentException.class, () -> window.appendAll(List.of(
                sample(memory, 30, 3_000), sample(cpu, 45, 1_000))));
        assertThrows(IllegalArgumentException.class, () -> window.appendAll(List.of(
                sample(memory, 30, 3_000), sample(memory, 35, 2_000))));

        assertEquals(List.of(existingCpu), window.get(cpu));
        assertEquals(List.of(), window.get(memory));
    }

    @Test
    void snapshotIsImmutableAndIncludesOnlySamplesThroughEvaluationTime() {
        MetricType cpu = MetricType.HOST_CPU_USAGE;
        MetricType memory = MetricType.HOST_MEMORY_USAGE;
        MetricSample olderMemory = sample(memory, 50, 0);
        MetricSample cpuAtEvaluation = sample(cpu, 40, 2_000);
        MetricSample futureCpu = sample(cpu, 45, 3_000);
        window.appendAll(List.of(olderMemory, cpuAtEvaluation, futureCpu));

        MetricView captured = window.snapshot(2_000);
        window.appendAll(List.of(sample(cpu, 60, 15_000), sample(memory, 65, 15_000)));

        assertEquals(List.of(cpuAtEvaluation), captured.get(cpu));
        assertEquals(List.of(olderMemory), captured.get(memory));
        assertEquals(List.of(cpuAtEvaluation), captured.get(cpu, 1_000, 2_000));
        assertThrows(UnsupportedOperationException.class, () -> captured.get(cpu).add(futureCpu));
    }

    private static MetricSample sample(MetricType type, double value, long timestamp) {
        return new MetricSample(type, value, timestamp);
    }
}
