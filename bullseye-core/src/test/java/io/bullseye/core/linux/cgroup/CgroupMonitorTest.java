package io.bullseye.core.linux.cgroup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.logging.BullseyeLogger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private CgroupMonitor monitor(int maximumGroups) {
        return monitor(maximumGroups, 8, true);
    }

    private CgroupMonitor monitor(int maximumGroups, int maximumDepth, boolean enabled) {
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
                new BullseyeLogger(
                        new PrintStream(OutputStream.nullOutputStream()),
                        new PrintStream(OutputStream.nullOutputStream())));
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
