package io.bullseye.core.linux.proc;

import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.core.logging.BullseyeLogger;
import io.bullseye.core.metric.MetricCollector;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class LinuxHostMetricCollector implements MetricCollector {

    private static final Path PROC_STAT = Path.of("/proc/stat");
    private static final Path PROC_MEMINFO = Path.of("/proc/meminfo");

    private final ProcFileSource source;
    private final Clock clock;
    private final BullseyeLogger logger;
    private CpuTicks previousCpuTicks;
    private int previousLogicalCpuCount;

    public LinuxHostMetricCollector(
            ProcFileSource source, Clock clock, BullseyeLogger logger) {
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String name() {
        return "linux-host";
    }

    @Override
    public synchronized Collection<MetricSample> collect() {
        long timestamp = clock.millis();
        List<MetricSample> samples = new ArrayList<>(4);
        samples.addAll(collectCpu(timestamp));
        samples.addAll(collectMemory(timestamp));
        return List.copyOf(samples);
    }

    private List<MetricSample> collectCpu(long timestamp) {
        try {
            String cpuStat = source.read(PROC_STAT);
            CpuTicks currentCpuTicks = ProcStatParser.parseCpuTicks(cpuStat);
            int logicalCpuCount = ProcStatParser.parseLogicalCpuCount(cpuStat);

            List<MetricSample> samples = new ArrayList<>(2);
            if (previousCpuTicks != null
                    && logicalCpuCount == previousLogicalCpuCount
                    && comparableTicks(currentCpuTicks, previousCpuTicks)) {
                samples.add(
                        new MetricSample(
                                MetricType.HOST_CPU_USAGE,
                                currentCpuTicks.usageSince(previousCpuTicks),
                                timestamp));
            }
            if (logicalCpuCount > 0) {
                samples.add(
                        new MetricSample(
                                MetricType.HOST_CPU_LOGICAL_COUNT, logicalCpuCount, timestamp));
            }
            previousCpuTicks = currentCpuTicks;
            previousLogicalCpuCount = logicalCpuCount;
            logger.componentRestored("host-cpu");
            return samples;
        } catch (Exception failure) {
            // Never calculate usage across a failed CPU observation.
            previousCpuTicks = null;
            previousLogicalCpuCount = 0;
            logger.componentInterrupted("host-cpu", failure);
            return List.of();
        }
    }

    private List<MetricSample> collectMemory(long timestamp) {
        try {
            String memoryInfo = source.read(PROC_MEMINFO);
            double memoryUsage = MemInfoParser.parseUsagePercentage(memoryInfo);
            long totalMemoryBytes = MemInfoParser.parseTotalBytes(memoryInfo);
            List<MetricSample> samples = List.of(
                    new MetricSample(MetricType.HOST_MEMORY_USAGE, memoryUsage, timestamp),
                    new MetricSample(
                            MetricType.HOST_MEMORY_TOTAL_BYTES, totalMemoryBytes, timestamp));
            logger.componentRestored("host-memory");
            return samples;
        } catch (Exception failure) {
            logger.componentInterrupted("host-memory", failure);
            return List.of();
        }
    }

    private static boolean comparableTicks(CpuTicks current, CpuTicks previous) {
        long totalDelta = current.total() - previous.total();
        long idleDelta = current.idle() - previous.idle();
        return totalDelta > 0 && idleDelta >= 0 && idleDelta <= totalDelta;
    }
}
