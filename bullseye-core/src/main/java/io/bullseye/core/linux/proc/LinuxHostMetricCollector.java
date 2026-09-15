package io.bullseye.core.linux.proc;

import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
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
    private static final Path PROC_LOADAVG = Path.of("/proc/loadavg");

    private final ProcFileSource source;
    private final Clock clock;
    private CpuTicks previousCpuTicks;

    public LinuxHostMetricCollector(ProcFileSource source, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String name() {
        return "linux-host";
    }

    @Override
    public synchronized Collection<MetricSample> collect() throws Exception {
        long timestamp = clock.millis();
        CpuTicks currentCpuTicks = ProcStatParser.parseCpuTicks(source.read(PROC_STAT));
        double memoryUsage = MemInfoParser.parseUsagePercentage(source.read(PROC_MEMINFO));
        double loadAverage = LoadAverageParser.parseOneMinuteLoad(source.read(PROC_LOADAVG));

        List<MetricSample> samples = new ArrayList<>(3);
        if (previousCpuTicks != null) {
            samples.add(
                    new MetricSample(
                            MetricType.HOST_CPU_USAGE,
                            currentCpuTicks.usageSince(previousCpuTicks),
                            timestamp));
        }
        previousCpuTicks = currentCpuTicks;
        samples.add(new MetricSample(MetricType.HOST_MEMORY_USAGE, memoryUsage, timestamp));
        samples.add(new MetricSample(MetricType.HOST_LOAD_AVERAGE, loadAverage, timestamp));
        return List.copyOf(samples);
    }
}
