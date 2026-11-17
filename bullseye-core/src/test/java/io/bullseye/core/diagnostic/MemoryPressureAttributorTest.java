package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.common.workload.WorkloadAttribution.Coverage;
import io.bullseye.common.workload.WorkloadIdentity;
import io.bullseye.core.linux.cgroup.CgroupMemorySnapshot;
import io.bullseye.core.linux.cgroup.CgroupMonitor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class MemoryPressureAttributorTest {

    private static final long GIB = 1_024L * 1_024 * 1_024;
    private static final long HOST_TOTAL = 16 * GIB;

    @Test
    void identifiesClearSingleCauseWithHighConfidence() {
        WorkloadAttribution result =
                attribute(
                        List.of(
                                snapshot("service-a", 1.1, 5, 0),
                                snapshot("service-b", 7, 35, 5),
                                snapshot("service-c", 4, 2, 0)),
                        Map.of(
                                path("service-a"), snapshotAt("service-a", 1, 1, 0, 0),
                                path("service-b"), snapshotAt("service-b", 2, 1, 0, 0),
                                path("service-c"), snapshotAt("service-c", 4, 1, 0, 0)));

        assertEquals("service-b", result.workload().name());
        assertEquals(WorkloadAttribution.Confidence.HIGH, result.confidence());
    }

    @Test
    void prefersRapidlyGrowingServiceOverLargeStableDatabase() {
        WorkloadAttribution result =
                attribute(
                        List.of(
                                snapshot("mysql.service", 8.1, 2, 0),
                                snapshot("order-api.service", 5, 2, 0)),
                        Map.of(
                                path("mysql.service"), snapshotAt("mysql.service", 8, 2, 0, 0),
                                path("order-api.service"),
                                        snapshotAt("order-api.service", 2, 2, 0, 0)));

        assertEquals("order-api.service", result.workload().name());
        assertTrue(value(result, "score.growth") > 0);
    }

    @Test
    void closeCandidatesReturnLowConfidence() {
        WorkloadAttribution result =
                attribute(
                        List.of(
                                snapshot("service-a", 4, 20, 1),
                                snapshot("service-b", 3.9, 19, 1)),
                        Map.of());

        assertTrue(result.resolved());
        assertEquals(WorkloadAttribution.Confidence.LOW, result.confidence());
    }

    @Test
    void attributesWithoutPreviousSnapshotUsingUsageAndPsi() {
        WorkloadAttribution result =
                attribute(List.of(snapshot("order-api.service", 7, 40, 6)), Map.of());

        assertEquals("order-api.service", result.workload().name());
        assertEquals(WorkloadAttribution.Confidence.HIGH, result.confidence());
    }

    @Test
    void accessDeniedIsReturnedWithoutException() {
        CgroupMonitor.CollectionResult denied =
                CgroupMonitor.CollectionResult.unavailable(CgroupMonitor.Status.ACCESS_DENIED);
        MemoryPressureAttributor attributor = new MemoryPressureAttributor(() -> denied, 5, 1.2);

        WorkloadAttribution result = assertDoesNotThrow(() -> attributor.attribute(HOST_TOTAL));

        assertEquals(WorkloadAttribution.Status.ACCESS_DENIED, result.status());
        assertEquals("UNKNOWN", result.workload().name());
    }

    @Test
    void emptyCollectionReturnsNoCandidate() {
        WorkloadAttribution result = attribute(List.of(), Map.of());

        assertEquals(WorkloadAttribution.Status.NO_CANDIDATE, result.status());
    }

    @Test
    void missingHostCapacityReturnsInsufficientData() {
        CgroupMonitor.CollectionResult collection =
                new CgroupMonitor.CollectionResult(
                        CgroupMonitor.Status.AVAILABLE,
                        List.of(snapshot("order-api.service", 7, 40, 6)),
                        Map.of());
        MemoryPressureAttributor attributor =
                new MemoryPressureAttributor(() -> collection, 5, 1.2);

        WorkloadAttribution result = attributor.attribute(-1);

        assertEquals(WorkloadAttribution.Status.INSUFFICIENT_DATA, result.status());
    }

    @Test
    void exposesMeasuredInputsAndScoreBreakdown() {
        var before = snapshotAt("api.service", 1, 10, 1, 0);
        var current = new CgroupMemorySnapshot(
                before.workload(), 2 * GIB, 4 * GIB, 20, 2, 2, 1, 0, 0, 5_000);

        WorkloadAttribution result =
                attribute(List.of(current), Map.of(path("api.service"), before));

        assertEquals(2 * GIB, value(result, "memory.current"));
        assertEquals(4 * GIB, value(result, "memory.max"));
        assertEquals(12.5, value(result, "host.share"));
        assertEquals(GIB, value(result, "growth.bytes"));
        assertEquals(5_000, value(result, "observation.duration"));
        assertEquals(GIB / 5.0, value(result, "growth.bytes-per-second"));
        assertEquals(20, value(result, "psi.some.avg10"));
        assertEquals(2, value(result, "events.high.delta"));
        assertEquals(1, value(result, "events.max.delta"));
        assertEquals(1.4, value(result, "score.events"), 0.000001);
        double components = result.evidence().stream()
                .filter(e -> e.metric().startsWith("score."))
                .mapToDouble(e -> e.value()).sum();
        assertEquals(result.score(), components, 0.000001);
        assertEquals(5_000L, result.collectedAt());
        assertTrue(result.coverage().complete());
    }

    @Test
    void equalGrowthRatesGetEqualScoresAcrossObservationIntervals() {
        var current = snapshotAt("api.service", 2, 20, 2, 10_000);
        var fast = snapshotAt("api.service", 1, 0, 0, 5_000);
        var slow = snapshotAt("api.service", 0, 0, 0, 0);

        var fiveSeconds = attribute(List.of(current), Map.of(path("api.service"), fast));
        var tenSeconds = attribute(List.of(current), Map.of(path("api.service"), slow));

        assertEquals(value(fiveSeconds, "score.growth"), value(tenSeconds, "score.growth"));
        assertEquals(fiveSeconds.score(), tenSeconds.score());
        assertEquals(5_000, value(fiveSeconds, "observation.duration"));
        assertEquals(10_000, value(tenSeconds, "observation.duration"));
    }

    @Test
    void sameGrowthOverLongerIntervalGetsLowerGrowthScore() {
        var current = snapshotAt("api.service", 2, 20, 2, 30_000);
        var recent = snapshotAt("api.service", 1, 0, 0, 25_000);
        var old = snapshotAt("api.service", 1, 0, 0, 0);

        var fiveSeconds = attribute(List.of(current), Map.of(path("api.service"), recent));
        var thirtySeconds = attribute(List.of(current), Map.of(path("api.service"), old));

        assertEquals(value(fiveSeconds, "score.growth") / 6,
                value(thirtySeconds, "score.growth"), 0.000001);
    }

    @Test
    void missingOrNonIncreasingObservationTimeDoesNotInventDeltas() {
        var current = snapshotAt("api.service", 7, 40, 6, 5_000);
        for (var previous : List.of(
                Map.<String, CgroupMemorySnapshot>of(),
                Map.of(path("api.service"), current),
                Map.of(path("api.service"), snapshotAt("api.service", 1, 0, 0, 6_000)))) {
            var result = attribute(List.of(current), previous);
            assertEquals(0, value(result, "score.growth"));
            assertEquals(0, value(result, "score.events"));
            assertFalse(result.evidence().stream()
                    .anyMatch(e -> e.metric().equals("growth.bytes")));
            assertFalse(result.evidence().stream()
                    .anyMatch(e -> e.metric().equals("events.oom.delta")));
        }
    }

    @Test
    void fallingMemoryAndResetEventCountersDoNotIncreasePressureScore() {
        var before = new CgroupMemorySnapshot(
                WorkloadIdentity.fromCgroupPath(path("api.service")),
                8 * GIB, null, 40, 6, 100, 100, 10, 5, 0);
        var current = snapshotAt("api.service", 7, 40, 6, 5_000);
        var result = attribute(List.of(current), Map.of(path("api.service"), before));

        assertEquals(-GIB, value(result, "growth.bytes"));
        assertEquals(0, value(result, "score.growth"));
        assertEquals(0, value(result, "score.events"));
        assertEquals(0, value(result, "events.oom_kill.delta"));
    }

    @Test
    void incompleteCollectionCapsConfidenceAndRetainsCoverage() {
        var current = snapshotAt("api.service", 7, 40, 6, 5_000);
        assertEquals(WorkloadAttribution.Confidence.HIGH,
                attribute(List.of(current), Map.of()).confidence());

        for (Coverage coverage : List.of(
                new Coverage(1, 1, false, false),
                new Coverage(1, 0, true, false),
                new Coverage(1, 0, false, true))) {
            var collection = new CgroupMonitor.CollectionResult(
                    CgroupMonitor.Status.AVAILABLE, List.of(current), Map.of(), coverage, 5_000);
            var result = new MemoryPressureAttributor(() -> collection, 5, 1.2)
                    .attribute(HOST_TOTAL);

            assertTrue(result.resolved());
            assertEquals(WorkloadAttribution.Confidence.LOW, result.confidence());
            assertEquals(coverage, result.coverage());
            assertEquals(5_000L, result.collectedAt());
        }
    }

    @Test
    void noCandidateStillReportsIncompleteInvestigation() {
        var coverage = new Coverage(0, 1, true, false);
        var collection = new CgroupMonitor.CollectionResult(
                CgroupMonitor.Status.AVAILABLE, List.of(), Map.of(), coverage, 5_000);
        var result = new MemoryPressureAttributor(() -> collection, 5, 1.2).attribute(HOST_TOTAL);

        assertEquals(WorkloadAttribution.Status.NO_CANDIDATE, result.status());
        assertEquals(coverage, result.coverage());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void equalScoringCandidatesHaveStableOrdering() {
        var a = snapshot("a.service", 4, 20, 2);
        var b = snapshot("b.service", 4, 20, 2);

        assertEquals("a.service", attribute(List.of(a, b), Map.of()).workload().name());
        assertEquals("a.service", attribute(List.of(b, a), Map.of()).workload().name());
    }

    private static double value(WorkloadAttribution result, String name) {
        return result.evidence().stream().filter(e -> e.metric().equals(name))
                .findFirst().orElseThrow().value();
    }

    private static WorkloadAttribution attribute(
            List<CgroupMemorySnapshot> current, Map<String, CgroupMemorySnapshot> previous) {
        AtomicReference<CgroupMonitor.CollectionResult> result =
                new AtomicReference<>(
                        new CgroupMonitor.CollectionResult(
                                CgroupMonitor.Status.AVAILABLE, current, previous));
        return new MemoryPressureAttributor(result::get, 5, 1.2).attribute(HOST_TOTAL);
    }

    private static CgroupMemorySnapshot snapshot(
            String workload, double memoryGib, double psiSome, double psiFull) {
        return snapshotAt(workload, memoryGib, psiSome, psiFull, 5_000);
    }

    private static CgroupMemorySnapshot snapshotAt(
            String workload, double memoryGib, double psiSome, double psiFull, long timestamp) {
        return new CgroupMemorySnapshot(
                WorkloadIdentity.fromCgroupPath(path(workload)),
                (long) (memoryGib * GIB),
                null,
                psiSome,
                psiFull,
                0,
                0,
                0,
                0,
                timestamp);
    }

    private static String path(String workload) {
        return "/system.slice/" + workload;
    }
}
