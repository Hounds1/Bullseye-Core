package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.diagnostic.rule.CpuPressureRule;
import io.bullseye.core.metric.InMemoryRollingMetricWindow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

class CpuPsiRuleTest {

    @Test
    void highUsageWithCpuPsiConfirmsHighPressure() {
        var harness = new Harness();
        harness.sample(0, 82, 8);
        harness.sample(5_000, 84, 9);
        harness.sample(10_000, 86, 10);

        DiagnosticDecision decision = harness.evaluate(10_000);

        assertEquals(Severity.HIGH, decision.severity());
        assertTrue(decision.reason().contains("psi.some=10.0%"));
    }

    @Test
    void highUsageWithLowCpuPsiIsSoftened() {
        var harness = new Harness();
        harness.sample(0, 82, 0);
        harness.sample(5_000, 84, 0);
        harness.sample(10_000, 86, 0);

        DiagnosticDecision decision = harness.evaluate(10_000);

        assertEquals(Severity.ELEVATED, decision.severity());
        assertTrue(
                decision.reason()
                        .startsWith("CPU utilization elevated without PSI corroboration."));
    }

    @Test
    void unavailableCpuPsiPreservesLegacyUsageDecision() {
        var harness = new Harness();
        harness.usage(0, 82);
        harness.usage(5_000, 84);
        harness.usage(10_000, 86);

        assertEquals(Severity.HIGH, harness.evaluate(10_000).severity());
    }

    private static final class Harness {
        private final InMemoryRollingMetricWindow window =
                new InMemoryRollingMetricWindow(Duration.ofMinutes(1), Duration.ofSeconds(5));
        private final CpuPressureRule rule =
                new CpuPressureRule(
                        new BullseyeConfiguration.CpuDiagnostics(
                                70,
                                Duration.ofSeconds(10),
                                80,
                                Duration.ofSeconds(10),
                                0,
                                90,
                                Duration.ofSeconds(5),
                                65,
                                Duration.ofSeconds(30),
                                5,
                                1),
                        Duration.ofSeconds(10));

        private void sample(long timestamp, double usage, double psi) {
            usage(timestamp, usage);
            window.append(new MetricSample(MetricType.HOST_CPU_PSI_SOME_AVG10, psi, timestamp));
        }

        private void usage(long timestamp, double usage) {
            window.append(new MetricSample(MetricType.HOST_CPU_USAGE, usage, timestamp));
        }

        private DiagnosticDecision evaluate(long now) {
            return rule.evaluate(
                            new DiagnosticContext(
                                    window,
                                    DiagnosticSnapshot.initial("host", "test", 0),
                                    Map.of(
                                            ResourceType.HOST_CPU,
                                            DiagnosticDecision.normal(
                                                    ResourceType.HOST_CPU, "normal")),
                                    now))
                    .orElseThrow();
        }
    }
}
