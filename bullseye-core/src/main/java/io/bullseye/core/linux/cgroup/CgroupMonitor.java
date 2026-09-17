package io.bullseye.core.linux.cgroup;

import io.bullseye.common.workload.WorkloadIdentity;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.linux.psi.PsiParser;
import io.bullseye.core.logging.BullseyeLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CgroupMonitor {

    public interface FileAccess {
        boolean exists(Path path);

        String read(Path path) throws IOException;

        List<Path> directories(Path path) throws IOException;
    }

    private final BullseyeConfiguration.Cgroup configuration;
    private final FileAccess files;
    private final Clock clock;
    private final BullseyeLogger logger;
    private final Map<Path, CpuPoint> cpuPoints = new HashMap<>();
    private final Map<Path, CgroupResourceSnapshot> previousSnapshots = new HashMap<>();
    private List<Path> cachedGroups = List.of();
    private volatile List<CgroupResourceSnapshot> snapshots = List.of();
    private long nextDiscoveryAt;
    private boolean discoveryComplete;
    private boolean initialized;
    private boolean supported;

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
                    return entries.filter(Files::isDirectory).toList();
                }
            }
        };
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        supported =
                configuration.enabled()
                        && files.exists(configuration.root().resolve("cgroup.controllers"));
        if (supported) {
            logger.status("cgroup v2 detected.");
        } else {
            logger.status("cgroup v2 unavailable. Workload attribution disabled.");
        }
    }

    public synchronized void sample() {
        initialize();
        if (!supported) {
            return;
        }
        long now = clock.millis();
        if (!discoveryComplete || now >= nextDiscoveryAt) {
            discover(now);
        }

        List<CgroupResourceSnapshot> collected = new ArrayList<>(cachedGroups.size());
        Map<Path, CgroupResourceSnapshot> currentByPath = new LinkedHashMap<>();
        for (Path group : cachedGroups) {
            CgroupResourceSnapshot snapshot = sampleGroup(group, now);
            if (snapshot != null) {
                collected.add(snapshot);
                currentByPath.put(group, snapshot);
            }
        }
        previousSnapshots.clear();
        previousSnapshots.putAll(currentByPath);
        snapshots = List.copyOf(collected);
    }

    public List<CgroupResourceSnapshot> snapshots() {
        return snapshots;
    }

    public synchronized boolean isSupported() {
        initialize();
        return supported;
    }

    private void discover(long now) {
        ArrayDeque<Node> pending = new ArrayDeque<>();
        List<Path> discovered = new ArrayList<>(Math.min(configuration.maximumGroups(), 64));
        Map<Path, Boolean> parents = new HashMap<>();
        boolean failed = false;
        pending.add(new Node(configuration.root(), 0));
        while (!pending.isEmpty() && discovered.size() < configuration.maximumGroups()) {
            Node node = pending.removeFirst();
            if (node.depth() >= configuration.maximumDepth()) {
                continue;
            }
            try {
                for (Path child : files.directories(node.path())) {
                    discovered.add(child);
                    parents.put(node.path(), true);
                    if (discovered.size() >= configuration.maximumGroups()) {
                        break;
                    }
                    pending.addLast(new Node(child, node.depth() + 1));
                }
                logger.componentRestored("cgroup-discovery");
            } catch (Exception failure) {
                failed = true;
                logger.componentInterrupted("cgroup-discovery", failure);
            }
        }
        if (!(failed && discovered.isEmpty() && !cachedGroups.isEmpty())) {
            List<Path> leaves =
                    discovered.stream().filter(path -> !parents.containsKey(path)).toList();
            cachedGroups = List.copyOf(leaves);
            cpuPoints.keySet().retainAll(leaves);
            previousSnapshots.keySet().retainAll(leaves);
        }
        discoveryComplete = true;
        nextDiscoveryAt = now + configuration.discoveryInterval().toMillis();
    }

    private CgroupResourceSnapshot sampleGroup(Path group, long now) {
        String relative = cgroupPath(group);
        String component = "cgroup:" + relative;
        try {
            Long usageMicros = cpuUsageMicros(group.resolve("cpu.stat"));
            double cpuUsage = cpuUsage(group, usageMicros, now);
            long memoryCurrent = wholeOrUnavailable(group.resolve("memory.current"), false);
            long memoryMax = wholeOrUnavailable(group.resolve("memory.max"), true);
            PsiParser.Snapshot cpu = pressure(group.resolve("cpu.pressure"));
            PsiParser.Snapshot memory = pressure(group.resolve("memory.pressure"));
            PsiParser.Snapshot io = pressure(group.resolve("io.pressure"));

            double cpuSome = some(cpu);
            double memorySome = some(memory);
            double memoryFull = full(memory);
            double ioSome = some(io);
            double ioFull = full(io);
            CgroupResourceSnapshot previous = previousSnapshots.get(group);
            CgroupResourceSnapshot result =
                    new CgroupResourceSnapshot(
                            WorkloadIdentity.fromCgroupPath(relative),
                            cpuUsage,
                            memoryCurrent,
                            memoryMax,
                            cpuSome,
                            memorySome,
                            memoryFull,
                            ioSome,
                            ioFull,
                            trend(cpuSome, previous == null ? Double.NaN : previous.cpuPsiSome()),
                            trend(
                                    memorySome,
                                    previous == null ? Double.NaN : previous.memoryPsiSome()),
                            trend(ioSome, previous == null ? Double.NaN : previous.ioPsiSome()),
                            now);
            if (usageMicros == null && memoryCurrent < 0 && !result.hasAnyPressureMetric()) {
                return null;
            }
            logger.componentRestored(component);
            return result;
        } catch (Exception failure) {
            logger.componentInterrupted(component, failure);
            return null;
        }
    }

    private Long cpuUsageMicros(Path path) throws IOException {
        String raw = optionalRead(path);
        if (raw == null) {
            return null;
        }
        for (String line : raw.lines().toList()) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && "usage_usec".equals(parts[0])) {
                return Long.parseLong(parts[1]);
            }
        }
        throw new IllegalArgumentException("cpu.stat usage_usec missing");
    }

    private double cpuUsage(Path group, Long usageMicros, long now) {
        if (usageMicros == null) {
            return Double.NaN;
        }
        CpuPoint previous = cpuPoints.put(group, new CpuPoint(usageMicros, now));
        if (previous == null
                || now <= previous.timestamp()
                || usageMicros < previous.usageMicros()) {
            return Double.NaN;
        }
        double elapsedMicros = (now - previous.timestamp()) * 1_000.0;
        return (usageMicros - previous.usageMicros()) / elapsedMicros * 100.0;
    }

    private long wholeOrUnavailable(Path path, boolean allowsMax) throws IOException {
        String raw = optionalRead(path);
        if (raw == null) {
            return -1;
        }
        String value = raw.trim();
        if (allowsMax && "max".equals(value)) {
            return -1;
        }
        return Long.parseLong(value);
    }

    private PsiParser.Snapshot pressure(Path path) throws IOException {
        String raw = optionalRead(path);
        return raw == null ? null : PsiParser.parse(raw);
    }

    private String optionalRead(Path path) throws IOException {
        return files.exists(path) ? files.read(path) : null;
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

    private static double trend(double current, double previous) {
        return Double.isFinite(current) && Double.isFinite(previous) ? current - previous : 0;
    }

    private record Node(Path path, int depth) {}

    private record CpuPoint(long usageMicros, long timestamp) {}
}
