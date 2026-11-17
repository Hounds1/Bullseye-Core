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

    public record CollectionResult(
            Status status,
            List<CgroupMemorySnapshot> snapshots,
            Map<String, CgroupMemorySnapshot> previousSnapshots,
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

        public CollectionResult(
                Status status,
                List<CgroupMemorySnapshot> snapshots,
                Map<String, CgroupMemorySnapshot> previousSnapshots) {
            this(
                    status,
                    snapshots,
                    previousSnapshots,
                    Coverage.complete(snapshots.size()),
                    snapshots.stream()
                            .mapToLong(CgroupMemorySnapshot::collectedAt)
                            .max()
                            .orElse(0));
        }

        public static CollectionResult unavailable(Status status) {
            return new CollectionResult(
                    status, List.of(), Map.of(), new Coverage(0, 0, true, false), 0);
        }
    }

    public interface FileAccess {
        boolean exists(Path path);

        String read(Path path) throws IOException;

        List<Path> directories(Path path) throws IOException;
    }

    private final BullseyeConfiguration.Cgroup configuration;
    private final FileAccess files;
    private final Clock clock;
    private final BullseyeLogger logger;
    private final Map<String, CgroupMemorySnapshot> previousSnapshots = new HashMap<>();
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
            public boolean exists(Path path) {
                return Files.exists(path);
            }

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

    public synchronized CollectionResult collectMemory() {
        initialize();
        long now = clock.millis();
        if (status != Status.AVAILABLE) {
            return new CollectionResult(
                    status, List.of(), Map.of(), new Coverage(0, 0, true, false), now);
        }

        if (!discoveryComplete || now >= nextDiscoveryAt) {
            discover(now);
        }
        boolean accessDenied = discoveryDenied;
        int failedGroups = 0;

        List<CgroupMemorySnapshot> collected = new ArrayList<>(cachedGroups.size());
        Map<String, CgroupMemorySnapshot> currentByPath = new LinkedHashMap<>();
        for (Path group : cachedGroups) {
            String component = "cgroup:" + cgroupPath(group);
            try {
                CgroupMemorySnapshot snapshot = collectGroup(group, now);
                collected.add(snapshot);
                currentByPath.put(snapshot.workload().cgroupPath(), snapshot);
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

        Map<String, CgroupMemorySnapshot> previous = Map.copyOf(previousSnapshots);
        previousSnapshots.clear();
        previousSnapshots.putAll(currentByPath);
        Coverage coverage =
                new Coverage(collected.size(), failedGroups, discoveryFailed, discoveryLimited);
        if (failedGroups > 0) {
            discoveryComplete = false;
        }
        if (collected.isEmpty() && accessDenied) {
            return new CollectionResult(Status.ACCESS_DENIED, List.of(), previous, coverage, now);
        }
        return new CollectionResult(Status.AVAILABLE, collected, previous, coverage, now);
    }

    public synchronized Status status() {
        initialize();
        return status;
    }

    public synchronized void clearHistory() {
        previousSnapshots.clear();
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
            previousSnapshots.keySet().retainAll(active);
        }
        discoveryComplete = true;
        discoveryFailed = failed;
        discoveryLimited = limited;
        discoveryDenied = accessDenied;
        nextDiscoveryAt = now + configuration.discoveryInterval().toMillis();
    }

    private CgroupMemorySnapshot collectGroup(Path group, long now) throws IOException {
        long currentBytes = Long.parseLong(files.read(group.resolve("memory.current")).trim());
        Long maxBytes = memoryMax(group.resolve("memory.max"));
        PsiParser.Snapshot pressure = pressure(group.resolve("memory.pressure"));
        MemoryEvents events = memoryEvents(group.resolve("memory.events"));
        return new CgroupMemorySnapshot(
                WorkloadIdentity.fromCgroupPath(cgroupPath(group)),
                currentBytes,
                maxBytes,
                some(pressure),
                full(pressure),
                events.high(),
                events.max(),
                events.oom(),
                events.oomKill(),
                now);
    }

    private Long memoryMax(Path path) throws IOException {
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
