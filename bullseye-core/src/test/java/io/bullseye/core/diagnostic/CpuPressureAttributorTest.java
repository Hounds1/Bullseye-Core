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
import io.bullseye.core.linux.cgroup.CgroupCpuSnapshot;
import io.bullseye.core.linux.cgroup.CgroupMonitor;
import io.bullseye.core.linux.cgroup.CgroupMonitor.CpuCollectionResult;

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
        assertEquals(Confidence.HIGH, result.confidence());
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
        assertEquals(Confidence.HIGH, result.confidence());
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
            var collection = new CpuCollectionResult(
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
            var collection = new CpuCollectionResult(
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

    private static CpuPressureAttributor attributor(
            AtomicReference<CpuCollectionResult> collections) {
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

    private static CpuCollectionResult collection(
            List<CgroupCpuSnapshot> current, Map<String, CgroupCpuSnapshot> previous) {
        return new CpuCollectionResult(
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
