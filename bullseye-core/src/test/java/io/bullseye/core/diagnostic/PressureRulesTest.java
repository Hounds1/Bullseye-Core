package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.diagnostic.rule.DiagnosticRule;
import io.bullseye.core.diagnostic.rule.IoPressureRule;
import io.bullseye.core.diagnostic.rule.MemoryPressureRule;
import io.bullseye.core.metric.InMemoryRollingMetricWindow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

class PressureRulesTest {

    private static final Duration FRESHNESS = Duration.ofSeconds(10);
    private InMemoryRollingMetricWindow window;
    private EnumMap<ResourceType, DiagnosticDecision> states;
    private MemoryPressureRule memoryRule;
    private IoPressureRule ioRule;

    @BeforeEach
    void setUp() {
        window = new InMemoryRollingMetricWindow(Duration.ofMinutes(2), Duration.ofSeconds(5));
        states = new EnumMap<>(ResourceType.class);
        memoryRule =
                new MemoryPressureRule(
                        BullseyeConfiguration.MemoryDiagnostics.defaults(), FRESHNESS);
        ioRule = new IoPressureRule(BullseyeConfiguration.IoDiagnostics.defaults(), FRESHNESS);
    }

    @Test
    void memoryNormalToElevatedRequiresUsageAndPsi() {
        memory(0, 81, 11, 0);
        memory(5_000, 82, 12, 0);
        memory(10_000, 83, 13, 0);

        assertEquals(Severity.ELEVATED, evaluate(memoryRule, 10_000).severity());
    }

    @Test
    void memoryElevatedToHighRequiresRisingPsi() {
        states.put(
                ResourceType.HOST_MEMORY,
                decision(ResourceType.HOST_MEMORY, Severity.ELEVATED, ResourceState.PRESSURE));
        memory(0, 86, 21, 0);
        memory(5_000, 87, 23, 0);
        memory(10_000, 88, 25, 0);

        assertEquals(Severity.HIGH, evaluate(memoryRule, 10_000).severity());
    }

    @Test
    void memoryHighToCriticalAcceptsFullPressure() {
        states.put(
                ResourceType.HOST_MEMORY,
                decision(ResourceType.HOST_MEMORY, Severity.HIGH, ResourceState.SATURATION_RISK));
        memory(0, 92, 25, 6);
        memory(5_000, 93, 26, 7);

        assertEquals(Severity.CRITICAL, evaluate(memoryRule, 5_000).severity());
    }

    @Test
    void memoryRecoversOnlyAfterSeparateStablePeriod() {
        states.put(
                ResourceType.HOST_MEMORY,
                decision(ResourceType.HOST_MEMORY, Severity.HIGH, ResourceState.SATURATION_RISK));
        for (long time = 0; time <= 30_000; time += 5_000) {
            memory(time, 65, 1, 0);
        }

        DiagnosticDecision recovered = evaluate(memoryRule, 30_000);

        assertEquals(Severity.NORMAL, recovered.severity());
        assertTrue(recovered.reason().startsWith("Memory pressure cleared."));
    }

    @Test
    void highMemoryUsageWithoutPsiIsObservationOnly() {
        append(MetricType.HOST_MEMORY_USAGE, 0, 95);
        append(MetricType.HOST_MEMORY_USAGE, 5_000, 96);
        append(MetricType.HOST_MEMORY_USAGE, 10_000, 97);

        assertTrue(evaluateOptional(memoryRule, 10_000).isEmpty());
    }

    @Test
    void transientMemoryPsiSpikeIsIgnored() {
        memory(0, 90, 0, 0);
        memory(5_000, 90, 35, 0);
        memory(10_000, 90, 0, 0);

        assertTrue(evaluateOptional(memoryRule, 10_000).isEmpty());
    }

    @Test
    void staleMemorySamplesCannotEscalate() {
        memory(0, 90, 30, 0);
        memory(5_000, 91, 35, 0);
        memory(10_000, 92, 40, 0);

        assertTrue(evaluateOptional(memoryRule, 20_001).isEmpty());
    }

    @Test
    void ioNormalToElevatedUsesSustainedSomePressure() {
        io(0, 11, 0);
        io(5_000, 12, 0);
        io(10_000, 13, 0);

        assertEquals(Severity.ELEVATED, evaluate(ioRule, 10_000).severity());
    }

    @Test
    void ioElevatedToHighAcceptsStrongSomePressure() {
        states.put(
                ResourceType.DISK_IO,
                decision(ResourceType.DISK_IO, Severity.ELEVATED, ResourceState.PRESSURE));
        io(0, 26, 0);
        io(5_000, 27, 0);
        io(10_000, 28, 0);

        assertEquals(Severity.HIGH, evaluate(ioRule, 10_000).severity());
    }

    @Test
    void ioHighToCriticalRequiresSustainedFullPressure() {
        states.put(
                ResourceType.DISK_IO,
                decision(ResourceType.DISK_IO, Severity.HIGH, ResourceState.SATURATION_RISK));
        io(0, 30, 11);
        io(5_000, 31, 12);

        assertEquals(Severity.CRITICAL, evaluate(ioRule, 5_000).severity());
    }

    @Test
    void ioRecoveryUsesIndependentThresholds() {
        states.put(
                ResourceType.DISK_IO,
                decision(ResourceType.DISK_IO, Severity.HIGH, ResourceState.SATURATION_RISK));
        for (long time = 0; time <= 30_000; time += 5_000) {
            io(time, 1, 0);
        }

        assertEquals(Severity.NORMAL, evaluate(ioRule, 30_000).severity());
    }

    @Test
    void transientIoSpikeIsIgnored() {
        io(0, 0, 0);
        io(5_000, 50, 20);
        io(10_000, 0, 0);

        assertTrue(evaluateOptional(ioRule, 10_000).isEmpty());
    }

    private DiagnosticDecision evaluate(DiagnosticRule rule, long now) {
        DiagnosticDecision decision = evaluateOptional(rule, now).orElseThrow();
        states.put(rule.resource(), decision);
        return decision;
    }

    private Optional<DiagnosticDecision> evaluateOptional(DiagnosticRule rule, long now) {
        return rule.evaluate(
                new DiagnosticContext(
                        window,
                        DiagnosticSnapshot.initial("host", "test", 0),
                        Map.copyOf(states),
                        now));
    }

    private void memory(long timestamp, double usage, double some, double full) {
        append(MetricType.HOST_MEMORY_USAGE, timestamp, usage);
        append(MetricType.HOST_MEMORY_PSI_SOME_AVG10, timestamp, some);
        append(MetricType.HOST_MEMORY_PSI_FULL_AVG10, timestamp, full);
    }

    private void io(long timestamp, double some, double full) {
        append(MetricType.HOST_IO_PSI_SOME_AVG10, timestamp, some);
        append(MetricType.HOST_IO_PSI_FULL_AVG10, timestamp, full);
    }

    private void append(MetricType type, long timestamp, double value) {
        window.append(new MetricSample(type, value, timestamp));
    }

    private static DiagnosticDecision decision(
            ResourceType resource, Severity severity, ResourceState state) {
        return new DiagnosticDecision(resource, state, severity, "retained");
    }
}
