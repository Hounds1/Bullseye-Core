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

class LinuxProcParserTest {

    @Test
    void parsesCpuUsageFromTickDelta() {
        CpuTicks previous = ProcStatParser.parseCpuTicks("cpu  100 10 20 800 20 5 5 0 0 0\n");
        CpuTicks current = ProcStatParser.parseCpuTicks("cpu  130 10 30 830 20 5 5 0 0 0\n");

        assertEquals(57.142857, current.usageSince(previous), 0.000001);
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
        assertTrue(second.stream().anyMatch(sample -> sample.type() == MetricType.HOST_CPU_USAGE));
        assertTrue(
                second.stream().anyMatch(sample -> sample.type() == MetricType.HOST_MEMORY_USAGE));
        assertTrue(
                second.stream().anyMatch(sample -> sample.type() == MetricType.HOST_LOAD_AVERAGE));
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
