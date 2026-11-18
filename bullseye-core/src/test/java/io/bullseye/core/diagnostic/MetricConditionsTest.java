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

    @Test
    void recentDeclineDoesNotIncludeOlderRise() {
        append(0, 80);
        append(5_000, 88);
        append(10_000, 87);
        append(15_000, 86);
        append(20_000, 85);

        List<MetricSample> recent = recentRun(20_000, 10);

        assertEquals(
                List.of(10_000L, 15_000L, 20_000L),
                recent.stream().map(MetricSample::timestamp).toList());
        assertEquals(-2, MetricConditions.rise(recent));
        assertEquals(Duration.ofSeconds(10), MetricConditions.observedDuration(recent));
        assertEquals(5, MetricConditions.rise(MetricConditions.sustainedRun(
                window, TYPE, 20_000, Duration.ofSeconds(10), FRESHNESS, value -> value >= 70)));
    }

    @Test
    void recentPlateauDoesNotReuseAnOlderIncrease() {
        append(0, 80);
        append(5_000, 85);
        append(10_000, 85);
        append(15_000, 85);
        append(20_000, 85);

        assertEquals(0, MetricConditions.rise(recentRun(20_000, 10)));
    }

    @Test
    void usesNearestObservedBoundaryWhenSamplingDoesNotAlignWithDuration() {
        append(0, 70);
        append(6_000, 80);
        append(12_000, 82);
        append(18_000, 84);
        append(24_000, 86);

        List<MetricSample> recent = recentRun(24_000, 10);

        assertEquals(
                List.of(12_000L, 18_000L, 24_000L),
                recent.stream().map(MetricSample::timestamp).toList());
        assertEquals(4, MetricConditions.rise(recent));
        assertEquals(Duration.ofSeconds(12), MetricConditions.observedDuration(recent));
    }

    @Test
    void delayedEvaluationKeepsTrendAnchoredToLatestObservation() {
        append(0, 70);
        append(5_000, 80);
        append(10_000, 82);
        append(15_000, 84);
        append(20_000, 86);

        List<MetricSample> recent = recentRun(25_000, 10);

        assertEquals(10_000, recent.getFirst().timestamp());
        assertEquals(20_000, recent.getLast().timestamp());
        assertEquals(Duration.ofSeconds(10), MetricConditions.observedDuration(recent));
    }

    @Test
    void recentTrendRejectsAbsentStaleFutureAndIncompleteObservations() {
        assertTrue(recentRun(0, 10).isEmpty());
        append(0, 80);
        append(5_000, 82);

        assertTrue(recentRun(10_000, 10).isEmpty());
        assertTrue(recentRun(15_001, 5).isEmpty());
        assertTrue(recentRun(4_999, 5).isEmpty());
        assertEquals(Duration.ZERO, MetricConditions.observedDuration(List.of()));
    }

    @Test
    void recentTrendCannotBridgeACollectionGapOrThresholdFailure() {
        append(0, 70);
        append(5_000, 75);
        append(30_000, 80);
        append(35_000, 82);
        assertTrue(recentRun(35_000, 10).isEmpty());

        append(40_000, 84);
        assertEquals(30_000, recentRun(40_000, 10).getFirst().timestamp());

        append(45_000, 60);
        append(50_000, 85);
        assertTrue(recentRun(50_000, 10).isEmpty());
    }

    @Test
    void repeatedTimestampsDoNotInventObservedTime() {
        append(5_000, 80);
        append(5_000, 82);

        assertTrue(recentRun(5_000, 1).isEmpty());
        assertEquals(Duration.ZERO, MetricConditions.observedDuration(window.get(TYPE)));
    }

    @Test
    void absentOptionalMetricDiffersFromAnExpiredObservedMetric() {
        assertFalse(MetricConditions.hasExpired(window, TYPE, 100_000, FRESHNESS));
        append(100_000, 80);

        assertFalse(MetricConditions.hasExpired(window, TYPE, 110_000, FRESHNESS));
        assertTrue(MetricConditions.hasExpired(window, TYPE, 110_001, FRESHNESS));

        append(115_000, 82);
        assertFalse(MetricConditions.hasExpired(window, TYPE, 115_000, FRESHNESS));
        assertTrue(MetricConditions.hasExpired(window, TYPE, 114_999, FRESHNESS));
    }

    @Test
    void durationFormattingRetainsFractionalObservedSeconds() {
        assertEquals("10.127s", MetricConditions.duration(Duration.ofMillis(10_127)));
        assertEquals("60.5s", MetricConditions.duration(Duration.ofMillis(60_500)));
        assertEquals("0.001s", MetricConditions.duration(Duration.ofMillis(1)));
        assertEquals("10s", MetricConditions.duration(Duration.ofSeconds(10)));
        assertEquals("2m", MetricConditions.duration(Duration.ofMinutes(2)));
    }

    private List<MetricSample> recentRun(long now, long seconds) {
        return MetricConditions.recentRun(
                window, TYPE, now, Duration.ofSeconds(seconds), FRESHNESS, value -> value >= 70);
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
