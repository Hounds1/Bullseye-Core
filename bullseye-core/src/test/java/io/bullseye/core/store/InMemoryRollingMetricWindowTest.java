package io.bullseye.core.store;

import io.bullseye.common.MetricSample;
import io.bullseye.common.MetricType;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryRollingMetricWindowTest {

    private final InMemoryRollingMetricWindow window = new InMemoryRollingMetricWindow(
            Duration.ofSeconds(10),
            Duration.ofSeconds(1)
    );

    @Test
    void appendsSamples() {
        MetricSample sample = sample(MetricType.HOST_CPU_USAGE, 42, 1_000);

        window.append(sample);

        assertEquals(java.util.List.of(sample), window.get(MetricType.HOST_CPU_USAGE));
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
                () -> window.append(sample(MetricType.HOST_CPU_USAGE, 20, 1_000))
        );
    }

    private static MetricSample sample(MetricType type, double value, long timestamp) {
        return new MetricSample(type, value, timestamp);
    }
}
