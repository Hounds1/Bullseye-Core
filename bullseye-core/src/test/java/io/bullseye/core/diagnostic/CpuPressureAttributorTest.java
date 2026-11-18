package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.common.workload.WorkloadAttribution.Confidence;
import io.bullseye.common.workload.WorkloadAttribution.Coverage;
import io.bullseye.common.workload.WorkloadAttribution.Status;
import io.bullseye.common.workload.WorkloadIdentity;
import io.bullseye.core.config.BullseyeConfiguration.AttributionConfidence;
import io.bullseye.core.linux.cgroup.CgroupCpuSnapshot;
import io.bullseye.core.linux.cgroup.CgroupMonitor;
import io.bullseye.core.linux.cgroup.CgroupMonitor.CollectionResult;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class CpuPressureAttributorTest {

    private static final Duration MAXIMUM_GAP = Duration.ofSeconds(32);

    @Test
    void identifiesLargestHostContributorWithMulticoreNormalization() {
        var worker = snapshot("worker.service", 3_000_000, 1_000);
        var other = snapshot("other.service", 1_500_000, 1_000);
        var result = attribute(
                List.of(worker, other),
                Map.of(
                        path("worker.service"), snapshot("worker.service", 1_000_000, 0),
                        path("other.service"), snapshot("other.service", 1_000_000, 0)),
                4);

        assertEquals("worker.service", result.workload().name());
        assertEquals(50, result.score());
        assertEquals(Confidence.LOW, result.confidence());
        assertEquals(2, value(result, "cpu.usage.cores"));
        assertEquals(4, value(result, "host.cpu.count"));
        assertEquals(50, value(result, "host.share"));
        assertEquals(2_000_000, value(result, "usage.delta"));
        assertEquals(1_600_000, value(result, "user.delta"));
        assertEquals(400_000, value(result, "system.delta"));
        assertEquals(1_000, value(result, "observation.duration"));
        assertEquals(result.score(), value(result, "score.usage"));
        assertEquals(2, value(result, "candidates.measured"));
        assertEquals(0, value(result, "candidates.unmeasured"));
    }

    @Test
    void localQuotaSaturationAndPressureDoNotOutrankActualCpuConsumption() {
        var limitedBefore = detailed("limited.service", 0, 0, 10_000L, 0L, 0L, 0L, 99);
        var limited = detailed(
                "limited.service", 100_000, 1_000, 10_000L, 10L, 10L, 900_000L, 99);
        var workerBefore = snapshot("worker.service", 0, 0);
        var worker = snapshot("worker.service", 2_000_000, 1_000);

        var result = attribute(
                List.of(limited, worker),
                Map.of(
                        path("limited.service"), limitedBefore,
                        path("worker.service"), workerBefore),
                4);

        assertEquals("worker.service", result.workload().name());
        assertEquals(50, result.score());
        assertEquals(Confidence.LOW, result.confidence());
    }

    @Test
    void exposesQuotaAndThrottlingAsSupplementaryEvidence() {
        var before = detailed("api.service", 1_000_000, 0, 100_000L, 50L, 2L, 10_000L, 0);
        var current = detailed(
                "api.service", 1_500_000, 1_000, 100_000L, 60L, 5L, 70_000L, 40);
        var result = attribute(List.of(current), Map.of(path("api.service"), before), 4);

        assertEquals(12.5, result.score());
        assertEquals(1, value(result, "quota.cores"));
        assertEquals(50, value(result, "quota.usage"));
        assertEquals(10, value(result, "throttling.periods.delta"));
        assertEquals(3, value(result, "throttling.throttled-periods.delta"));
        assertEquals(60_000, value(result, "throttling.duration.delta"));
        assertEquals(40, value(result, "psi.some.avg10"));
    }

    @Test
    void firstObservationDoesNotInventCpuUsage() {
        var current = snapshot("api.service", 100_000_000, 1_000);
        var result = attribute(List.of(current), Map.of(), 4);

        assertEquals(Status.INSUFFICIENT_DATA, result.status());
        assertEquals(Confidence.UNRESOLVED, result.confidence());
        assertTrue(result.evidence().isEmpty());
        assertEquals(1_000L, result.collectedAt());
        assertTrue(result.coverage().complete());
    }

    @Test
    void nextObservationCanIdentifyTheWorkload() {
        var initial = snapshot("api.service", 1_000_000, 1_000);
        var next = snapshot("api.service", 3_000_000, 2_000);
        var collections = new AtomicReference<>(collection(List.of(initial), Map.of()));
        var attributor = attributor(collections);

        assertEquals(Status.INSUFFICIENT_DATA, attributor.attribute(4).status());
        collections.set(collection(List.of(next), Map.of(path("api.service"), initial)));

        assertEquals(Status.IDENTIFIED, attributor.attribute(4).status());
    }

    @Test
    void rejectsCounterResetEqualTimeReversedTimeAndLongCollectionGap() {
        var current = snapshot("api.service", 2_000_000, 40_000);
        for (CgroupCpuSnapshot previous : List.of(
                snapshot("api.service", 3_000_000, 39_000),
                snapshot("api.service", 0, 40_000),
                snapshot("api.service", 0, 41_000),
                snapshot("api.service", 0, 7_999))) {
            var result = attribute(List.of(current), Map.of(path("api.service"), previous), 4);
            assertEquals(Status.INSUFFICIENT_DATA, result.status());
            assertTrue(result.evidence().isEmpty());
        }
    }

    @Test
    void rejectsResetUserOrSystemCountersEvenWhenTotalCounterIncreases() {
        var before = snapshot("api.service", 1_000_000, 0);
        for (CgroupCpuSnapshot current : List.of(
                withCounters("api.service", 3_000_000, 0, 600_000, 1_000),
                withCounters("api.service", 3_000_000, 2_400_000, 0, 1_000))) {
            var result = attribute(List.of(current), Map.of(path("api.service"), before), 4);
            assertEquals(Status.INSUFFICIENT_DATA, result.status());
        }
    }

    @Test
    void changingHostCapacityRequiresAnotherObservation() {
        var before = snapshot("api.service", 0, 0);
        var current = snapshot("api.service", 2_000_000, 1_000);
        var next = snapshot("api.service", 4_000_000, 2_000);
        var collections = new AtomicReference<>(
                collection(List.of(current), Map.of(path("api.service"), before)));
        var attributor = attributor(collections);

        assertEquals(50, attributor.attribute(4).score());
        assertEquals(Status.INSUFFICIENT_DATA, attributor.attribute(8).status());
        collections.set(collection(List.of(next), Map.of(path("api.service"), current)));
        assertEquals(25, attributor.attribute(8).score());
    }

    @Test
    void missingHostCapacityReturnsInsufficientData() {
        var before = snapshot("api.service", 0, 0);
        var current = snapshot("api.service", 2_000_000, 1_000);
        for (int capacity : List.of(0, -1)) {
            var result = attribute(List.of(current), Map.of(path("api.service"), before), capacity);
            assertEquals(Status.INSUFFICIENT_DATA, result.status());
        }
    }

    @Test
    void stableConsumptionHasSameScoreAcrossDifferentIntervals() {
        var before = snapshot("api.service", 0, 0);
        var shortWindow = snapshot("api.service", 2_000_000, 1_000);
        var longWindow = snapshot("api.service", 60_000_000, 30_000);
        var previous = Map.of(path("api.service"), before);

        assertEquals(
                attribute(List.of(shortWindow), previous, 4).score(),
                attribute(List.of(longWindow), previous, 4).score());
    }

    @Test
    void unsupportedOptionalCpuFilesDoNotPreventIdentification() {
        var before = snapshot("api.service", 0, 0);
        var current = snapshot("api.service", 2_000_000, 1_000);
        var result = attribute(List.of(current), Map.of(path("api.service"), before), 4);

        assertTrue(result.resolved());
        assertFalse(hasMetric(result, "quota.cores"));
        assertFalse(hasMetric(result, "quota.usage"));
        assertFalse(hasMetric(result, "psi.some.avg10"));
        assertFalse(hasMetric(result, "throttling.duration.delta"));
    }

    @Test
    void unlimitedQuotaDoesNotProduceFakeUtilization() {
        var before = detailed("api.service", 0, 0, null, null, null, null, 0);
        var current = detailed("api.service", 2_000_000, 1_000, null, null, null, null, 0);
        var result = attribute(List.of(current), Map.of(path("api.service"), before), 4);

        assertTrue(result.resolved());
        assertFalse(hasMetric(result, "quota.cores"));
        assertFalse(hasMetric(result, "quota.usage"));
    }

    @Test
    void changedQuotaDoesNotApplyCurrentLimitToPastUsage() {
        var before = detailed("api.service", 0, 0, 50_000L, null, null, null, 0);
        var current = detailed("api.service", 500_000, 1_000, 100_000L, null, null, null, 0);
        var result = attribute(List.of(current), Map.of(path("api.service"), before), 4);

        assertEquals(1, value(result, "quota.cores"));
        assertFalse(hasMetric(result, "quota.usage"));
    }

    @Test
    void resetOptionalThrottlingCountersAreOmitted() {
        var before = detailed("api.service", 0, 0, 100_000L, 50L, 20L, 500_000L, 0);
        var current = detailed("api.service", 500_000, 1_000, 100_000L, 10L, 2L, 1_000L, 0);
        var result = attribute(List.of(current), Map.of(path("api.service"), before), 4);

        assertTrue(result.resolved());
        assertFalse(hasMetric(result, "throttling.periods.delta"));
        assertFalse(hasMetric(result, "throttling.throttled-periods.delta"));
        assertFalse(hasMetric(result, "throttling.duration.delta"));
    }

    @Test
    void incompleteCoverageCapsConfidenceAndPreservesCollectionMetadata() {
        var before = snapshot("api.service", 0, 0);
        var current = snapshot("api.service", 2_000_000, 1_000);
        for (Coverage coverage : List.of(
                new Coverage(1, 1, false, false),
                new Coverage(1, 0, true, false),
                new Coverage(1, 0, false, true))) {
            var collection = new CollectionResult<>(
                    CgroupMonitor.Status.AVAILABLE,
                    List.of(current), Map.of(path("api.service"), before), coverage, 1_000);
            var result = new CpuPressureAttributor(() -> collection, 5, 1.2, MAXIMUM_GAP)
                    .attribute(4);

            assertTrue(result.resolved());
            assertEquals(Confidence.LOW, result.confidence());
            assertEquals(coverage, result.coverage());
            assertEquals(1_000L, result.collectedAt());
        }
    }

    @Test
    void unseenCandidateCapsConfidenceUntilItCanBeMeasured() {
        var before = snapshot("api.service", 0, 0);
        var current = snapshot("api.service", 2_000_000, 1_000);
        var newGroup = snapshot("new.service", 100_000_000, 1_000);
        var result = attribute(
                List.of(current, newGroup), Map.of(path("api.service"), before), 4);

        assertEquals("api.service", result.workload().name());
        assertEquals(Confidence.LOW, result.confidence());
        assertEquals(1, value(result, "candidates.measured"));
        assertEquals(1, value(result, "candidates.unmeasured"));
        assertTrue(result.coverage().complete());
    }

    @Test
    void competingCandidatesUseStableOrderingAndLowConfidence() {
        var a = snapshot("a.service", 2_000_000, 1_000);
        var b = snapshot("b.service", 2_000_000, 1_000);
        var previous = Map.of(
                path("a.service"), snapshot("a.service", 0, 0),
                path("b.service"), snapshot("b.service", 0, 0));
        for (List<CgroupCpuSnapshot> current : List.of(List.of(a, b), List.of(b, a))) {
            var result = attribute(current, previous, 4);
            assertEquals("a.service", result.workload().name());
            assertEquals(Confidence.LOW, result.confidence());
        }
    }

    @Test
    void lowOrZeroMeasuredUsageReturnsNoCandidate() {
        var before = snapshot("api.service", 0, 0);
        for (long usage : List.of(0L, 100_000L)) {
            var current = snapshot("api.service", usage, 1_000);
            var result = attribute(List.of(current), Map.of(path("api.service"), before), 4);
            assertEquals(Status.NO_CANDIDATE, result.status());
        }
        assertEquals(Status.NO_CANDIDATE, attribute(List.of(), Map.of(), 4).status());
    }

    @Test
    void zeroMinimumScoreStillDoesNotIdentifyAnIdleWorkload() {
        var before = snapshot("api.service", 0, 0);
        var current = snapshot("api.service", 0, 1_000);
        var collection = collection(List.of(current), Map.of(path("api.service"), before));
        var result = new CpuPressureAttributor(() -> collection, 0, 1.2, MAXIMUM_GAP)
                .attribute(4);

        assertEquals(Status.NO_CANDIDATE, result.status());
    }

    @Test
    void unavailableCollectionKeepsStatusAndCoverage() {
        var coverage = new Coverage(0, 1, true, false);
        for (CgroupMonitor.Status status : List.of(
                CgroupMonitor.Status.ACCESS_DENIED, CgroupMonitor.Status.CGROUP_V2_NOT_AVAILABLE)) {
            var collection = new CollectionResult<CgroupCpuSnapshot>(
                    status, List.of(), Map.of(), coverage, 1_000);
            var result = new CpuPressureAttributor(() -> collection, 5, 1.2, MAXIMUM_GAP)
                    .attribute(4);

            assertEquals(status.name(), result.status().name());
            assertEquals(coverage, result.coverage());
            assertEquals(1_000L, result.collectedAt());
            assertTrue(result.evidence().isEmpty());
        }
    }

    @Test
    void rejectsInvalidScoringParametersAndObservationGap() {
        var empty = collection(List.of(), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new CpuPressureAttributor(() -> empty, Double.NaN, 1.2, MAXIMUM_GAP));
        assertThrows(IllegalArgumentException.class,
                () -> new CpuPressureAttributor(() -> empty, 5, 1, MAXIMUM_GAP));
        assertThrows(IllegalArgumentException.class,
                () -> new CpuPressureAttributor(() -> empty, 5, 1.2, Duration.ZERO));
    }

    @Test
    void confidenceRequiresNewMeasuredIntervalsAndTheFullConfirmationDuration() {
        var collections = new AtomicReference<>(observedCpu(5_000, 2_000_000, 200_000));
        var attributor = attributor(collections);

        var first = attributor.attribute(4);
        assertEquals(Confidence.LOW, first.confidence());
        assertEquals(50, first.score());
        assertEquals(1, value(first, "confidence.observations"));
        assertEquals(0, value(first, "confidence.duration"));
        assertEquals(45, value(first, "confidence.margin"));
        assertEquals(5, value(first, "confidence.runner-score"));
        assertEquals(3, value(first, "confidence.confirmations-remaining"));
        assertTrue(attributor.needsConfirmation());

        collections.set(observedCpu(10_000, 2_000_000, 200_000));
        assertEquals(Confidence.LOW, attributor.attribute(4).confidence());
        collections.set(observedCpu(14_999, 10_000, 2_000_000, 200_000));
        var tooEarly = attributor.attribute(4);
        assertEquals(3, value(tooEarly, "confidence.observations"));
        assertEquals(9.999, value(tooEarly, "confidence.duration"), 0.000001);
        assertEquals(Confidence.LOW, tooEarly.confidence());

        collections.set(observedCpu(15_000, 14_999, 2_000_000, 200_000));
        var confirmed = attributor.attribute(4);
        assertEquals(Confidence.HIGH, confirmed.confidence());
        assertEquals(50, confirmed.score());
        assertEquals(4, value(confirmed, "confidence.observations"));
        assertEquals(10, value(confirmed, "confidence.duration"));
        assertFalse(attributor.needsConfirmation());
    }

    @Test
    void repeatedCpuCollectionConsumesAttemptsWithoutAdvancingConfidence() {
        var collections = new AtomicReference<>(observedCpu(5_000, 2_000_000, 200_000));
        var attributor = attributor(collections);
        var first = attributor.attribute(4);
        assertEquals(3, value(first, "confidence.confirmations-remaining"));

        for (int remaining = 2; remaining >= 0; remaining--) {
            assertTrue(attributor.needsConfirmation());
            var repeated = attributor.attribute(4);
            assertEquals(Confidence.LOW, repeated.confidence());
            assertEquals(1, value(repeated, "confidence.observations"));
            assertEquals(0, value(repeated, "confidence.duration"));
            assertEquals(remaining, value(repeated, "confidence.confirmations-remaining"));
        }

        assertFalse(attributor.needsConfirmation());
    }

    @Test
    void mediumConfidenceAlsoRequiresRepeatedObservations() {
        var collections = new AtomicReference<>(observedCpu(5_000, 1_000_000, 200_000));
        var attributor = attributor(collections);
        assertEquals(Confidence.LOW, attributor.attribute(4).confidence());
        collections.set(observedCpu(10_000, 1_000_000, 200_000));
        assertEquals(Confidence.LOW, attributor.attribute(4).confidence());
        collections.set(observedCpu(15_000, 1_000_000, 200_000));

        var confirmed = attributor.attribute(4);
        assertEquals(Confidence.MEDIUM, confirmed.confidence());
        assertEquals(25, confirmed.score());
        assertFalse(attributor.needsConfirmation());
    }

    @Test
    void winnerAndBaseGradeChangesRestartContinuityWithoutRefillingConfirmationBudget() {
        var collections = new AtomicReference<>(observedCpu(5_000, 2_000_000, 200_000));
        var attributor = attributor(collections);
        assertEquals(Confidence.LOW, attributor.attribute(4).confidence());

        collections.set(observedCpu(10_000, 1_000_000, 200_000));
        var changedGrade = attributor.attribute(4);
        assertEquals(Confidence.LOW, changedGrade.confidence());
        assertEquals(1, value(changedGrade, "confidence.observations"));
        assertEquals(2, value(changedGrade, "confidence.confirmations-remaining"));

        collections.set(observedCpu(15_000, 200_000, 2_000_000));
        var changedWinner = attributor.attribute(4);
        assertEquals("runner.service", changedWinner.workload().name());
        assertEquals(1, value(changedWinner, "confidence.observations"));
        assertEquals(1, value(changedWinner, "confidence.confirmations-remaining"));

        collections.set(observedCpu(20_000, 2_000_000, 200_000));
        var exhausted = attributor.attribute(4);
        assertEquals(Confidence.LOW, exhausted.confidence());
        assertEquals(1, value(exhausted, "confidence.observations"));
        assertEquals(0, value(exhausted, "confidence.confirmations-remaining"));
        assertFalse(attributor.needsConfirmation());
    }

    @Test
    void partialCollectionDropsConfirmedConfidenceAndStartsFreshConfirmation() {
        var collections = new AtomicReference<>(observedCpu(5_000, 2_000_000, 200_000));
        var attributor = attributor(collections);
        confirmHigh(attributor, collections);

        var complete = observedCpu(20_000, 2_000_000, 200_000);
        collections.set(new CollectionResult<>(
                complete.status(), complete.snapshots(), complete.previousSnapshots(),
                new Coverage(2, 1, false, false), complete.collectedAt()));
        var partial = attributor.attribute(4);
        assertEquals(Confidence.LOW, partial.confidence());
        assertEquals(50, partial.score());

        collections.set(observedCpu(25_000, 2_000_000, 200_000));
        var recovered = attributor.attribute(4);
        assertEquals(Confidence.LOW, recovered.confidence());
        assertEquals(1, value(recovered, "confidence.observations"));
    }

    @Test
    void resetCapacityChangeAndCollectionGapDiscardConfirmedHistory() {
        var collections = new AtomicReference<>(observedCpu(5_000, 2_000_000, 200_000));
        var attributor = attributor(collections);
        confirmHigh(attributor, collections);

        attributor.reset();
        assertFalse(attributor.needsConfirmation());
        collections.set(observedCpu(20_000, 2_000_000, 200_000));
        assertEquals(Confidence.LOW, attributor.attribute(4).confidence());
        assertEquals(Status.INSUFFICIENT_DATA, attributor.attribute(8).status());
        collections.set(observedCpu(25_000, 4_000_000, 400_000));
        var resized = attributor.attribute(8);
        assertEquals(Confidence.LOW, resized.confidence());
        assertEquals(1, value(resized, "confidence.observations"));

        collections.set(observedCpu(60_000, 4_000_000, 400_000));
        var afterGap = attributor.attribute(8);
        assertEquals(Confidence.LOW, afterGap.confidence());
        assertEquals(1, value(afterGap, "confidence.observations"));
    }

    @Test
    void reversedCollectionTimeCannotConfirmAnOlderCandidate() {
        var collections = new AtomicReference<>(observedCpu(5_000, 2_000_000, 200_000));
        var attributor = attributor(collections);
        attributor.attribute(4);

        collections.set(observedCpu(4_000, 2_000_000, 200_000));
        var reversed = attributor.attribute(4);
        assertEquals(Confidence.LOW, reversed.confidence());
        assertTrue(value(reversed, "confidence.observations") <= 1);
    }

    @Test
    void absoluteMarginMustQualifyBeforeConfidenceCanBeConfirmed() {
        var collections = new AtomicReference<>(observedCpu(5_000, 1_000_000, 820_000));
        var defaults = attributor(collections);
        var smallerMargin = new CpuPressureAttributor(
                collections::get, 5, 1.2, MAXIMUM_GAP,
                new AttributionConfidence(3, Duration.ofSeconds(10), 3, 4));
        WorkloadAttribution defaultResult = null;
        WorkloadAttribution configuredResult = null;
        for (long time : List.of(5_000L, 10_000L, 15_000L)) {
            collections.set(observedCpu(time, 1_000_000, 820_000));
            defaultResult = defaults.attribute(4);
            configuredResult = smallerMargin.attribute(4);
        }

        assertEquals(25, defaultResult.score());
        assertEquals(4.5, value(defaultResult, "confidence.margin"), 0.000001);
        assertEquals(Confidence.LOW, defaultResult.confidence());
        assertFalse(defaults.needsConfirmation());
        assertEquals(25, configuredResult.score());
        assertEquals(Confidence.MEDIUM, configuredResult.confidence());
    }

    private static void confirmHigh(
            CpuPressureAttributor attributor,
            AtomicReference<CollectionResult<CgroupCpuSnapshot>> collections) {
        WorkloadAttribution result = null;
        for (long time : List.of(5_000L, 10_000L, 15_000L)) {
            collections.set(observedCpu(time, 2_000_000, 200_000));
            result = attributor.attribute(4);
        }
        assertEquals(Confidence.HIGH, result.confidence());
    }

    private static CollectionResult<CgroupCpuSnapshot> observedCpu(
            long timestamp, long workerUsecPerSecond, long runnerUsecPerSecond) {
        return observedCpu(
                timestamp, Math.max(0, timestamp - 5_000),
                workerUsecPerSecond, runnerUsecPerSecond);
    }

    private static CollectionResult<CgroupCpuSnapshot> observedCpu(
            long timestamp, long previousAt, long workerUsecPerSecond, long runnerUsecPerSecond) {
        return collection(
                List.of(
                        snapshot("worker.service", timestamp * workerUsecPerSecond / 1_000, timestamp),
                        snapshot("runner.service", timestamp * runnerUsecPerSecond / 1_000, timestamp)),
                Map.of(
                        path("worker.service"), snapshot(
                                "worker.service", previousAt * workerUsecPerSecond / 1_000,
                                previousAt),
                        path("runner.service"), snapshot(
                                "runner.service", previousAt * runnerUsecPerSecond / 1_000,
                                previousAt)));
    }

    private static CpuPressureAttributor attributor(
            AtomicReference<CollectionResult<CgroupCpuSnapshot>> collections) {
        return new CpuPressureAttributor(collections::get, 5, 1.2, MAXIMUM_GAP);
    }

    private static WorkloadAttribution attribute(
            List<CgroupCpuSnapshot> current,
            Map<String, CgroupCpuSnapshot> previous,
            int hostCpuCount) {
        var collection = collection(current, previous);
        return new CpuPressureAttributor(() -> collection, 5, 1.2, MAXIMUM_GAP)
                .attribute(hostCpuCount);
    }

    private static CollectionResult<CgroupCpuSnapshot> collection(
            List<CgroupCpuSnapshot> current, Map<String, CgroupCpuSnapshot> previous) {
        return new CollectionResult<>(
                CgroupMonitor.Status.AVAILABLE,
                current,
                previous,
                Coverage.complete(current.size()),
                current.stream().mapToLong(CgroupCpuSnapshot::collectedAt).max().orElse(0));
    }

    private static CgroupCpuSnapshot snapshot(String name, long usage, long timestamp) {
        return withCounters(name, usage, usage * 4 / 5, usage / 5, timestamp);
    }

    private static CgroupCpuSnapshot withCounters(
            String name, long usage, long user, long system, long timestamp) {
        return new CgroupCpuSnapshot(
                WorkloadIdentity.fromCgroupPath(path(name)),
                usage, user, system, null, null, null, null, null, Double.NaN, timestamp);
    }

    private static CgroupCpuSnapshot detailed(
            String name,
            long usage,
            long timestamp,
            Long quota,
            Long periods,
            Long throttledPeriods,
            Long throttledUsec,
            double psiSome) {
        return new CgroupCpuSnapshot(
                WorkloadIdentity.fromCgroupPath(path(name)),
                usage, usage * 4 / 5, usage / 5, quota, 100_000L,
                periods, throttledPeriods, throttledUsec, psiSome, timestamp);
    }

    private static double value(WorkloadAttribution result, String metric) {
        return result.evidence().stream()
                .filter(evidence -> evidence.metric().equals(metric))
                .findFirst().orElseThrow().value();
    }

    private static boolean hasMetric(WorkloadAttribution result, String metric) {
        return result.evidence().stream().anyMatch(evidence -> evidence.metric().equals(metric));
    }

    private static String path(String name) {
        return "/system.slice/" + name;
    }
}
