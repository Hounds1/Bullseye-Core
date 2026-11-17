package io.bullseye.core.linux.cgroup;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        CgroupMemorySnapshot snapshot = monitor(512).collectMemory().snapshots().getFirst();

        assertEquals(800, snapshot.currentBytes());
        assertEquals(1_000, snapshot.maxBytes());
        assertEquals(20, snapshot.psiSome());
        assertEquals(3, snapshot.psiFull());
        assertEquals(4, snapshot.highEvents());
        assertEquals(2, snapshot.maxEvents());
        assertEquals(1, snapshot.oomEvents());
        assertEquals(0, snapshot.oomKillEvents());
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

    private CgroupMonitor monitor(int maximumGroups) {
        return new CgroupMonitor(
                new BullseyeConfiguration.Cgroup(
                        true, root, Duration.ofSeconds(60), 8, maximumGroups, 5, 1.2),
                files,
                clock,
                new BullseyeLogger(
                        new PrintStream(OutputStream.nullOutputStream()),
                        new PrintStream(OutputStream.nullOutputStream())));
    }

    private void fixtureGroup(
            String memoryCurrent, String memoryMax, double psiSome, double psiFull, String events) {
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
        private int directoryReads;

        @Override
        public boolean exists(Path path) {
            return content.containsKey(path) || directories.containsKey(path);
        }

        @Override
        public String read(Path path) throws IOException {
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
        public List<Path> directories(Path path) {
            directoryReads++;
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
