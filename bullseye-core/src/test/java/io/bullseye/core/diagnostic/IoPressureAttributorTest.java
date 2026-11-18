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
import io.bullseye.core.linux.cgroup.CgroupIoSnapshot;
import io.bullseye.core.linux.cgroup.CgroupIoSnapshot.DeviceLimit;
import io.bullseye.core.linux.cgroup.CgroupIoSnapshot.DeviceStats;
import io.bullseye.core.linux.cgroup.CgroupMonitor;
import io.bullseye.core.linux.cgroup.CgroupMonitor.CollectionResult;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class IoPressureAttributorTest {

    private static final Duration MAXIMUM_GAP = Duration.ofSeconds(32);
    private static final DeviceStats ZERO = new DeviceStats(0, 0, 0, 0);

    @Test
    void identifiesObservedDeviceLeaderAndPublishesActualReadWriteRates() {
        var worker = snapshot("worker.service", 2_000,
                Map.of("8:0", new DeviceStats(1_200, 600, 120, 60)));
        var other = snapshot("other.service", 2_000,
                Map.of("8:0", new DeviceStats(100, 100, 10, 10)));
        var result = attribute(List.of(worker, other), Map.of(
                path("worker.service"), snapshot("worker.service", 0, Map.of("8:0", ZERO)),
                path("other.service"), snapshot("other.service", 0, Map.of("8:0", ZERO))));

        assertEquals("worker.service", result.workload().name());
        assertEquals(90, result.score());
        assertEquals(Confidence.LOW, result.confidence());
        assertEquals(600, value(result, "device.8:0.read.bytes.per-second"));
        assertEquals(300, value(result, "device.8:0.write.bytes.per-second"));
        assertEquals(60, value(result, "device.8:0.read.operations.per-second"));
        assertEquals(30, value(result, "device.8:0.write.operations.per-second"));
        assertEquals(1_200, value(result, "device.8:0.read.bytes.delta"));
        assertEquals(600, value(result, "device.8:0.write.bytes.delta"));
        assertEquals(120, value(result, "device.8:0.read.operations.delta"));
        assertEquals(60, value(result, "device.8:0.write.operations.delta"));
        assertEquals(90, value(result, "device.8:0.observed.byte.share"));
        assertEquals(90, value(result, "device.8:0.observed.operation.share"));
        assertEquals(90, value(result, "score.activity"));
        assertEquals(2_000, value(result, "observation.duration"));
        assertEquals(1, value(result, "devices.active"));
        assertEquals(2, value(result, "candidates.measured"));
        assertEquals(0, value(result, "candidates.unmeasured"));
    }

    @Test
    void pressureAndLocalLimitsDoNotPromoteAnIdleOrSmallConsumer() {
        var limit = new DeviceLimit(1L, 1L, 1L, 1L);
        for (DeviceStats activity : List.of(ZERO, new DeviceStats(1, 1, 1, 1))) {
            var victim = detailed("victim.service", 1_000,
                    Map.of("8:0", activity), Map.of("8:0", limit), true, 99, 90);
            var worker = snapshot("worker.service", 1_000,
                    Map.of("8:0", new DeviceStats(900, 0, 900, 0)));
            var result = attribute(List.of(victim, worker), Map.of(
                    path("victim.service"), snapshot("victim.service", 0, Map.of("8:0", ZERO)),
                    path("worker.service"), snapshot("worker.service", 0, Map.of("8:0", ZERO))));

            assertEquals("worker.service", result.workload().name());
            assertEquals(Confidence.LOW, result.confidence());
        }
    }

    @Test
    void operationsMatterAlongsideBytesOnTheSameDevice() {
        var random = snapshot("random.service", 1_000,
                Map.of("8:0", new DeviceStats(250, 0, 900, 0)));
        var sequential = snapshot("sequential.service", 1_000,
                Map.of("8:0", new DeviceStats(750, 0, 100, 0)));
        var result = attribute(List.of(random, sequential), Map.of(
                path("random.service"), snapshot("random.service", 0, Map.of("8:0", ZERO)),
                path("sequential.service"), snapshot("sequential.service", 0, Map.of("8:0", ZERO))));

        assertEquals("random.service", result.workload().name());
        assertEquals(57.5, result.score());
        assertEquals(25, value(result, "device.8:0.observed.byte.share"));
        assertEquals(90, value(result, "device.8:0.observed.operation.share"));
    }

    @Test
    void conflictingByteAndOperationLeadersDoNotProduceAnArbitraryWinner() {
        var a = snapshot("a.service", 1_000,
                Map.of("8:0", new DeviceStats(900, 0, 100, 0)));
        var b = snapshot("b.service", 1_000,
                Map.of("8:0", new DeviceStats(100, 0, 900, 0)));
        var previous = Map.of(
                path("a.service"), snapshot("a.service", 0, Map.of("8:0", ZERO)),
                path("b.service"), snapshot("b.service", 0, Map.of("8:0", ZERO)));
        for (List<CgroupIoSnapshot> current : List.of(List.of(a, b), List.of(b, a))) {
            assertEquals(Status.NO_CANDIDATE, attribute(current, previous).status());
        }
    }

    @Test
    void differentDeviceLeadersCannotBeRankedByUnrelatedThroughput() {
        var bulk = snapshot("bulk.service", 1_000,
                Map.of("8:0", new DeviceStats(1_000_000, 0, 1_000, 0)));
        var small = snapshot("small.service", 1_000,
                Map.of("8:16", new DeviceStats(1, 0, 1, 0)));
        var result = attribute(List.of(bulk, small), Map.of(
                path("bulk.service"), snapshot("bulk.service", 0, Map.of("8:0", ZERO)),
                path("small.service"), snapshot("small.service", 0, Map.of("8:16", ZERO))));

        assertEquals(Status.NO_CANDIDATE, result.status());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void commonLeaderAcrossDevicesUsesItsLowestDeviceScore() {
        var worker = snapshot("worker.service", 1_000, Map.of(
                "8:0", new DeviceStats(900, 0, 900, 0),
                "8:16", new DeviceStats(8_000, 0, 8_000, 0)));
        var other = snapshot("other.service", 1_000, Map.of(
                "8:0", new DeviceStats(100, 0, 100, 0),
                "8:16", new DeviceStats(2_000, 0, 2_000, 0)));
        var zeroDevices = Map.of("8:0", ZERO, "8:16", ZERO);
        var result = attribute(List.of(worker, other), Map.of(
                path("worker.service"), snapshot("worker.service", 0, zeroDevices),
                path("other.service"), snapshot("other.service", 0, zeroDevices)));

        assertEquals("worker.service", result.workload().name());
        assertEquals(80, result.score());
        assertEquals(90, value(result, "device.8:0.score.activity"));
        assertEquals(80, value(result, "device.8:16.score.activity"));
        assertEquals(2, value(result, "devices.active"));
    }

    @Test
    void anIdleUnrelatedDeviceDoesNotPreventIdentification() {
        var worker = snapshot("worker.service", 1_000,
                Map.of("8:0", new DeviceStats(900, 0, 900, 0)));
        var idle = snapshot("idle.service", 1_000, Map.of("8:16", ZERO));
        var result = attribute(List.of(worker, idle), Map.of(
                path("worker.service"), snapshot("worker.service", 0, Map.of("8:0", ZERO)),
                path("idle.service"), snapshot("idle.service", 0, Map.of("8:16", ZERO))));

        assertEquals("worker.service", result.workload().name());
        assertEquals(1, value(result, "devices.active"));
        assertEquals(2, value(result, "devices.measured"));
    }

    @Test
    void oneNonzeroAxisUsesItsWholeShareWithoutInventingTheOther() {
        for (DeviceStats activity : List.of(
                new DeviceStats(100, 0, 0, 0), new DeviceStats(0, 0, 100, 0))) {
            var result = attribute(
                    List.of(snapshot("worker.service", 1_000, Map.of("8:0", activity))),
                    Map.of(path("worker.service"), snapshot("worker.service", 0, Map.of("8:0", ZERO))));

            assertEquals(100, result.score());
            assertEquals(Confidence.LOW, result.confidence());
        }
    }

    @Test
    void firstObservationRequiresASecondSnapshotBeforeUsingCumulativeCounters() {
        var first = snapshot("worker.service", 1_000,
                Map.of("8:0", new DeviceStats(1_000_000, 0, 1_000, 0)));
        var second = snapshot("worker.service", 2_000,
                Map.of("8:0", new DeviceStats(1_001_000, 0, 1_010, 0)));
        var collections = new AtomicReference<>(collection(List.of(first), Map.of()));
        var attributor = new IoPressureAttributor(collections::get, 5, 1.2, MAXIMUM_GAP);

        var initial = attributor.attribute();
        assertEquals(Status.INSUFFICIENT_DATA, initial.status());
        assertTrue(initial.evidence().isEmpty());
        assertEquals(1_000L, initial.collectedAt());

        collections.set(collection(List.of(second), Map.of(path("worker.service"), first)));
        var result = attributor.attribute();
        assertEquals(Status.IDENTIFIED, result.status());
        assertEquals(1_000, value(result, "device.8:0.read.bytes.delta"));
    }

    @Test
    void anyResetCounterInvalidatesThatDeviceInterval() {
        var previous = snapshot("worker.service", 0,
                Map.of("8:0", new DeviceStats(100, 100, 100, 100)));
        for (DeviceStats reset : List.of(
                new DeviceStats(0, 200, 200, 200),
                new DeviceStats(200, 0, 200, 200),
                new DeviceStats(200, 200, 0, 200),
                new DeviceStats(200, 200, 200, 0))) {
            var current = snapshot("worker.service", 1_000, Map.of("8:0", reset));
            var result = attribute(List.of(current), Map.of(path("worker.service"), previous));
            assertEquals(Status.INSUFFICIENT_DATA, result.status());
        }
    }

    @Test
    void rejectsEqualReversedOrStaleObservationTimes() {
        var current = snapshot("worker.service", 40_000,
                Map.of("8:0", new DeviceStats(100, 0, 10, 0)));
        for (long timestamp : List.of(40_000L, 41_000L, 7_999L)) {
            var previous = snapshot("worker.service", timestamp, Map.of("8:0", ZERO));
            assertEquals(Status.INSUFFICIENT_DATA,
                    attribute(List.of(current), Map.of(path("worker.service"), previous)).status());
        }
        var atBoundary = snapshot("worker.service", 8_000, Map.of("8:0", ZERO));
        assertTrue(attribute(List.of(current), Map.of(path("worker.service"), atBoundary)).resolved());
    }

    @Test
    void changedWorkloadIdentityCannotReuseTheOldPathCounters() {
        var current = snapshot("worker.service", 1_000,
                Map.of("8:0", new DeviceStats(100, 0, 10, 0)));
        var previous = new CgroupIoSnapshot(
                new WorkloadIdentity("old", "old", path("worker.service"),
                        WorkloadIdentity.Type.GENERIC_CGROUP),
                Map.of("8:0", ZERO), Map.of(), false, Double.NaN, Double.NaN, 0);
        assertEquals(Status.INSUFFICIENT_DATA,
                attribute(List.of(current), Map.of(path("worker.service"), previous)).status());
    }

    @Test
    void newResetAndRemovedDevicesAreReportedWithoutCorruptingValidDeviceRates() {
        var previous = snapshot("worker.service", 0, Map.of(
                "8:0", ZERO,
                "8:16", new DeviceStats(1_000, 0, 100, 0),
                "8:32", new DeviceStats(500, 0, 50, 0)));
        var current = snapshot("worker.service", 1_000, Map.of(
                "8:0", new DeviceStats(100, 0, 10, 0),
                "8:16", ZERO,
                "8:48", new DeviceStats(1_000_000, 0, 100_000, 0)));
        var result = attribute(List.of(current), Map.of(path("worker.service"), previous));

        assertTrue(result.resolved());
        assertEquals(1, value(result, "devices.measured"));
        assertEquals(3, value(result, "devices.unmeasured"));
        assertEquals(1, value(result, "candidates.measured"));
        assertEquals(1, value(result, "candidates.unmeasured"));
        assertEquals(100, value(result, "device.8:0.read.bytes.per-second"));
        assertFalse(hasMetric(result, "device.8:48.read.bytes.per-second"));
    }

    @Test
    void normalizationUsesRatesRatherThanComparingUnequalIntervalDeltas() {
        var a = snapshot("a.service", 10_000,
                Map.of("8:0", new DeviceStats(900, 0, 90, 0)));
        var b = snapshot("b.service", 10_000,
                Map.of("8:0", new DeviceStats(1_000, 0, 100, 0)));
        var result = attribute(List.of(a, b), Map.of(
                path("a.service"), snapshot("a.service", 9_000, Map.of("8:0", ZERO)),
                path("b.service"), snapshot("b.service", 0, Map.of("8:0", ZERO))));

        assertEquals("a.service", result.workload().name());
        assertEquals(90, result.score());
    }

    @Test
    void localLimitsAndPressureAreSupplementaryEvidenceOnly() {
        var limit = new DeviceLimit(50L, 100L, 5L, 10L);
        var previous = detailed("worker.service", 0,
                Map.of("8:0", ZERO), Map.of("8:0", limit), true, 0, 0);
        var current = detailed("worker.service", 1_000,
                Map.of("8:0", new DeviceStats(100, 200, 10, 20)),
                Map.of("8:0", limit), true, 80, 40);
        var result = attribute(List.of(current), Map.of(path("worker.service"), previous));

        assertEquals(100, result.score());
        assertEquals(Confidence.LOW, result.confidence());
        assertEquals(80, value(result, "psi.some.avg10"));
        assertEquals(40, value(result, "psi.full.avg10"));
        assertEquals(1, value(result, "limits.available"));
        assertEquals(50, value(result, "device.8:0.limit.read.bytes.per-second"));
        assertEquals(100, value(result, "device.8:0.limit.write.bytes.per-second"));
        assertEquals(5, value(result, "device.8:0.limit.read.operations.per-second"));
        assertEquals(10, value(result, "device.8:0.limit.write.operations.per-second"));
        assertEquals(200, value(result, "device.8:0.limit.read.bytes.usage"));
        assertEquals(200, value(result, "device.8:0.limit.write.bytes.usage"));
        assertEquals(200, value(result, "device.8:0.limit.read.operations.usage"));
        assertEquals(200, value(result, "device.8:0.limit.write.operations.usage"));
    }

    @Test
    void changedOrPreviouslyUnknownLimitDoesNotInventIntervalLimitUtilization() {
        var current = detailed("worker.service", 1_000,
                Map.of("8:0", new DeviceStats(100, 0, 10, 0)),
                Map.of("8:0", new DeviceLimit(100L, null, 10L, null)), true, 0, 0);
        for (CgroupIoSnapshot previous : List.of(
                snapshot("worker.service", 0, Map.of("8:0", ZERO)),
                detailed("worker.service", 0, Map.of("8:0", ZERO), Map.of(), true, 0, 0),
                detailed("worker.service", 0, Map.of("8:0", ZERO),
                        Map.of("8:0", new DeviceLimit(50L, null, 5L, null)), true, 0, 0))) {
            var result = attribute(List.of(current), Map.of(path("worker.service"), previous));
            assertEquals(100, value(result, "device.8:0.limit.read.bytes.per-second"));
            assertFalse(hasMetric(result, "device.8:0.limit.read.bytes.usage"));
            assertFalse(hasMetric(result, "device.8:0.limit.read.operations.usage"));
        }
    }

    @Test
    void unsupportedOptionalFilesStillAllowALowConfidenceActivityCandidate() {
        var current = snapshot("worker.service", 1_000,
                Map.of("8:0", new DeviceStats(100, 0, 10, 0)));
        var result = attribute(List.of(current), Map.of(
                path("worker.service"), snapshot("worker.service", 0, Map.of("8:0", ZERO))));

        assertTrue(result.resolved());
        assertEquals(0, value(result, "limits.available"));
        assertFalse(hasMetric(result, "psi.some.avg10"));
        assertFalse(hasMetric(result, "psi.full.avg10"));
        assertFalse(hasMetric(result, "device.8:0.limit.read.bytes.usage"));
    }

    @Test
    void incompleteCoverageAndNewGroupsRemainVisibleInTheLowConfidenceResult() {
        var previous = snapshot("worker.service", 0, Map.of("8:0", ZERO));
        var current = snapshot("worker.service", 1_000,
                Map.of("8:0", new DeviceStats(100, 0, 10, 0)));
        var newGroup = snapshot("new.service", 1_000,
                Map.of("8:0", new DeviceStats(1_000_000, 0, 100_000, 0)));
        var coverage = new Coverage(2, 1, true, true);
        var collection = new CollectionResult<>(
                CgroupMonitor.Status.AVAILABLE,
                List.of(current, newGroup), Map.of(path("worker.service"), previous),
                coverage, 1_000);
        var result = new IoPressureAttributor(() -> collection, 5, 1.2, MAXIMUM_GAP).attribute();

        assertEquals("worker.service", result.workload().name());
        assertEquals(Confidence.LOW, result.confidence());
        assertEquals(coverage, result.coverage());
        assertEquals(1_000L, result.collectedAt());
        assertEquals(1, value(result, "candidates.unmeasured"));
        assertEquals(1, value(result, "devices.unmeasured"));
    }

    @Test
    void zeroActivityAndEmptyTopologyNeverIdentifyAWorkloadEvenAtZeroMinimumScore() {
        var current = snapshot("worker.service", 1_000, Map.of("8:0", ZERO));
        var previous = snapshot("worker.service", 0, Map.of("8:0", ZERO));
        var collection = collection(List.of(current), Map.of(path("worker.service"), previous));
        var result = new IoPressureAttributor(() -> collection, 0, 1.2, MAXIMUM_GAP).attribute();

        assertEquals(Status.NO_CANDIDATE, result.status());
        assertEquals(Status.NO_CANDIDATE, attribute(List.of(), Map.of()).status());
    }

    @Test
    void emptyStatNeedsNoInventedDeviceAndSettlesToNoCandidate() {
        var first = snapshot("worker.service", 1_000, Map.of());
        var second = snapshot("worker.service", 2_000, Map.of());

        assertEquals(Status.INSUFFICIENT_DATA, attribute(List.of(first), Map.of()).status());
        assertEquals(Status.NO_CANDIDATE,
                attribute(List.of(second), Map.of(path("worker.service"), first)).status());
    }

    @Test
    void aSmallLeadOrScoreBelowTheConfiguredMinimumIsNotEnough() {
        var a = snapshot("a.service", 1_000, Map.of("8:0", new DeviceStats(55, 0, 55, 0)));
        var b = snapshot("b.service", 1_000, Map.of("8:0", new DeviceStats(45, 0, 45, 0)));
        var collection = collection(List.of(a, b), Map.of(
                path("a.service"), snapshot("a.service", 0, Map.of("8:0", ZERO)),
                path("b.service"), snapshot("b.service", 0, Map.of("8:0", ZERO))));

        assertEquals(Status.NO_CANDIDATE,
                new IoPressureAttributor(() -> collection, 5, 1.3, MAXIMUM_GAP).attribute().status());
        assertEquals(Status.NO_CANDIDATE,
                new IoPressureAttributor(() -> collection, 60, 1.2, MAXIMUM_GAP).attribute().status());
    }

    @Test
    void addingLargeReadAndWriteCountersDoesNotOverflowBeforeScoring() {
        var current = snapshot("worker.service", 1_000,
                Map.of("8:0", new DeviceStats(Long.MAX_VALUE, Long.MAX_VALUE,
                        Long.MAX_VALUE, Long.MAX_VALUE)));
        var result = attribute(List.of(current), Map.of(
                path("worker.service"), snapshot("worker.service", 0, Map.of("8:0", ZERO))));

        assertEquals(100, result.score());
        assertTrue(result.evidence().stream().allMatch(metric -> Double.isFinite(metric.value())));
    }

    @Test
    void unavailableCollectionsPreserveStatusCoverageAndTimestamp() {
        var coverage = new Coverage(0, 1, true, false);
        for (CgroupMonitor.Status status : List.of(
                CgroupMonitor.Status.ACCESS_DENIED, CgroupMonitor.Status.CGROUP_V2_NOT_AVAILABLE)) {
            var collection = new CollectionResult<CgroupIoSnapshot>(
                    status, List.of(), Map.of(), coverage, 1_000);
            var result = new IoPressureAttributor(() -> collection, 5, 1.2, MAXIMUM_GAP).attribute();

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
                () -> new IoPressureAttributor(() -> empty, Double.NaN, 1.2, MAXIMUM_GAP));
        assertThrows(IllegalArgumentException.class,
                () -> new IoPressureAttributor(() -> empty, -1, 1.2, MAXIMUM_GAP));
        assertThrows(IllegalArgumentException.class,
                () -> new IoPressureAttributor(() -> empty, 5, 1, MAXIMUM_GAP));
        assertThrows(IllegalArgumentException.class,
                () -> new IoPressureAttributor(() -> empty, 5, Double.POSITIVE_INFINITY, MAXIMUM_GAP));
        assertThrows(IllegalArgumentException.class,
                () -> new IoPressureAttributor(() -> empty, 5, 1.2, Duration.ZERO));
    }

    private static WorkloadAttribution attribute(
            List<CgroupIoSnapshot> current, Map<String, CgroupIoSnapshot> previous) {
        var collection = collection(current, previous);
        return new IoPressureAttributor(() -> collection, 5, 1.2, MAXIMUM_GAP).attribute();
    }

    private static CollectionResult<CgroupIoSnapshot> collection(
            List<CgroupIoSnapshot> current, Map<String, CgroupIoSnapshot> previous) {
        return new CollectionResult<>(
                CgroupMonitor.Status.AVAILABLE,
                current,
                previous,
                Coverage.complete(current.size()),
                current.stream().mapToLong(CgroupIoSnapshot::collectedAt).max().orElse(0));
    }

    private static CgroupIoSnapshot snapshot(
            String name, long timestamp, Map<String, DeviceStats> devices) {
        return detailed(name, timestamp, devices, Map.of(), false, Double.NaN, Double.NaN);
    }

    private static CgroupIoSnapshot detailed(
            String name,
            long timestamp,
            Map<String, DeviceStats> devices,
            Map<String, DeviceLimit> limits,
            boolean limitsAvailable,
            double psiSome,
            double psiFull) {
        return new CgroupIoSnapshot(WorkloadIdentity.fromCgroupPath(path(name)),
                devices, limits, limitsAvailable, psiSome, psiFull, timestamp);
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
