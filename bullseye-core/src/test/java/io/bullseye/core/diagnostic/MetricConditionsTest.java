package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.core.metric.InMemoryRollingMetricWindow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

class MetricConditionsTest {

    private static final MetricType TYPE = MetricType.HOST_CPU_USAGE;
    private static final Duration FRESHNESS = Duration.ofSeconds(10);
    private final InMemoryRollingMetricWindow window =
            new InMemoryRollingMetricWindow(Duration.ofMinutes(2), Duration.ofSeconds(5));

    @Test
    void acceptsContinuousSamplesWithinGapTolerance() {
        append(0, 80);
        append(5_000, 82);
        append(10_000, 84);

        assertTrue(aboveThreshold(10_000, 10));
    }

    @Test
    void acceptsGapExactlyAtTolerance() {
        append(0, 80);
        append(10_000, 82);

        assertTrue(aboveThreshold(10_000, 10));
    }

    @Test
    void gapBeyondToleranceCannotCauseEscalation() {
        append(0, 80);
        append(10_001, 82);

        assertFalse(aboveThreshold(10_001, 10));
    }

    @Test
    void resumesDurationAndTrendFromFirstPostGapObservation() {
        append(0, 75);
        append(5_000, 80);
        append(60_000, 81);
        append(65_000, 82);

        assertFalse(aboveThreshold(65_000, 10));

        append(70_000, 83);
        List<MetricSample> run =
                MetricConditions.sustainedRun(
                        window,
                        TYPE,
                        70_000,
                        Duration.ofSeconds(10),
                        FRESHNESS,
                        value -> value >= 70);

        assertEquals(
                List.of(60_000L, 65_000L, 70_000L),
                run.stream().map(MetricSample::timestamp).toList());
        assertEquals(2, MetricConditions.rise(run));
    }

    @Test
    void outageCannotCompleteRecoveryDuration() {
        append(0, 60);
        append(5_000, 60);
        append(60_000, 60);

        assertFalse(recovered(60_000));

        for (long timestamp = 65_000; timestamp <= 90_000; timestamp += 5_000) {
            append(timestamp, 60);
        }

        assertTrue(recovered(90_000));
    }

    @Test
    void freshLastSampleDoesNotExtendObservedDuration() {
        append(0, 80);
        append(5_000, 82);

        assertFalse(aboveThreshold(10_000, 10));
        assertTrue(aboveThreshold(10_000, 5));
    }

    @Test
    void staleAndFutureSamplesCannotSatisfyCondition() {
        append(0, 80);
        append(5_000, 82);

        assertFalse(aboveThreshold(15_001, 5));
        assertFalse(aboveThreshold(4_999, 5));
    }

    @Test
    void thresholdFailureBreaksOtherwiseContinuousRun() {
        append(0, 80);
        append(5_000, 50);
        append(10_000, 82);

        assertFalse(aboveThreshold(10_000, 10));
    }

    private boolean aboveThreshold(long now, long seconds) {
        return MetricConditions.sustained(
                window, TYPE, now, Duration.ofSeconds(seconds), FRESHNESS, value -> value >= 70);
    }

    private boolean recovered(long now) {
        return MetricConditions.sustained(
                window, TYPE, now, Duration.ofSeconds(30), FRESHNESS, value -> value <= 65);
    }

    private void append(long timestamp, double value) {
        window.append(new MetricSample(TYPE, value, timestamp));
    }
}
