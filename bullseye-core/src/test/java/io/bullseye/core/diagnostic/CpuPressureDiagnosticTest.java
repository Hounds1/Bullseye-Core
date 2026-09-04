package io.bullseye.core.diagnostic;

import io.bullseye.common.DiagnosticSnapshot;
import io.bullseye.common.MetricSample;
import io.bullseye.common.MetricType;
import io.bullseye.common.ResourceState;
import io.bullseye.common.ResourceType;
import io.bullseye.common.Severity;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.state.InMemoryDiagnosticStateRepository;
import io.bullseye.core.store.InMemoryRollingMetricWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpuPressureDiagnosticTest {

    private InMemoryRollingMetricWindow window;
    private InMemoryDiagnosticStateRepository repository;
    private DiagnosticCoordinator coordinator;

    @BeforeEach
    void setUp() {
        window = new InMemoryRollingMetricWindow(Duration.ofMinutes(2), Duration.ofSeconds(5));
        repository = new InMemoryDiagnosticStateRepository(
                DiagnosticSnapshot.initial("was-01", "orders", 0)
        );
        BullseyeConfiguration.CpuDiagnostics configuration =
                new BullseyeConfiguration.CpuDiagnostics(
                        70,
                        Duration.ofSeconds(10),
                        80,
                        Duration.ofSeconds(10),
                        0,
                        90,
                        Duration.ofSeconds(5),
                        65,
                        Duration.ofSeconds(30)
                );
        AtomicInteger eventSequence = new AtomicInteger();
        coordinator = new DiagnosticCoordinator(
                window,
                new DiagnosticEngine(List.of(
                        new CpuPressureRule(configuration, Duration.ofSeconds(10))
                )),
                new SeverityEvaluator(),
                repository,
                () -> "event-" + eventSequence.incrementAndGet()
        );
    }

    @Test
    void evaluatesEscalationAndRecoveryWithHysteresis() {
        append(0, 71);
        append(5_000, 73);
        append(10_000, 74);

        var elevated = coordinator.evaluate(10_000).orElseThrow();
        assertEquals(Severity.ELEVATED, elevated.current().severity());
        assertEquals(ResourceState.PRESSURE, elevated.current().state());
        assertEquals(2, elevated.current().version());
        assertEquals(
                "CPU pressure detected. usage=74.0% sustained=10s",
                elevated.event().reason()
        );

        append(15_000, 81);
        append(20_000, 83);
        append(25_000, 85);

        var high = coordinator.evaluate(25_000).orElseThrow();
        assertEquals(Severity.HIGH, high.current().severity());
        assertEquals(ResourceState.SATURATION_RISK, high.current().state());
        assertEquals(3, high.current().version());
        assertEquals(
                "Warning. CPU saturation risk detected. usage=85.0% rise=+4.0pp/10s",
                high.event().reason()
        );

        append(30_000, 75);
        assertTrue(coordinator.evaluate(30_000).isEmpty());
        assertEquals(Severity.HIGH, repository.current().severity());
        assertEquals(3, repository.current().version());

        append(35_000, 92);
        append(40_000, 93);

        var critical = coordinator.evaluate(40_000).orElseThrow();
        assertEquals(Severity.CRITICAL, critical.current().severity());
        assertEquals(ResourceState.SATURATED, critical.current().state());
        assertEquals(4, critical.current().version());
        assertEquals(
                "Critical CPU saturation detected. usage=93.0% sustained=5s",
                critical.event().reason()
        );

        for (long timestamp = 45_000; timestamp <= 75_000; timestamp += 5_000) {
            append(timestamp, 60);
        }

        var recovered = coordinator.evaluate(75_000).orElseThrow();
        assertEquals(Severity.NORMAL, recovered.current().severity());
        assertEquals(ResourceType.UNKNOWN, recovered.current().resource());
        assertEquals(5, recovered.current().version());
        assertEquals(
                "CPU pressure cleared. usage=60.0% sustained=30s",
                recovered.event().reason()
        );
    }

    @Test
    void staleSamplesDoNotCauseEscalation() {
        append(0, 95);
        append(5_000, 96);

        assertTrue(coordinator.evaluate(20_001).isEmpty());
        assertEquals(Severity.NORMAL, repository.current().severity());
    }

    private void append(long timestamp, double value) {
        window.append(new MetricSample(MetricType.HOST_CPU_USAGE, value, timestamp));
    }
}
