package io.bullseye.core.linux.cgroup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        fixtureGroup("1000000", "800", "1000");
    }

    @Test
    void parsesCpuStatMemoryAndPressureFiles() {
        CgroupMonitor monitor = monitor(512);
        monitor.sample();
        files.content.put(group.resolve("cpu.stat"), "usage_usec 1500000\nuser_usec 1\n");
        clock.millis = 1_000;

        monitor.sample();
        CgroupResourceSnapshot snapshot = monitor.snapshots().getFirst();

        assertEquals(50, snapshot.cpuUsage(), 0.0001);
        assertEquals(800, snapshot.memoryCurrent());
        assertEquals(1_000, snapshot.memoryMax());
        assertEquals(12, snapshot.cpuPsiSome());
        assertEquals(20, snapshot.memoryPsiSome());
        assertEquals(3, snapshot.memoryPsiFull());
        assertEquals(30, snapshot.ioPsiSome());
        assertEquals(4, snapshot.ioPsiFull());
    }

    @Test
    void mapsUnlimitedMemoryMaxToUnavailableSentinel() {
        files.content.put(group.resolve("memory.max"), "max\n");
        CgroupMonitor monitor = monitor(512);

        monitor.sample();

        assertEquals(-1, monitor.snapshots().getFirst().memoryMax());
    }

    @Test
    void cachesDiscoveryUntilRefreshInterval() {
        CgroupMonitor monitor = monitor(512);

        monitor.sample();
        int initialDiscoveryReads = files.directoryReads;
        clock.millis = 30_000;
        monitor.sample();
        assertEquals(initialDiscoveryReads, files.directoryReads);

        clock.millis = 60_000;
        monitor.sample();
        assertTrue(files.directoryReads > initialDiscoveryReads);
    }

    @Test
    void skipsCgroupThatDisappearsDuringCollection() {
        files.missingDuringRead.add(group.resolve("memory.current"));
        CgroupMonitor monitor = monitor(512);

        monitor.sample();

        assertTrue(monitor.snapshots().isEmpty());
    }

    @Test
    void isolatesPermissionFailureToAffectedCgroup() {
        files.denied.add(group.resolve("memory.pressure"));
        CgroupMonitor monitor = monitor(512);

        monitor.sample();

        assertTrue(monitor.snapshots().isEmpty());
        assertTrue(monitor.isSupported());
    }

    @Test
    void boundsNumberOfDiscoveredCgroups() {
        Path second = root.resolve("system.slice/batch.service");
        files.directories.put(root, List.of(group, second));
        CgroupMonitor monitor = monitor(1);

        monitor.sample();

        assertEquals(1, monitor.snapshots().size());
        assertEquals("order-api.service", monitor.snapshots().getFirst().workload().name());
    }

    @Test
    void unsupportedCgroupV2DisablesAttributionWithoutFailure() {
        files.content.remove(root.resolve("cgroup.controllers"));
        CgroupMonitor monitor = monitor(512);

        monitor.sample();

        assertFalse(monitor.isSupported());
        assertTrue(monitor.snapshots().isEmpty());
    }

    @Test
    void cachesAnEmptyTopologyInsteadOfRediscoveringEverySample() {
        files.directories.put(root, List.of());
        CgroupMonitor monitor = monitor(512);

        monitor.sample();
        clock.millis = 5_000;
        monitor.sample();

        assertEquals(1, files.directoryReads);
    }

    private CgroupMonitor monitor(int maximumGroups) {
        return new CgroupMonitor(
                new BullseyeConfiguration.Cgroup(
                        true,
                        root,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(5),
                        8,
                        maximumGroups,
                        5,
                        1.2),
                files,
                clock,
                new BullseyeLogger(
                        new PrintStream(OutputStream.nullOutputStream()),
                        new PrintStream(OutputStream.nullOutputStream())));
    }

    private void fixtureGroup(String usageMicros, String memoryCurrent, String memoryMax) {
        files.content.put(group.resolve("cpu.stat"), "usage_usec " + usageMicros + "\n");
        files.content.put(group.resolve("memory.current"), memoryCurrent + "\n");
        files.content.put(group.resolve("memory.max"), memoryMax + "\n");
        files.content.put(group.resolve("cpu.pressure"), psi(12, null));
        files.content.put(group.resolve("memory.pressure"), psi(20, 3.0));
        files.content.put(group.resolve("io.pressure"), psi(30, 4.0));
    }

    private static String psi(double some, Double full) {
        String value = "some avg10=" + some + " avg60=1 avg300=1 total=10\n";
        return full == null ? value : value + "full avg10=" + full + " avg60=1 avg300=1 total=2\n";
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
