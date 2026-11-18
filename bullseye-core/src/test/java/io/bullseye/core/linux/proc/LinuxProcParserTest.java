package io.bullseye.core.linux.proc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bullseye.common.metric.MetricType;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
    void parsesMemoryAndLoadAverage() {
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
        assertEquals(1.25, LoadAverageParser.parseOneMinuteLoad("1.25 0.80 0.50 1/200 10\n"));
    }

    @Test
    void collectorUsesInjectableProcSource() throws Exception {
        AtomicInteger statReads = new AtomicInteger();
        ProcFileSource source = path -> contentFor(path, statReads.getAndIncrement());
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);
        LinuxHostMetricCollector collector = new LinuxHostMetricCollector(source, clock);

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
        assertTrue(
                second.stream().anyMatch(sample -> sample.type() == MetricType.HOST_LOAD_AVERAGE));
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
        LinuxHostMetricCollector collector = new LinuxHostMetricCollector(source, clock);

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
        LinuxHostMetricCollector collector = new LinuxHostMetricCollector(source, clock);
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

    private static String contentFor(Path path, int readIndex) {
        String fileName = path.getFileName().toString();
        return switch (fileName) {
            case "stat" ->
                    readIndex == 0
                            ? "cpu  100 10 20 800 20 5 5 0\n"
                            : "cpu  130 10 30 830 20 5 5 0\n";
            case "meminfo" -> "MemTotal: 1000 kB\nMemAvailable: 250 kB\n";
            case "loadavg" -> "1.25 0.80 0.50 1/200 10\n";
            default -> throw new IllegalArgumentException("Unexpected path: " + path);
        };
    }
}
