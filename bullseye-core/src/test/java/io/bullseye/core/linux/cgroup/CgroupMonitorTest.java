package io.bullseye.core.linux.cgroup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.workload.WorkloadIdentity;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.logging.BullseyeLogger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class CgroupMonitorTest {

    private Path root;
    private Path group;
    private FakeFiles files;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        root = Path.of("/fixture/cgroup");
        group = root.resolve("system.slice/order-api.service");
        files = new FakeFiles();
        files.content.put(root.resolve("cgroup.controllers"), "cpu memory io");
        files.directories.put(root, List.of(group));
        clock = new MutableClock();
        fixtureGroup("800", "1000", 20, 3, "high 4\nmax 2\noom 1\noom_kill 0\n");
    }

    @Test
    void collectsMemoryPressureAndEventCounters() {
        CgroupMonitor.CollectionResult result = monitor(512).collectMemory();
        CgroupMemorySnapshot snapshot = result.snapshots().getFirst();

        assertEquals(800, snapshot.currentBytes());
        assertEquals(1_000, snapshot.maxBytes());
        assertEquals(20, snapshot.psiSome());
        assertEquals(3, snapshot.psiFull());
        assertEquals(4, snapshot.highEvents());
        assertEquals(2, snapshot.maxEvents());
        assertEquals(1, snapshot.oomEvents());
        assertEquals(0, snapshot.oomKillEvents());
        assertTrue(result.coverage().complete());
        assertEquals(1, result.coverage().collectedGroups());
        assertEquals(0, result.collectedAt());
    }

    @Test
    void collectsMemoryHighSeparatelyFromHardLimit() {
        files.content.put(group.resolve("memory.high"), "900\n");

        CgroupMonitor.CollectionResult result = monitor(512).collectMemory();
        CgroupMemorySnapshot snapshot = result.snapshots().getFirst();

        assertEquals(900, snapshot.highBytes());
        assertEquals(1_000, snapshot.maxBytes());
        assertTrue(result.coverage().complete());
    }

    @Test
    void unlimitedAndMissingMemoryHighRemainUnavailableWithoutCollectionFailure() {
        CgroupMonitor.CollectionResult missing = monitor(512).collectMemory();
        assertNull(missing.snapshots().getFirst().highBytes());
        assertTrue(missing.coverage().complete());

        files.content.put(group.resolve("memory.high"), "max\n");
        CgroupMonitor.CollectionResult unlimited = monitor(512).collectMemory();

        assertNull(unlimited.snapshots().getFirst().highBytes());
        assertEquals(1_000, unlimited.snapshots().getFirst().maxBytes());
        assertTrue(unlimited.coverage().complete());
    }

    @Test
    void zeroMemoryHighIsAValidConfiguredBoundary() {
        files.content.put(group.resolve("memory.high"), "0\n");

        CgroupMonitor.CollectionResult result = monitor(512).collectMemory();

        assertEquals(0, result.snapshots().getFirst().highBytes());
        assertTrue(result.coverage().complete());
    }

    @Test
    void malformedAndNegativeMemoryHighMarkTheGroupAsFailed() {
        for (String high : List.of("broken", "-1", "900 extra", "")) {
            files.content.put(group.resolve("memory.high"), high);

            CgroupMonitor.CollectionResult result = monitor(512).collectMemory();

            assertTrue(result.snapshots().isEmpty());
            assertEquals(1, result.coverage().failedGroups());
            assertFalse(result.coverage().complete());
        }
    }

    @Test
    void deniedMemoryHighKeepsOtherGroupsAndMarksPartialCoverage() {
        Path second = root.resolve("batch.service");
        files.directories.put(root, List.of(group, second));
        fixtureGroup(second, "400", "max", 2, 0, "high 0\n");
        files.denied.add(group.resolve("memory.high"));

        CgroupMonitor.CollectionResult result = monitor(512).collectMemory();

        assertEquals(CgroupMonitor.Status.AVAILABLE, result.status());
        assertEquals("batch.service", result.snapshots().getFirst().workload().name());
        assertEquals(1, result.coverage().collectedGroups());
        assertEquals(1, result.coverage().failedGroups());
        assertFalse(result.coverage().complete());
    }

    @Test
    void legacyMemorySnapshotConstructorLeavesHighUnavailable() {
        CgroupMemorySnapshot snapshot = new CgroupMemorySnapshot(
                WorkloadIdentity.fromCgroupPath("/test.service"),
                800, 1_000L, 20, 3, 4, 2, 1, 0, 5_000);

        assertNull(snapshot.highBytes());
        assertEquals(1_000, snapshot.maxBytes());
        assertEquals(5_000, snapshot.collectedAt());
    }

    @Test
    void memorySnapshotRejectsInfinitePressureButAcceptsUnavailablePressure() {
        WorkloadIdentity workload = WorkloadIdentity.fromCgroupPath("/test.service");
        for (double invalid : List.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> new CgroupMemorySnapshot(
                    workload, 800, null, null, invalid, 0, 0, 0, 0, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new CgroupMemorySnapshot(
                    workload, 800, null, null, 0, invalid, 0, 0, 0, 0, 0));
        }

        CgroupMemorySnapshot unavailable = new CgroupMemorySnapshot(
                workload, 800, null, null, Double.NaN, Double.NaN, 0, 0, 0, 0, 0);
        assertTrue(Double.isNaN(unavailable.psiSome()));
        assertTrue(Double.isNaN(unavailable.psiFull()));
    }

    @Test
    void mapsUnlimitedMemoryMaxToNull() {
        files.content.put(group.resolve("memory.max"), "max\n");

        CgroupMemorySnapshot snapshot = monitor(512).collectMemory().snapshots().getFirst();

        assertNull(snapshot.maxBytes());
    }

    @Test
    void retainsPreviousTriggerSnapshotForGrowthCalculation() {
        CgroupMonitor monitor = monitor(512);
        monitor.collectMemory();
        files.content.put(group.resolve("memory.current"), "1200\n");
        clock.millis = 5_000;

        CgroupMonitor.CollectionResult second = monitor.collectMemory();

        assertEquals(
                800,
                second.previousSnapshots().get("/system.slice/order-api.service").currentBytes());
        assertEquals(1_200, second.snapshots().getFirst().currentBytes());
    }

    @Test
    void cachesDiscoveryUntilRefreshInterval() {
        CgroupMonitor monitor = monitor(512);

        monitor.collectMemory();
        int initialReads = files.directoryReads;
        clock.millis = 30_000;
        monitor.collectMemory();
        assertEquals(initialReads, files.directoryReads);

        clock.millis = 60_000;
        monitor.collectMemory();
        assertTrue(files.directoryReads > initialReads);
    }

    @Test
    void skipsCgroupThatDisappearsDuringCollection() {
        files.missingDuringRead.add(group.resolve("memory.current"));

        CgroupMonitor.CollectionResult result = monitor(512).collectMemory();

        assertEquals(CgroupMonitor.Status.AVAILABLE, result.status());
        assertTrue(result.snapshots().isEmpty());
        assertEquals(1, result.coverage().failedGroups());
        assertFalse(result.coverage().complete());
    }

    @Test
    void returnsAccessDeniedWithoutThrowing() {
        files.denied.add(group.resolve("memory.pressure"));

        CgroupMonitor.CollectionResult result = monitor(512).collectMemory();

        assertEquals(CgroupMonitor.Status.ACCESS_DENIED, result.status());
        assertTrue(result.snapshots().isEmpty());
    }

    @Test
    void boundsNumberOfDiscoveredCgroups() {
        Path second = root.resolve("system.slice/batch.service");
        files.directories.put(root, List.of(group, second));
        fixtureGroup(second, "400", "max", 2, 0, "high 0\n");

        CgroupMonitor.CollectionResult result = monitor(1).collectMemory();

        assertEquals(1, result.snapshots().size());
        assertEquals("order-api.service", result.snapshots().getFirst().workload().name());
        assertTrue(result.coverage().discoveryLimited());
    }

    @Test
    void unsupportedCgroupV2ReturnsExplicitStatus() {
        files.content.remove(root.resolve("cgroup.controllers"));

        CgroupMonitor.CollectionResult result = monitor(512).collectMemory();

        assertEquals(CgroupMonitor.Status.CGROUP_V2_NOT_AVAILABLE, result.status());
        assertTrue(result.snapshots().isEmpty());
    }

    @Test
    void deniedCgroupControllerReturnsAccessDeniedStatus() {
        files.denied.add(root.resolve("cgroup.controllers"));

        CgroupMonitor.CollectionResult result = monitor(512).collectMemory();

        assertEquals(CgroupMonitor.Status.ACCESS_DENIED, result.status());
        assertTrue(result.snapshots().isEmpty());
    }

    @Test
    void cachesAnEmptyTopologyInsteadOfRediscoveringEveryTrigger() {
        files.directories.put(root, List.of());
        CgroupMonitor monitor = monitor(512);

        monitor.collectMemory();
        clock.millis = 5_000;
        monitor.collectMemory();

        assertEquals(1, files.directoryReads);
    }

    @Test
    void partialReadFailureKeepsSuccessfulWorkloadsButMarksCoverageIncomplete() {
        Path second = root.resolve("batch.service");
        files.directories.put(root, List.of(group, second));
        fixtureGroup(second, "400", "max", 2, 0, "high 0\n");
        files.denied.add(second.resolve("memory.pressure"));

        CgroupMonitor.CollectionResult result = monitor(512).collectMemory();

        assertEquals(CgroupMonitor.Status.AVAILABLE, result.status());
        assertEquals(1, result.coverage().collectedGroups());
        assertEquals(1, result.coverage().failedGroups());
        assertFalse(result.coverage().complete());
        assertEquals("order-api.service", result.snapshots().getFirst().workload().name());
    }

    @Test
    void optionalFilesDoNotHideAccessDeniedAsMissing() {
        files.denied.add(group.resolve("memory.pressure"));
        files.content.remove(group.resolve("memory.pressure"));

        CgroupMonitor.CollectionResult result = monitor(512).collectMemory();

        assertEquals(CgroupMonitor.Status.ACCESS_DENIED, result.status());
        assertEquals(1, result.coverage().failedGroups());
    }

    @Test
    void malformedGroupIsIncludedInFailureCount() {
        files.content.put(group.resolve("memory.current"), "not-a-counter");

        CgroupMonitor.CollectionResult result = monitor(512).collectMemory();

        assertEquals(1, result.coverage().failedGroups());
        assertTrue(result.snapshots().isEmpty());
        assertFalse(result.coverage().complete());
    }

    @Test
    void cachedDiscoveryRetainsFailureUntilSuccessfulRediscovery() {
        files.deniedDirectories.add(group);
        CgroupMonitor monitor = monitor(512);
        CgroupMonitor.CollectionResult first = monitor.collectMemory();
        assertTrue(first.coverage().discoveryFailed());
        assertEquals(1, first.snapshots().size());
        int directoryReads = files.directoryReads;

        files.deniedDirectories.clear();
        clock.millis = 5_000;
        CgroupMonitor.CollectionResult cached = monitor.collectMemory();
        assertEquals(directoryReads, files.directoryReads);
        assertTrue(cached.coverage().discoveryFailed());

        clock.millis = 60_000;
        CgroupMonitor.CollectionResult recovered = monitor.collectMemory();
        assertTrue(recovered.coverage().complete());
        assertEquals(60_000, recovered.collectedAt());
    }

    @Test
    void depthLimitMarksUnvisitedDescendantsAsIncomplete() {
        Path child = group.resolve("worker");
        files.directories.put(group, List.of(child));
        fixtureGroup(child, "100", "max", 0, 0, "high 0\n");

        CgroupMonitor.CollectionResult result = monitor(512, 1, true).collectMemory();

        assertEquals(1, result.snapshots().size());
        assertTrue(result.coverage().discoveryLimited());
        assertFalse(result.coverage().complete());
    }

    @Test
    void exactGroupAndDepthLimitsWithoutOmittedChildrenRemainComplete() {
        CgroupMonitor.CollectionResult result = monitor(1, 1, true).collectMemory();

        assertEquals(1, result.snapshots().size());
        assertTrue(result.coverage().complete());
    }

    @Test
    void retriesDetectionAfterInitialPermissionFailure() {
        Path controllers = root.resolve("cgroup.controllers");
        files.denied.add(controllers);
        CgroupMonitor monitor = monitor(512);
        assertEquals(CgroupMonitor.Status.ACCESS_DENIED, monitor.collectMemory().status());

        files.denied.clear();
        clock.millis = 5_000;
        CgroupMonitor.CollectionResult recovered = monitor.collectMemory();

        assertEquals(CgroupMonitor.Status.AVAILABLE, recovered.status());
        assertEquals(1, recovered.snapshots().size());
        assertTrue(recovered.coverage().complete());
    }

    @Test
    void disabledConfigurationNeverProbesController() {
        files.denied.add(root.resolve("cgroup.controllers"));
        CgroupMonitor monitor = monitor(512, 8, false);

        assertEquals(
                CgroupMonitor.Status.CGROUP_V2_NOT_AVAILABLE, monitor.collectMemory().status());
        clock.millis = 60_000;
        assertEquals(
                CgroupMonitor.Status.CGROUP_V2_NOT_AVAILABLE, monitor.collectMemory().status());
        assertEquals(0, files.directoryReads);
        assertEquals(0, files.fileReads);
    }

    @Test
    void failedReadInvalidatesDiscoveryAndRemovesOldHistory() {
        CgroupMonitor monitor = monitor(512);
        monitor.collectMemory();
        files.missingDuringRead.add(group.resolve("memory.current"));
        clock.millis = 5_000;
        assertEquals(1, monitor.collectMemory().coverage().failedGroups());

        Path replacement = root.resolve("replacement.service");
        files.directories.put(root, List.of(replacement));
        fixtureGroup(replacement, "400", "max", 2, 0, "high 0\n");
        clock.millis = 10_000;
        CgroupMonitor.CollectionResult refreshed = monitor.collectMemory();

        assertTrue(refreshed.coverage().complete());
        assertEquals("replacement.service", refreshed.snapshots().getFirst().workload().name());
        assertTrue(refreshed.previousSnapshots().isEmpty());
    }

    @Test
    void historyResetRefreshesTopologyForNextPressureEpisode() {
        CgroupMonitor monitor = monitor(512);
        monitor.collectMemory();
        Path replacement = root.resolve("replacement.service");
        files.directories.put(root, List.of(replacement));
        fixtureGroup(replacement, "400", "max", 2, 0, "high 0\n");

        monitor.clearHistory();
        clock.millis = 5_000;
        CgroupMonitor.CollectionResult nextEpisode = monitor.collectMemory();

        assertEquals("replacement.service", nextEpisode.snapshots().getFirst().workload().name());
        assertTrue(nextEpisode.previousSnapshots().isEmpty());
    }

    @Test
    void collectsCpuUsageQuotaPressureAndOptionalThrottleCounters() {
        fixtureCpu(group, 4_000_000);
        files.content.put(group.resolve("cpu.max"), "200000 100000\n");
        clock.millis = 2_000;

        CgroupMonitor.CpuCollectionResult result = monitor(512).collectCpu();
        CgroupCpuSnapshot snapshot = result.snapshots().getFirst();

        assertEquals(4_000_000, snapshot.usageUsec());
        assertEquals(3_000_000, snapshot.userUsec());
        assertEquals(1_000_000, snapshot.systemUsec());
        assertEquals(200_000, snapshot.quotaUsec());
        assertEquals(100_000, snapshot.periodUsec());
        assertEquals(20, snapshot.nrPeriods());
        assertEquals(3, snapshot.nrThrottled());
        assertEquals(50_000, snapshot.throttledUsec());
        assertEquals(15, snapshot.psiSome());
        assertEquals(2_000, snapshot.collectedAt());
        assertEquals(2_000, result.collectedAt());
        assertTrue(result.coverage().complete());
        assertTrue(result.previousSnapshots().isEmpty());
    }

    @Test
    void missingOptionalCpuFilesAndCountersRemainUnavailable() {
        files.content.put(
                group.resolve("cpu.stat"),
                "usage_usec 100\nuser_usec 80\nsystem_usec 20\n");

        CgroupMonitor.CpuCollectionResult result = monitor(512).collectCpu();
        CgroupCpuSnapshot snapshot = result.snapshots().getFirst();

        assertNull(snapshot.quotaUsec());
        assertNull(snapshot.periodUsec());
        assertNull(snapshot.nrPeriods());
        assertNull(snapshot.nrThrottled());
        assertNull(snapshot.throttledUsec());
        assertTrue(Double.isNaN(snapshot.psiSome()));
        assertTrue(result.coverage().complete());
    }

    @Test
    void unlimitedCpuQuotaPreservesPeriodWithoutInventingLimit() {
        fixtureCpu(group, 4_000_000);

        CgroupCpuSnapshot snapshot = monitor(512).collectCpu().snapshots().getFirst();

        assertNull(snapshot.quotaUsec());
        assertEquals(100_000, snapshot.periodUsec());
    }

    @Test
    void rejectsMissingNegativeAndDuplicateRequiredCpuCounters() {
        for (String counters : List.of(
                "usage_usec 100\nuser_usec 80\n",
                "usage_usec -100\nuser_usec 80\nsystem_usec 20\n",
                "usage_usec 100\nusage_usec 200\nuser_usec 80\nsystem_usec 20\n",
                "usage_usec broken\nuser_usec 80\nsystem_usec 20\n")) {
            files.content.put(group.resolve("cpu.stat"), counters);

            CgroupMonitor.CpuCollectionResult result = monitor(512).collectCpu();

            assertTrue(result.snapshots().isEmpty());
            assertEquals(1, result.coverage().failedGroups());
            assertFalse(result.coverage().complete());
        }
    }

    @Test
    void invalidCpuQuotaAndDeniedPressureAreCollectionFailures() {
        fixtureCpu(group, 4_000_000);
        for (String maximum : List.of("max", "max 0", "0 100000", "100000 broken")) {
            files.content.put(group.resolve("cpu.max"), maximum);

            CgroupMonitor.CpuCollectionResult result = monitor(512).collectCpu();

            assertTrue(result.snapshots().isEmpty());
            assertEquals(1, result.coverage().failedGroups());
        }

        files.content.put(group.resolve("cpu.max"), "max 100000");
        files.content.remove(group.resolve("cpu.pressure"));
        files.denied.add(group.resolve("cpu.pressure"));
        CgroupMonitor.CpuCollectionResult denied = monitor(512).collectCpu();

        assertEquals(CgroupMonitor.Status.ACCESS_DENIED, denied.status());
        assertEquals(1, denied.coverage().failedGroups());
    }

    @Test
    void cpuAndMemoryFailureCoverageRemainIndependent() {
        Path second = root.resolve("batch.service");
        files.directories.put(root, List.of(group, second));
        fixtureGroup(second, "400", "max", 2, 0, "high 0\n");
        fixtureCpu(group, 4_000_000);
        fixtureCpu(second, 2_000_000);
        files.denied.add(second.resolve("cpu.stat"));
        CgroupMonitor monitor = monitor(512);

        CgroupMonitor.CpuCollectionResult partialCpu = monitor.collectCpu();
        assertEquals(CgroupMonitor.Status.AVAILABLE, partialCpu.status());
        assertEquals(1, partialCpu.coverage().collectedGroups());
        assertEquals(1, partialCpu.coverage().failedGroups());
        assertFalse(partialCpu.coverage().complete());
        assertTrue(monitor.collectMemory().coverage().complete());

        files.denied.clear();
        files.denied.add(second.resolve("memory.current"));
        assertEquals(1, monitor.collectMemory().coverage().failedGroups());
        assertTrue(monitor.collectCpu().coverage().complete());
    }

    @Test
    void resetsCpuAndMemoryHistoryIndependently() {
        fixtureCpu(group, 4_000_000);
        CgroupMonitor monitor = monitor(512);
        monitor.collectCpu();
        monitor.collectMemory();
        monitor.clearHistory();
        clock.millis = 1_000;
        fixtureCpu(group, 8_000_000);

        CgroupMonitor.CpuCollectionResult secondCpu = monitor.collectCpu();
        assertEquals(
                4_000_000,
                secondCpu.previousSnapshots().get("/system.slice/order-api.service").usageUsec());
        assertEquals(8_000_000, secondCpu.snapshots().getFirst().usageUsec());
        assertTrue(monitor.collectMemory().previousSnapshots().isEmpty());

        monitor.clearCpuHistory();
        clock.millis = 2_000;
        assertFalse(monitor.collectMemory().previousSnapshots().isEmpty());
        assertTrue(monitor.collectCpu().previousSnapshots().isEmpty());
    }

    @Test
    void failedCpuReadClearsBaselineBeforeRecovery() {
        fixtureCpu(group, 4_000_000);
        CgroupMonitor monitor = monitor(512);
        monitor.collectCpu();
        files.missingDuringRead.add(group.resolve("cpu.stat"));
        clock.millis = 1_000;

        assertEquals(1, monitor.collectCpu().coverage().failedGroups());

        files.missingDuringRead.clear();
        fixtureCpu(group, 12_000_000);
        clock.millis = 2_000;
        CgroupMonitor.CpuCollectionResult recovered = monitor.collectCpu();
        assertTrue(recovered.coverage().complete());
        assertTrue(recovered.previousSnapshots().isEmpty());
    }

    @Test
    void cpuAndMemoryShareDiscoveryAndOnlyCollectLeafCandidates() {
        Path child = group.resolve("worker");
        files.directories.put(group, List.of(child));
        fixtureCpu(group, 8_000_000);
        fixtureCpu(child, 4_000_000);
        fixtureGroup(child, "400", "max", 2, 0, "high 0\n");
        CgroupMonitor monitor = monitor(512);

        CgroupMonitor.CpuCollectionResult cpu = monitor.collectCpu();
        int discoveryReads = files.directoryReads;
        CgroupMonitor.CollectionResult memory = monitor.collectMemory();

        assertEquals(1, cpu.snapshots().size());
        assertEquals(
                "/system.slice/order-api.service/worker",
                cpu.snapshots().getFirst().workload().cgroupPath());
        assertEquals(4_000_000, cpu.snapshots().getFirst().usageUsec());
        assertTrue(cpu.coverage().complete());
        assertEquals(1, memory.snapshots().size());
        assertEquals(discoveryReads, files.directoryReads);
    }

    @Test
    void cpuReportsDiscoveryLimitsAndUnsupportedCgroup() {
        fixtureCpu(group, 4_000_000);
        files.directories.put(group, List.of(group.resolve("worker")));

        CgroupMonitor.CpuCollectionResult limited = monitor(512, 1, true).collectCpu();

        assertTrue(limited.coverage().discoveryLimited());
        assertFalse(limited.coverage().complete());

        files.content.remove(root.resolve("cgroup.controllers"));
        CgroupMonitor.CpuCollectionResult unsupported = monitor(512).collectCpu();
        assertEquals(CgroupMonitor.Status.CGROUP_V2_NOT_AVAILABLE, unsupported.status());
        assertTrue(unsupported.snapshots().isEmpty());
        assertTrue(unsupported.coverage().discoveryFailed());
    }

    @Test
    void successfulMemoryReadDoesNotRestoreFailedCpuComponent() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        CgroupMonitor monitor =
                monitor(
                        512,
                        8,
                        true,
                        new BullseyeLogger(new PrintStream(output), new PrintStream(errors)));
        fixtureCpu(group, 4_000_000);
        files.denied.add(group.resolve("cpu.stat"));
        monitor.collectCpu();
        monitor.collectMemory();

        assertFalse(output.toString().contains("cgroup-cpu:"));
        assertTrue(errors.toString().contains("cgroup-cpu:"));

        files.denied.clear();
        monitor.collectCpu();
        assertTrue(output.toString().contains("cgroup-cpu:"));
    }

    @Test
    void collectsIoCountersAndLimitsForEachDeviceWithoutDependingOnFieldOrder() {
        files.content.put(
                group.resolve("io.stat"),
                "8:0 wios=4 rios=3 wbytes=200 rbytes=100 dbytes=0 dios=0\n"
                        + "259:1 rbytes=300 wbytes=400 rios=5 wios=6 extra=7\n");
        files.content.put(
                group.resolve("io.max"),
                "259:1 wiops=10 rbps=max wbps=4096 riops=max\n8:0 rbps=1024\n");
        files.content.put(group.resolve("io.pressure"), psi(20, 3));
        clock.millis = 2_000;

        CgroupMonitor.IoCollectionResult result = monitor(512).collectIo();
        CgroupIoSnapshot snapshot = result.snapshots().getFirst();

        assertEquals(new CgroupIoSnapshot.DeviceStats(100, 200, 3, 4), snapshot.devices().get("8:0"));
        assertEquals(new CgroupIoSnapshot.DeviceStats(300, 400, 5, 6), snapshot.devices().get("259:1"));
        assertEquals(1_024L, snapshot.limits().get("8:0").readBytesPerSecond());
        assertNull(snapshot.limits().get("8:0").writeBytesPerSecond());
        assertNull(snapshot.limits().get("259:1").readBytesPerSecond());
        assertEquals(4_096L, snapshot.limits().get("259:1").writeBytesPerSecond());
        assertEquals(10L, snapshot.limits().get("259:1").writeOperationsPerSecond());
        assertTrue(snapshot.limitsAvailable());
        assertEquals(20, snapshot.psiSome());
        assertEquals(3, snapshot.psiFull());
        assertEquals(2_000, result.collectedAt());
        assertTrue(result.coverage().complete());
    }

    @Test
    void emptyIoStatAndIoMaxAreValidAndDifferentFromUnavailableLimits() {
        files.content.put(group.resolve("io.stat"), "\n");
        files.content.put(group.resolve("io.max"), "\n");

        CgroupMonitor.IoCollectionResult result = monitor(512).collectIo();
        CgroupIoSnapshot snapshot = result.snapshots().getFirst();

        assertTrue(snapshot.devices().isEmpty());
        assertTrue(snapshot.limits().isEmpty());
        assertTrue(snapshot.limitsAvailable());
        assertTrue(Double.isNaN(snapshot.psiSome()));
        assertTrue(Double.isNaN(snapshot.psiFull()));
        assertTrue(result.coverage().complete());

        files.content.remove(group.resolve("io.max"));
        CgroupIoSnapshot missing = monitor(512).collectIo().snapshots().getFirst();
        assertFalse(missing.limitsAvailable());
    }

    @Test
    void ioPressureMayLackFullAndIoMaxMayBeUnavailable() {
        fixtureIo(group, 100);
        files.content.remove(group.resolve("io.max"));
        files.content.put(group.resolve("io.pressure"), "some avg10=5 avg60=1 avg300=1 total=10\n");

        CgroupMonitor.IoCollectionResult result = monitor(512).collectIo();
        CgroupIoSnapshot snapshot = result.snapshots().getFirst();

        assertEquals(5, snapshot.psiSome());
        assertTrue(Double.isNaN(snapshot.psiFull()));
        assertFalse(snapshot.limitsAvailable());
        assertTrue(result.coverage().complete());
    }

    @Test
    void ioStatAcceptsKernelControllerExtensionsAndOmittedZeroCounters() {
        files.content.put(
                group.resolve("io.stat"),
                "8:0 rbytes=100 wbytes=200 rios=3 wios=4 use_delay=-1 cost.vrate=99.25\n"
                        + "8:1\n"
                        + "259:0 cost.usage=0 cost.vrate=100.00 future=text\n");

        CgroupMonitor.IoCollectionResult result = monitor(512).collectIo();
        CgroupIoSnapshot snapshot = result.snapshots().getFirst();

        assertEquals(new CgroupIoSnapshot.DeviceStats(100, 200, 3, 4), snapshot.devices().get("8:0"));
        assertEquals(new CgroupIoSnapshot.DeviceStats(0, 0, 0, 0), snapshot.devices().get("8:1"));
        assertEquals(new CgroupIoSnapshot.DeviceStats(0, 0, 0, 0), snapshot.devices().get("259:0"));
        assertTrue(result.coverage().complete());
    }

    @Test
    void rejectsMalformedIoStatWithoutTreatingItAsAnIdleWorkload() {
        String valid = "8:0 rbytes=100 wbytes=200 rios=3 wios=4";
        for (String stat : List.of(
                "8:0 rbytes=100 wbytes=200 rios=3",
                "8:0 rbytes=-1 wbytes=200 rios=3 wios=4",
                "8:0 rbytes=max wbytes=200 rios=3 wios=4",
                valid + " rbytes=100",
                valid + "\n" + valid,
                "08:0 rbytes=100 wbytes=200 rios=3 wios=4",
                "8:x rbytes=100 wbytes=200 rios=3 wios=4",
                valid + " broken",
                valid + " extra=",
                valid + " extra=1=2",
                valid + " dbytes=-1",
                valid + " dios=broken")) {
            files.content.put(group.resolve("io.stat"), stat);

            CgroupMonitor.IoCollectionResult result = monitor(512).collectIo();

            assertTrue(result.snapshots().isEmpty(), stat);
            assertEquals(1, result.coverage().failedGroups(), stat);
        }
    }

    @Test
    void missingRequiredIoStatMarksCollectionAsFailed() {
        CgroupMonitor.IoCollectionResult result = monitor(512).collectIo();

        assertEquals(CgroupMonitor.Status.AVAILABLE, result.status());
        assertTrue(result.snapshots().isEmpty());
        assertEquals(1, result.coverage().failedGroups());
        assertFalse(result.coverage().complete());
    }

    @Test
    void rejectsMalformedOptionalIoLimitsAndPressureInsteadOfHidingTheFailure() {
        fixtureIo(group, 100);
        for (String maximum : List.of(
                "8:0 rbps=0",
                "8:0 wbps=-1",
                "8:0 riops=broken",
                "8:0 wiops=max wiops=max",
                "8:0 rbps=max\n8:0 wbps=max",
                "broken rbps=max",
                "8:0")) {
            files.content.put(group.resolve("io.max"), maximum);

            CgroupMonitor.IoCollectionResult result = monitor(512).collectIo();

            assertTrue(result.snapshots().isEmpty(), maximum);
            assertEquals(1, result.coverage().failedGroups(), maximum);
        }
        files.content.put(group.resolve("io.max"), "");
        files.content.put(group.resolve("io.pressure"), "broken");
        assertEquals(1, monitor(512).collectIo().coverage().failedGroups());
    }

    @Test
    void deniedOptionalIoFilesAreAccessFailures() {
        fixtureIo(group, 100);
        for (String file : List.of("io.max", "io.pressure")) {
            files.denied.add(group.resolve(file));

            CgroupMonitor.IoCollectionResult result = monitor(512).collectIo();

            assertEquals(CgroupMonitor.Status.ACCESS_DENIED, result.status());
            assertEquals(1, result.coverage().failedGroups());
            files.denied.clear();
        }
    }

    @Test
    void ioFailureKeepsReadableGroupsAndDoesNotAffectOtherResources() {
        Path second = root.resolve("batch.service");
        files.directories.put(root, List.of(group, second));
        fixtureIo(group, 100);
        fixtureIo(second, 200);
        fixtureCpu(group, 1_000);
        fixtureCpu(second, 2_000);
        fixtureGroup(second, "400", "max", 2, 0, "high 0\n");
        files.denied.add(second.resolve("io.stat"));
        CgroupMonitor monitor = monitor(512);

        CgroupMonitor.IoCollectionResult result = monitor.collectIo();

        assertEquals(CgroupMonitor.Status.AVAILABLE, result.status());
        assertEquals(1, result.coverage().collectedGroups());
        assertEquals(1, result.coverage().failedGroups());
        assertFalse(result.coverage().complete());
        assertTrue(monitor.collectCpu().coverage().complete());
        assertTrue(monitor.collectMemory().coverage().complete());
    }

    @Test
    void resetsIoHistoryIndependentlyFromCpuAndMemory() {
        fixtureIo(group, 100);
        fixtureCpu(group, 1_000);
        CgroupMonitor monitor = monitor(512);
        monitor.collectIo();
        monitor.collectCpu();
        monitor.collectMemory();
        monitor.clearCpuHistory();
        monitor.clearHistory();
        clock.millis = 1_000;
        fixtureIo(group, 300);

        CgroupMonitor.IoCollectionResult secondIo = monitor.collectIo();
        assertEquals(
                100,
                secondIo.previousSnapshots().get("/system.slice/order-api.service")
                        .devices().get("8:0").readBytes());
        assertEquals(300, secondIo.snapshots().getFirst().devices().get("8:0").readBytes());
        assertTrue(monitor.collectCpu().previousSnapshots().isEmpty());
        assertTrue(monitor.collectMemory().previousSnapshots().isEmpty());

        monitor.clearIoHistory();
        clock.millis = 2_000;
        assertTrue(monitor.collectIo().previousSnapshots().isEmpty());
        assertFalse(monitor.collectCpu().previousSnapshots().isEmpty());
        assertFalse(monitor.collectMemory().previousSnapshots().isEmpty());
    }

    @Test
    void failedIoReadClearsBaselineBeforeRecovery() {
        fixtureIo(group, 100);
        CgroupMonitor monitor = monitor(512);
        monitor.collectIo();
        files.missingDuringRead.add(group.resolve("io.stat"));
        clock.millis = 1_000;
        assertEquals(1, monitor.collectIo().coverage().failedGroups());

        files.missingDuringRead.clear();
        fixtureIo(group, 300);
        clock.millis = 2_000;
        CgroupMonitor.IoCollectionResult recovered = monitor.collectIo();

        assertTrue(recovered.coverage().complete());
        assertTrue(recovered.previousSnapshots().isEmpty());
    }

    @Test
    void ioSharesLeafDiscoveryAndPrunesHistoryForRemovedGroups() {
        Path child = group.resolve("worker");
        files.directories.put(group, List.of(child));
        fixtureIo(group, 100);
        fixtureIo(child, 200);
        fixtureCpu(child, 2_000);
        CgroupMonitor monitor = monitor(512);

        CgroupMonitor.IoCollectionResult initial = monitor.collectIo();
        assertEquals(1, initial.snapshots().size());
        assertEquals(200, initial.snapshots().getFirst().devices().get("8:0").readBytes());
        int discoveryReads = files.directoryReads;
        monitor.collectCpu();
        assertEquals(discoveryReads, files.directoryReads);

        files.directories.put(group, List.of());
        clock.millis = 60_000;
        CgroupMonitor.IoCollectionResult removed = monitor.collectIo();
        assertTrue(removed.previousSnapshots().isEmpty());
        assertEquals(100, removed.snapshots().getFirst().devices().get("8:0").readBytes());

        files.directories.put(group, List.of(child));
        clock.millis = 120_000;
        assertTrue(monitor.collectIo().previousSnapshots().isEmpty());
    }

    @Test
    void ioReportsDiscoveryLimitsAndRecoversAfterCgroupBecomesAvailable() {
        fixtureIo(group, 100);
        files.directories.put(group, List.of(group.resolve("worker")));
        assertTrue(monitor(512, 1, true).collectIo().coverage().discoveryLimited());
        files.directories.put(group, List.of());
        files.content.remove(root.resolve("cgroup.controllers"));
        CgroupMonitor monitor = monitor(512);

        CgroupMonitor.IoCollectionResult unavailable = monitor.collectIo();
        assertEquals(CgroupMonitor.Status.CGROUP_V2_NOT_AVAILABLE, unavailable.status());
        assertTrue(unavailable.coverage().discoveryFailed());

        files.content.put(root.resolve("cgroup.controllers"), "cpu memory io");
        CgroupMonitor.IoCollectionResult recovered = monitor.collectIo();
        assertEquals(CgroupMonitor.Status.AVAILABLE, recovered.status());
        assertTrue(recovered.previousSnapshots().isEmpty());
        assertTrue(recovered.coverage().complete());
    }

    @Test
    void successfulMemoryReadDoesNotRestoreFailedIoComponent() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        CgroupMonitor monitor =
                monitor(
                        512,
                        8,
                        true,
                        new BullseyeLogger(new PrintStream(output), new PrintStream(errors)));
        fixtureIo(group, 100);
        files.denied.add(group.resolve("io.stat"));
        monitor.collectIo();
        monitor.collectMemory();

        assertFalse(output.toString().contains("cgroup-io:"));
        assertTrue(errors.toString().contains("cgroup-io:"));

        files.denied.clear();
        monitor.collectIo();
        assertTrue(output.toString().contains("cgroup-io:"));
    }

    private void fixtureIo(Path target, long readBytes) {
        files.content.put(
                target.resolve("io.stat"),
                "8:0 rbytes=" + readBytes + " wbytes=200 rios=3 wios=4\n");
        files.content.put(target.resolve("io.max"), "8:0 rbps=max wbps=max riops=max wiops=max\n");
        files.content.put(target.resolve("io.pressure"), psi(5, 1));
    }

    private CgroupMonitor monitor(int maximumGroups) {
        return monitor(maximumGroups, 8, true);
    }

    private CgroupMonitor monitor(int maximumGroups, int maximumDepth, boolean enabled) {
        return monitor(
                maximumGroups,
                maximumDepth,
                enabled,
                new BullseyeLogger(
                        new PrintStream(OutputStream.nullOutputStream()),
                        new PrintStream(OutputStream.nullOutputStream())));
    }

    private CgroupMonitor monitor(
            int maximumGroups, int maximumDepth, boolean enabled, BullseyeLogger logger) {
        return new CgroupMonitor(
                new BullseyeConfiguration.Cgroup(
                        enabled,
                        root,
                        Duration.ofSeconds(60),
                        maximumDepth,
                        maximumGroups,
                        5,
                        1.2),
                files,
                clock,
                logger);
    }

    private void fixtureCpu(Path target, long usageUsec) {
        files.content.put(
                target.resolve("cpu.stat"),
                "usage_usec "
                        + usageUsec
                        + "\nuser_usec "
                        + usageUsec * 3 / 4
                        + "\nsystem_usec "
                        + usageUsec / 4
                        + "\nnr_periods 20\nnr_throttled 3\nthrottled_usec 50000\n");
        files.content.put(target.resolve("cpu.max"), "max 100000\n");
        files.content.put(target.resolve("cpu.pressure"), psi(15, 0));
    }

    private void fixtureGroup(
            String memoryCurrent,
            String memoryMax,
            double psiSome,
            double psiFull,
            String events) {
        fixtureGroup(group, memoryCurrent, memoryMax, psiSome, psiFull, events);
    }

    private void fixtureGroup(
            Path target,
            String memoryCurrent,
            String memoryMax,
            double psiSome,
            double psiFull,
            String events) {
        files.content.put(target.resolve("memory.current"), memoryCurrent + "\n");
        files.content.put(target.resolve("memory.max"), memoryMax + "\n");
        files.content.put(target.resolve("memory.pressure"), psi(psiSome, psiFull));
        files.content.put(target.resolve("memory.events"), events);
    }

    private static String psi(double some, double full) {
        return "some avg10="
                + some
                + " avg60=1 avg300=1 total=10\nfull avg10="
                + full
                + " avg60=1 avg300=1 total=2\n";
    }

    private static final class FakeFiles implements CgroupMonitor.FileAccess {
        private final Map<Path, String> content = new HashMap<>();
        private final Map<Path, List<Path>> directories = new HashMap<>();
        private final Set<Path> denied = new HashSet<>();
        private final Set<Path> missingDuringRead = new HashSet<>();
        private final Set<Path> deniedDirectories = new HashSet<>();
        private int directoryReads;
        private int fileReads;

        @Override
        public boolean exists(Path path) {
            return content.containsKey(path) || directories.containsKey(path);
        }

        @Override
        public String read(Path path) throws IOException {
            fileReads++;
            if (denied.contains(path)) {
                throw new AccessDeniedException(path.toString());
            }
            if (missingDuringRead.contains(path)) {
                throw new NoSuchFileException(path.toString());
            }
            String value = content.get(path);
            if (value == null) {
                throw new NoSuchFileException(path.toString());
            }
            return value;
        }

        @Override
        public List<Path> directories(Path path) throws IOException {
            directoryReads++;
            if (deniedDirectories.contains(path)) {
                throw new AccessDeniedException(path.toString());
            }
            return new ArrayList<>(directories.getOrDefault(path, List.of()));
        }
    }

    private static final class MutableClock extends Clock {
        private long millis;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
