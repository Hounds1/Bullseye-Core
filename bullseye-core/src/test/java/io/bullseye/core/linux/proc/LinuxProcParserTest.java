package io.bullseye.core.linux.proc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.core.logging.BullseyeLogger;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class LinuxProcParserTest {

    @Test
    void parsesCpuUsageFromTickDelta() {
        CpuTicks previous = ProcStatParser.parseCpuTicks("cpu  100 10 20 800 20 5 5 0 0 0\n");
        CpuTicks current = ProcStatParser.parseCpuTicks("cpu  130 10 30 830 20 5 5 0 0 0\n");

        assertEquals(57.142857, current.usageSince(previous), 0.000001);
    }

    @Test
    void countsLogicalCpuRowsWithoutAssumingContiguousIds() {
        String stat =
                """
                cpu  100 10 20 800
                cpu0 50 5 10 400
                cpu7 50 5 10 400
                cpufreq 1000
                intr 1234
                """;

        assertEquals(2, ProcStatParser.parseLogicalCpuCount(stat));
        assertEquals(0, ProcStatParser.parseLogicalCpuCount("cpu  100 10 20 800\n"));
    }

    @Test
    void parsesMemoryUsageAndTotalBytes() {
        String memory =
                """
                MemTotal:       1000 kB
                MemFree:         100 kB
                MemAvailable:    250 kB
                Buffers:          20 kB
                Cached:           80 kB
                """;

        assertEquals(75, MemInfoParser.parseUsagePercentage(memory));
        assertEquals(1_024_000, MemInfoParser.parseTotalBytes(memory));
    }

    @Test
    void collectorUsesInjectableProcSource() throws Exception {
        AtomicInteger statReads = new AtomicInteger();
        List<Path> readPaths = new ArrayList<>();
        ProcFileSource source =
                path -> {
                    readPaths.add(path);
                    int readIndex = path.getFileName().toString().equals("stat")
                            ? statReads.getAndIncrement()
                            : 0;
                    return contentFor(path, readIndex);
                };
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);
        LinuxHostMetricCollector collector =
                new LinuxHostMetricCollector(source, clock, silentLogger());

        var first = collector.collect();
        var second = collector.collect();

        assertFalse(first.stream().anyMatch(sample -> sample.type() == MetricType.HOST_CPU_USAGE));
        assertFalse(
                first.stream()
                        .anyMatch(sample -> sample.type() == MetricType.HOST_CPU_LOGICAL_COUNT));
        assertTrue(second.stream().anyMatch(sample -> sample.type() == MetricType.HOST_CPU_USAGE));
        assertTrue(
                second.stream().anyMatch(sample -> sample.type() == MetricType.HOST_MEMORY_USAGE));
        assertTrue(
                second.stream()
                        .anyMatch(sample -> sample.type() == MetricType.HOST_MEMORY_TOTAL_BYTES));
        assertEquals(2, statReads.get());
        assertEquals(4, readPaths.size());
        assertEquals(
                Set.of(Path.of("/proc/stat"), Path.of("/proc/meminfo")),
                new HashSet<>(readPaths));
    }

    @Test
    void collectsCpuCapacityFromTheSameStatReadAndReflectsTopologyChanges() throws Exception {
        AtomicInteger statReads = new AtomicInteger();
        AtomicReference<String> stat =
                new AtomicReference<>(
                        "cpu  100 0 0 800\ncpu0 50 0 0 400\ncpu1 50 0 0 400\n");
        ProcFileSource source =
                path -> {
                    if (path.getFileName().toString().equals("stat")) {
                        statReads.incrementAndGet();
                        return stat.get();
                    }
                    return contentFor(path, 0);
                };
        Clock clock = Clock.fixed(Instant.ofEpochMilli(2_000), ZoneOffset.UTC);
        LinuxHostMetricCollector collector =
                new LinuxHostMetricCollector(source, clock, silentLogger());

        var first = collector.collect();
        var firstCount =
                first.stream()
                        .filter(sample -> sample.type() == MetricType.HOST_CPU_LOGICAL_COUNT)
                        .findFirst()
                        .orElseThrow();
        assertEquals(2, firstCount.value());
        assertEquals(2_000, firstCount.timestamp());
        assertEquals(1, statReads.get());

        stat.set("cpu  150 0 0 850\ncpu0 150 0 0 850\n");
        var second = collector.collect();
        assertEquals(
                1,
                second.stream()
                        .filter(sample -> sample.type() == MetricType.HOST_CPU_LOGICAL_COUNT)
                        .findFirst()
                        .orElseThrow()
                        .value());
        assertFalse(
                second.stream().anyMatch(sample -> sample.type() == MetricType.HOST_CPU_USAGE));
        assertEquals(2, statReads.get());

        stat.set("cpu  200 0 0 900\ncpu0 200 0 0 900\n");
        var third = collector.collect();
        assertEquals(
                50,
                third.stream()
                        .filter(sample -> sample.type() == MetricType.HOST_CPU_USAGE)
                        .findFirst()
                        .orElseThrow()
                        .value());
        assertEquals(3, statReads.get());
    }

    @Test
    void tickRollbackRebaselinesCpuWithoutLosingOtherMetrics() throws Exception {
        AtomicReference<String> stat =
                new AtomicReference<>("cpu  100 0 0 800\ncpu0 100 0 0 800\n");
        ProcFileSource source =
                path -> path.getFileName().toString().equals("stat")
                        ? stat.get()
                        : contentFor(path, 0);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(3_000), ZoneOffset.UTC);
        LinuxHostMetricCollector collector =
                new LinuxHostMetricCollector(source, clock, silentLogger());
        collector.collect();

        stat.set("cpu  50 0 0 200\ncpu0 50 0 0 200\n");
        var rollback = collector.collect();
        assertFalse(
                rollback.stream().anyMatch(sample -> sample.type() == MetricType.HOST_CPU_USAGE));
        assertTrue(
                rollback.stream()
                        .anyMatch(sample -> sample.type() == MetricType.HOST_CPU_LOGICAL_COUNT));
        assertTrue(
                rollback.stream()
                        .anyMatch(sample -> sample.type() == MetricType.HOST_MEMORY_USAGE));

        stat.set("cpu  70 0 0 220\ncpu0 70 0 0 220\n");
        assertEquals(
                50,
                collector.collect().stream()
                        .filter(sample -> sample.type() == MetricType.HOST_CPU_USAGE)
                        .findFirst()
                        .orElseThrow()
                        .value());
    }

    @Test
    void cpuReadFailureKeepsMemoryAndResetsCpuBaselineBeforeRecovery() throws Exception {
        assertCpuFailureRecovery(true);
    }

    @Test
    void cpuParseFailureKeepsMemoryAndResetsCpuBaselineBeforeRecovery() throws Exception {
        assertCpuFailureRecovery(false);
    }

    @Test
    void memoryReadFailureKeepsUpdatingCpuBaseline() throws Exception {
        assertMemoryFailurePreservesCpu(true);
    }

    @Test
    void memoryParseFailureKeepsUpdatingCpuBaseline() throws Exception {
        assertMemoryFailurePreservesCpu(false);
    }

    @Test
    void memoryTotalBytesOverflowDoesNotPublishPartialMemorySamples() throws Exception {
        String overflow = "MemTotal: 9223372036854775807 kB\nMemAvailable: 0 kB\n";
        assertEquals(100, MemInfoParser.parseUsagePercentage(overflow));
        AtomicReference<String> stat = new AtomicReference<>(cpuStat(100, 800));
        ProcFileSource source = path -> path.getFileName().toString().equals("stat")
                ? stat.get()
                : overflow;
        LinuxHostMetricCollector collector = collector(source);
        collector.collect();

        stat.set(cpuStat(130, 870));
        Collection<MetricSample> samples = collector.collect();

        assertMetricTypes(samples, MetricType.HOST_CPU_USAGE, MetricType.HOST_CPU_LOGICAL_COUNT);
        assertEquals(30, metricValue(samples, MetricType.HOST_CPU_USAGE));
    }

    @Test
    void componentFailuresLogOnceAndRecoverIndependentlyAcrossRepeatedOutages() throws Exception {
        Set<String> failedFiles = new HashSet<>();
        ProcFileSource source = path -> {
            if (failedFiles.contains(path.getFileName().toString())) {
                throw new IOException("Unavailable: " + path);
            }
            return contentFor(path, 0);
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        LinuxHostMetricCollector collector = new LinuxHostMetricCollector(
                source,
                Clock.fixed(Instant.ofEpochMilli(4_000), ZoneOffset.UTC),
                new BullseyeLogger(new PrintStream(output), new PrintStream(errors)));
        collector.collect();
        assertTrue(output.toString().isEmpty());
        assertTrue(errors.toString().isEmpty());

        failedFiles.add("stat");
        collector.collect();
        collector.collect();
        assertEquals(1, componentLogCount(errors, "host-cpu"));
        assertEquals(0, componentLogCount(errors, "host-memory"));
        assertEquals(0, componentLogCount(output, "host-cpu"));

        failedFiles.add("meminfo");
        assertTrue(collector.collect().isEmpty());
        assertTrue(collector.collect().isEmpty());
        assertEquals(1, componentLogCount(errors, "host-cpu"));
        assertEquals(1, componentLogCount(errors, "host-memory"));

        failedFiles.remove("stat");
        collector.collect();
        collector.collect();
        assertEquals(1, componentLogCount(output, "host-cpu"));
        assertEquals(0, componentLogCount(output, "host-memory"));

        failedFiles.add("stat");
        collector.collect();
        collector.collect();
        assertEquals(2, componentLogCount(errors, "host-cpu"));
        assertEquals(1, componentLogCount(errors, "host-memory"));

        failedFiles.remove("meminfo");
        collector.collect();
        collector.collect();
        assertEquals(1, componentLogCount(output, "host-memory"));
        assertEquals(1, componentLogCount(output, "host-cpu"));

        failedFiles.remove("stat");
        collector.collect();
        collector.collect();
        assertEquals(2, componentLogCount(output, "host-cpu"));
        assertEquals(1, componentLogCount(output, "host-memory"));
    }

    private static void assertCpuFailureRecovery(boolean readFailure) throws Exception {
        AtomicReference<String> stat = new AtomicReference<>(cpuStat(100, 800));
        AtomicBoolean failed = new AtomicBoolean();
        ProcFileSource source = path -> {
            if (!path.getFileName().toString().equals("stat")) {
                return contentFor(path, 0);
            }
            if (failed.get()) {
                if (readFailure) {
                    throw new IOException("CPU read failed");
                }
                return "cpu  invalid 0 0 800\ncpu0 100 0 0 800\n";
            }
            return stat.get();
        };
        LinuxHostMetricCollector collector = collector(source);
        collector.collect();

        failed.set(true);
        Collection<MetricSample> interrupted = collector.collect();
        assertMetricTypes(
                interrupted, MetricType.HOST_MEMORY_USAGE, MetricType.HOST_MEMORY_TOTAL_BYTES);
        assertEquals(75, metricValue(interrupted, MetricType.HOST_MEMORY_USAGE));
        assertEquals(1_024_000, metricValue(interrupted, MetricType.HOST_MEMORY_TOTAL_BYTES));

        failed.set(false);
        stat.set(cpuStat(400, 900));
        Collection<MetricSample> recovered = collector.collect();
        assertMetricTypes(
                recovered,
                MetricType.HOST_CPU_LOGICAL_COUNT,
                MetricType.HOST_MEMORY_USAGE,
                MetricType.HOST_MEMORY_TOTAL_BYTES);

        stat.set(cpuStat(450, 1_050));
        Collection<MetricSample> comparable = collector.collect();
        assertMetricTypes(
                comparable,
                MetricType.HOST_CPU_USAGE,
                MetricType.HOST_CPU_LOGICAL_COUNT,
                MetricType.HOST_MEMORY_USAGE,
                MetricType.HOST_MEMORY_TOTAL_BYTES);
        assertEquals(25, metricValue(comparable, MetricType.HOST_CPU_USAGE));
        assertTrue(comparable.stream().allMatch(sample -> sample.timestamp() == 5_000));
    }

    private static void assertMemoryFailurePreservesCpu(boolean readFailure) throws Exception {
        AtomicReference<String> stat = new AtomicReference<>(cpuStat(100, 800));
        AtomicBoolean failed = new AtomicBoolean();
        ProcFileSource source = path -> {
            if (path.getFileName().toString().equals("stat")) {
                return stat.get();
            }
            if (failed.get()) {
                if (readFailure) {
                    throw new IOException("Memory read failed");
                }
                return "MemTotal: invalid kB\nMemAvailable: 250 kB\n";
            }
            return contentFor(path, 0);
        };
        LinuxHostMetricCollector collector = collector(source);
        collector.collect();

        failed.set(true);
        stat.set(cpuStat(130, 870));
        Collection<MetricSample> firstFailure = collector.collect();
        assertMetricTypes(
                firstFailure, MetricType.HOST_CPU_USAGE, MetricType.HOST_CPU_LOGICAL_COUNT);
        assertEquals(30, metricValue(firstFailure, MetricType.HOST_CPU_USAGE));

        stat.set(cpuStat(150, 950));
        Collection<MetricSample> secondFailure = collector.collect();
        assertMetricTypes(
                secondFailure, MetricType.HOST_CPU_USAGE, MetricType.HOST_CPU_LOGICAL_COUNT);
        assertEquals(20, metricValue(secondFailure, MetricType.HOST_CPU_USAGE));

        failed.set(false);
        stat.set(cpuStat(170, 970));
        Collection<MetricSample> recovered = collector.collect();
        assertMetricTypes(
                recovered,
                MetricType.HOST_CPU_USAGE,
                MetricType.HOST_CPU_LOGICAL_COUNT,
                MetricType.HOST_MEMORY_USAGE,
                MetricType.HOST_MEMORY_TOTAL_BYTES);
        assertEquals(50, metricValue(recovered, MetricType.HOST_CPU_USAGE));
        assertEquals(75, metricValue(recovered, MetricType.HOST_MEMORY_USAGE));
        assertEquals(1_024_000, metricValue(recovered, MetricType.HOST_MEMORY_TOTAL_BYTES));
    }

    private static LinuxHostMetricCollector collector(ProcFileSource source) {
        return new LinuxHostMetricCollector(
                source,
                Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC),
                silentLogger());
    }

    private static BullseyeLogger silentLogger() {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        return new BullseyeLogger(discard, discard);
    }

    private static String cpuStat(long user, long idle) {
        return "cpu  " + user + " 0 0 " + idle + "\ncpu0 " + user + " 0 0 " + idle + "\n";
    }

    private static void assertMetricTypes(
            Collection<MetricSample> samples, MetricType... expected) {
        assertEquals(expected.length, samples.size());
        assertEquals(
                Set.of(expected),
                new HashSet<>(samples.stream().map(MetricSample::type).toList()));
    }

    private static double metricValue(Collection<MetricSample> samples, MetricType type) {
        return samples.stream()
                .filter(sample -> sample.type() == type)
                .findFirst()
                .orElseThrow()
                .value();
    }

    private static long componentLogCount(ByteArrayOutputStream output, String component) {
        return output.toString().lines()
                .filter(line -> line.contains("component=" + component))
                .count();
    }

    private static String contentFor(Path path, int readIndex) {
        String fileName = path.getFileName().toString();
        return switch (fileName) {
            case "stat" ->
                    readIndex == 0
                            ? "cpu  100 10 20 800 20 5 5 0\n"
                            : "cpu  130 10 30 830 20 5 5 0\n";
            case "meminfo" -> "MemTotal: 1000 kB\nMemAvailable: 250 kB\n";
            default -> throw new IllegalArgumentException("Unexpected path: " + path);
        };
    }
}
