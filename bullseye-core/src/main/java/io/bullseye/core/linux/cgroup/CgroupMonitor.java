package io.bullseye.core.linux.cgroup;

import io.bullseye.common.workload.WorkloadAttribution.Coverage;
import io.bullseye.common.workload.WorkloadIdentity;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.linux.psi.PsiParser;
import io.bullseye.core.logging.BullseyeLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CgroupMonitor {

    public enum Status {
        AVAILABLE,
        CGROUP_V2_NOT_AVAILABLE,
        ACCESS_DENIED
    }

    public record CollectionResult<T>(
            Status status,
            List<T> snapshots,
            Map<String, T> previousSnapshots,
            Coverage coverage,
            long collectedAt) {

        public CollectionResult {
            Objects.requireNonNull(status, "status");
            snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
            previousSnapshots =
                    Map.copyOf(Objects.requireNonNull(previousSnapshots, "previousSnapshots"));
            Objects.requireNonNull(coverage, "coverage");
            if (collectedAt < 0) {
                throw new IllegalArgumentException("collectedAt must not be negative");
            }
        }

        public static <T> CollectionResult<T> unavailable(Status status) {
            return new CollectionResult<>(
                    status, List.of(), Map.of(), new Coverage(0, 0, true, false), 0);
        }
    }

    public interface FileAccess {
        String read(Path path) throws IOException;

        List<Path> directories(Path path) throws IOException;
    }

    @FunctionalInterface
    private interface GroupReader<T> {
        T read(Path group, long collectedAt) throws IOException;
    }

    private final BullseyeConfiguration.Cgroup configuration;
    private final FileAccess files;
    private final Clock clock;
    private final BullseyeLogger logger;
    private final Map<String, CgroupMemorySnapshot> previousMemorySnapshots = new HashMap<>();
    private final Map<String, CgroupCpuSnapshot> previousCpuSnapshots = new HashMap<>();
    private final Map<String, CgroupIoSnapshot> previousIoSnapshots = new HashMap<>();
    private List<Path> cachedGroups = List.of();
    private long nextDiscoveryAt;
    private boolean discoveryComplete;
    private boolean discoveryFailed;
    private boolean discoveryLimited;
    private boolean discoveryDenied;
    private boolean initialized;
    private Status status = Status.CGROUP_V2_NOT_AVAILABLE;

    public CgroupMonitor(
            BullseyeConfiguration.Cgroup configuration,
            FileAccess files,
            Clock clock,
            BullseyeLogger logger) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.files = Objects.requireNonNull(files, "files");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public static FileAccess nioFileAccess() {
        return new FileAccess() {
            @Override
            public String read(Path path) throws IOException {
                return Files.readString(path, StandardCharsets.US_ASCII);
            }

            @Override
            public List<Path> directories(Path path) throws IOException {
                try (var entries = Files.list(path)) {
                    List<Path> directories = new ArrayList<>();
                    var iterator = entries.iterator();
                    while (iterator.hasNext()) {
                        Path entry = iterator.next();
                        if (Files.readAttributes(entry, BasicFileAttributes.class).isDirectory()) {
                            directories.add(entry);
                        }
                    }
                    return directories;
                }
            }
        };
    }

    public synchronized void initialize() {
        if (initialized && (!configuration.enabled() || status == Status.AVAILABLE)) {
            return;
        }
        boolean firstAttempt = !initialized;
        Status previousStatus = status;
        initialized = true;
        Path controllers = configuration.root().resolve("cgroup.controllers");
        if (!configuration.enabled()) {
            status = Status.CGROUP_V2_NOT_AVAILABLE;
            logger.status("cgroup v2 unavailable. Workload attribution disabled.");
            return;
        }
        try {
            files.read(controllers);
            status = Status.AVAILABLE;
            logger.componentRestored("cgroup-v2");
            logger.status("cgroup v2 detected.");
        } catch (AccessDeniedException failure) {
            status = Status.ACCESS_DENIED;
            logger.componentInterrupted("cgroup-v2", failure);
        } catch (NoSuchFileException failure) {
            status = Status.CGROUP_V2_NOT_AVAILABLE;
            if (firstAttempt || previousStatus != status) {
                logger.status("cgroup v2 unavailable. Workload attribution suspended.");
            }
        } catch (IOException failure) {
            status = Status.CGROUP_V2_NOT_AVAILABLE;
            logger.componentInterrupted("cgroup-v2", failure);
        }
    }

    public synchronized CollectionResult<CgroupMemorySnapshot> collectMemory() {
        return collect("cgroup-memory", previousMemorySnapshots, this::collectMemoryGroup);
    }

    public synchronized CollectionResult<CgroupCpuSnapshot> collectCpu() {
        CollectionResult<CgroupCpuSnapshot> result =
                collect("cgroup-cpu", previousCpuSnapshots, this::collectCpuGroup);
        if (result.status() != Status.AVAILABLE) {
            previousCpuSnapshots.clear();
        }
        return result;
    }

    public synchronized CollectionResult<CgroupIoSnapshot> collectIo() {
        CollectionResult<CgroupIoSnapshot> result =
                collect("cgroup-io", previousIoSnapshots, this::collectIoGroup);
        if (result.status() != Status.AVAILABLE) {
            previousIoSnapshots.clear();
        }
        return result;
    }

    private <T> CollectionResult<T> collect(
            String componentPrefix,
            Map<String, T> history,
            GroupReader<T> reader) {
        initialize();
        long now = clock.millis();
        if (status != Status.AVAILABLE) {
            return new CollectionResult<>(
                    status, List.of(), Map.of(), new Coverage(0, 0, true, false), now);
        }

        if (!discoveryComplete || now >= nextDiscoveryAt) {
            discover(now);
        }
        boolean accessDenied = discoveryDenied;
        int failedGroups = 0;

        List<T> collected = new ArrayList<>(cachedGroups.size());
        Map<String, T> currentByPath = new LinkedHashMap<>();
        for (Path group : cachedGroups) {
            String path = cgroupPath(group);
            String component = componentPrefix + ":" + path;
            try {
                T snapshot = reader.read(group, now);
                collected.add(snapshot);
                currentByPath.put(path, snapshot);
                logger.componentRestored(component);
            } catch (AccessDeniedException failure) {
                accessDenied = true;
                failedGroups++;
                logger.componentInterrupted(component, failure);
            } catch (Exception failure) {
                failedGroups++;
                logger.componentInterrupted(component, failure);
            }
        }

        Map<String, T> previous = Map.copyOf(history);
        history.clear();
        history.putAll(currentByPath);
        Coverage coverage =
                new Coverage(collected.size(), failedGroups, discoveryFailed, discoveryLimited);
        if (failedGroups > 0) {
            discoveryComplete = false;
        }
        Status resultStatus =
                collected.isEmpty() && accessDenied ? Status.ACCESS_DENIED : Status.AVAILABLE;
        return new CollectionResult<>(resultStatus, collected, previous, coverage, now);
    }

    public synchronized Status status() {
        initialize();
        return status;
    }

    public synchronized void clearMemoryHistory() {
        previousMemorySnapshots.clear();
        discoveryComplete = false;
    }

    public synchronized void clearCpuHistory() {
        previousCpuSnapshots.clear();
        discoveryComplete = false;
    }

    public synchronized void clearIoHistory() {
        previousIoSnapshots.clear();
        discoveryComplete = false;
    }

    private void discover(long now) {
        ArrayDeque<Node> pending = new ArrayDeque<>();
        List<Path> discovered = new ArrayList<>(Math.min(configuration.maximumGroups(), 64));
        Set<Path> parents = new HashSet<>();
        boolean failed = false;
        boolean accessDenied = false;
        boolean limited = false;
        pending.add(new Node(configuration.root(), 0));
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            try {
                List<Path> children = files.directories(node.path());
                if (node.depth() >= configuration.maximumDepth()) {
                    limited |= !children.isEmpty();
                    continue;
                }
                for (Path child : children) {
                    if (discovered.size() >= configuration.maximumGroups()) {
                        limited = true;
                        break;
                    }
                    discovered.add(child);
                    parents.add(node.path());
                    pending.addLast(new Node(child, node.depth() + 1));
                }
            } catch (AccessDeniedException failure) {
                failed = true;
                accessDenied = true;
                logger.componentInterrupted("cgroup-discovery", failure);
            } catch (Exception failure) {
                failed = true;
                logger.componentInterrupted("cgroup-discovery", failure);
            }
        }
        if (!failed) {
            logger.componentRestored("cgroup-discovery");
        }

        if (!(failed && discovered.isEmpty() && !cachedGroups.isEmpty())) {
            List<Path> leaves =
                    discovered.stream().filter(path -> !parents.contains(path)).toList();
            cachedGroups = List.copyOf(leaves);
            Set<String> active = new HashSet<>();
            leaves.forEach(path -> active.add(cgroupPath(path)));
            previousMemorySnapshots.keySet().retainAll(active);
            previousCpuSnapshots.keySet().retainAll(active);
            previousIoSnapshots.keySet().retainAll(active);
        }
        discoveryComplete = true;
        discoveryFailed = failed;
        discoveryLimited = limited;
        discoveryDenied = accessDenied;
        nextDiscoveryAt = now + configuration.discoveryInterval().toMillis();
    }

    private CgroupMemorySnapshot collectMemoryGroup(Path group, long now) throws IOException {
        long currentBytes = Long.parseLong(files.read(group.resolve("memory.current")).trim());
        Long highBytes = memoryLimit(group.resolve("memory.high"));
        Long maxBytes = memoryLimit(group.resolve("memory.max"));
        PsiParser.Snapshot pressure = pressure(group.resolve("memory.pressure"));
        MemoryEvents events = memoryEvents(group.resolve("memory.events"));
        return new CgroupMemorySnapshot(
                WorkloadIdentity.fromCgroupPath(cgroupPath(group)),
                currentBytes,
                highBytes,
                maxBytes,
                some(pressure),
                full(pressure),
                events.high(),
                events.max(),
                events.oom(),
                events.oomKill(),
                now);
    }

    private CgroupCpuSnapshot collectCpuGroup(Path group, long now) throws IOException {
        Map<String, Long> counters = cpuCounters(files.read(group.resolve("cpu.stat")));
        String maximum = optionalRead(group.resolve("cpu.max"));
        Long quota = null;
        Long period = null;
        if (maximum != null) {
            String[] parts = maximum.trim().split("\\s+");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Malformed cpu.max");
            }
            quota = "max".equals(parts[0]) ? null : Long.parseLong(parts[0]);
            period = Long.parseLong(parts[1]);
        }
        PsiParser.Snapshot pressure = pressure(group.resolve("cpu.pressure"));
        return new CgroupCpuSnapshot(
                WorkloadIdentity.fromCgroupPath(cgroupPath(group)),
                requiredCounter(counters, "usage_usec", "cpu.stat"),
                requiredCounter(counters, "user_usec", "cpu.stat"),
                requiredCounter(counters, "system_usec", "cpu.stat"),
                quota,
                period,
                counters.get("nr_periods"),
                counters.get("nr_throttled"),
                counters.get("throttled_usec"),
                some(pressure),
                now);
    }

    private CgroupIoSnapshot collectIoGroup(Path group, long now) throws IOException {
        Map<String, CgroupIoSnapshot.DeviceStats> devices =
                ioCounters(files.read(group.resolve("io.stat")));
        String maximum = optionalRead(group.resolve("io.max"));
        Map<String, CgroupIoSnapshot.DeviceLimit> limits =
                maximum == null ? Map.of() : ioLimits(maximum);
        PsiParser.Snapshot pressure = pressure(group.resolve("io.pressure"));
        return new CgroupIoSnapshot(
                WorkloadIdentity.fromCgroupPath(cgroupPath(group)),
                devices,
                limits,
                maximum != null,
                some(pressure),
                full(pressure),
                now);
    }

    private static Map<String, CgroupIoSnapshot.DeviceStats> ioCounters(String raw) {
        Map<String, CgroupIoSnapshot.DeviceStats> devices = new LinkedHashMap<>();
        Set<String> counterNames = Set.of("rbytes", "wbytes", "rios", "wios", "dbytes", "dios");
        for (var entry : ioDeviceFields(raw, "io.stat").entrySet()) {
            Map<String, Long> counters = new HashMap<>();
            for (var field : entry.getValue().entrySet()) {
                // Controller extensions can contain signed or fractional values.
                if (!counterNames.contains(field.getKey())) {
                    continue;
                }
                long value = Long.parseLong(field.getValue());
                if (value < 0) {
                    throw new IllegalArgumentException("Negative io.stat counter: " + field.getKey());
                }
                counters.put(field.getKey(), value);
            }
            // The kernel omits all four read/write fields when their counters are zero.
            if (!counters.containsKey("rbytes")
                    && !counters.containsKey("wbytes")
                    && !counters.containsKey("rios")
                    && !counters.containsKey("wios")) {
                counters.put("rbytes", 0L);
                counters.put("wbytes", 0L);
                counters.put("rios", 0L);
                counters.put("wios", 0L);
            }
            devices.put(
                    entry.getKey(),
                    new CgroupIoSnapshot.DeviceStats(
                            requiredCounter(counters, "rbytes", "io.stat"),
                            requiredCounter(counters, "wbytes", "io.stat"),
                            requiredCounter(counters, "rios", "io.stat"),
                            requiredCounter(counters, "wios", "io.stat")));
        }
        return devices;
    }

    private static Map<String, CgroupIoSnapshot.DeviceLimit> ioLimits(String raw) {
        Map<String, CgroupIoSnapshot.DeviceLimit> limits = new LinkedHashMap<>();
        for (var entry : ioDeviceFields(raw, "io.max").entrySet()) {
            Map<String, Long> values = new HashMap<>();
            for (var field : entry.getValue().entrySet()) {
                Long value = "max".equals(field.getValue())
                        ? null
                        : Long.parseLong(field.getValue());
                if (value != null && value <= 0) {
                    throw new IllegalArgumentException("Non-positive io.max limit: " + field.getKey());
                }
                values.put(field.getKey(), value);
            }
            limits.put(
                    entry.getKey(),
                    new CgroupIoSnapshot.DeviceLimit(
                            values.get("rbps"),
                            values.get("wbps"),
                            values.get("riops"),
                            values.get("wiops")));
        }
        return limits;
    }

    private static Map<String, Map<String, String>> ioDeviceFields(String raw, String fileName) {
        Map<String, Map<String, String>> devices = new LinkedHashMap<>();
        for (String line : raw.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2 && !"io.stat".equals(fileName)) {
                throw new IllegalArgumentException("Malformed " + fileName + " line");
            }
            CgroupIoSnapshot.requireDeviceId(parts[0]);
            Map<String, String> fields = new HashMap<>();
            if (devices.put(parts[0], fields) != null) {
                throw new IllegalArgumentException("Duplicate " + fileName + " device: " + parts[0]);
            }
            for (int index = 1; index < parts.length; index++) {
                String[] field = parts[index].split("=", -1);
                if (field.length != 2
                        || field[0].isEmpty()
                        || field[1].isEmpty()
                        || fields.put(field[0], field[1]) != null) {
                    throw new IllegalArgumentException("Malformed " + fileName + " field: " + parts[index]);
                }
            }
        }
        return devices;
    }

    private static Map<String, Long> cpuCounters(String raw) {
        Map<String, Long> counters = new HashMap<>();
        for (String line : raw.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Malformed cpu.stat line");
            }
            long value = Long.parseLong(parts[1]);
            if (value < 0 || counters.put(parts[0], value) != null) {
                throw new IllegalArgumentException("Invalid cpu.stat counter: " + parts[0]);
            }
        }
        return counters;
    }

    private static long requiredCounter(Map<String, Long> counters, String key, String fileName) {
        Long value = counters.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + fileName + " counter: " + key);
        }
        return value;
    }

    private Long memoryLimit(Path path) throws IOException {
        String raw = optionalRead(path);
        if (raw == null || "max".equals(raw.trim())) {
            return null;
        }
        return Long.parseLong(raw.trim());
    }

    private MemoryEvents memoryEvents(Path path) throws IOException {
        String raw = optionalRead(path);
        if (raw == null) {
            return MemoryEvents.EMPTY;
        }
        Map<String, Long> counters = new HashMap<>();
        for (String line : raw.lines().toList()) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2) {
                counters.put(parts[0], Long.parseLong(parts[1]));
            }
        }
        return new MemoryEvents(
                counters.getOrDefault("high", 0L),
                counters.getOrDefault("max", 0L),
                counters.getOrDefault("oom", 0L),
                counters.getOrDefault("oom_kill", 0L));
    }

    private PsiParser.Snapshot pressure(Path path) throws IOException {
        String raw = optionalRead(path);
        return raw == null ? null : PsiParser.parse(raw);
    }

    private String optionalRead(Path path) throws IOException {
        try {
            return files.read(path);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private String cgroupPath(Path group) {
        String relative = configuration.root().relativize(group).toString().replace('\\', '/');
        return "/" + relative;
    }

    private static double some(PsiParser.Snapshot pressure) {
        return pressure == null ? Double.NaN : pressure.some().avg10();
    }

    private static double full(PsiParser.Snapshot pressure) {
        return pressure == null || pressure.full().isEmpty()
                ? Double.NaN
                : pressure.full().orElseThrow().avg10();
    }

    private record Node(Path path, int depth) {}

    private record MemoryEvents(long high, long max, long oom, long oomKill) {
        private static final MemoryEvents EMPTY = new MemoryEvents(0, 0, 0, 0);
    }
}
