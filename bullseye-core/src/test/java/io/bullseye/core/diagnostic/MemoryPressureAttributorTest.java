package io.bullseye.core.diagnostic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.workload.WorkloadAttribution;
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
                                path("service-a"), snapshot("service-a", 1, 1, 0),
                                path("service-b"), snapshot("service-b", 2, 1, 0),
                                path("service-c"), snapshot("service-c", 4, 1, 0)));

        assertEquals("service-b", result.workload().name());
        assertEquals(WorkloadAttribution.Confidence.HIGH, result.confidence());
    }

    @Test
    void prefersRapidlyGrowingServiceOverLargeStableDatabase() {
        WorkloadAttribution result =
                attribute(
                        List.of(
                                snapshot("mysql.service", 8.1, 2, 0),
                                snapshot("order-api.service", 5, 25, 2)),
                        Map.of(
                                path("mysql.service"), snapshot("mysql.service", 8, 2, 0),
                                path("order-api.service"), snapshot("order-api.service", 2, 2, 0)));

        assertEquals("order-api.service", result.workload().name());
    }

    @Test
    void closeCandidatesReturnLowConfidence() {
        WorkloadAttribution result =
                attribute(
                        List.of(snapshot("service-a", 4, 20, 1), snapshot("service-b", 3.9, 19, 1)),
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
                1_000);
    }

    private static String path(String workload) {
        return "/system.slice/" + workload;
    }
}
