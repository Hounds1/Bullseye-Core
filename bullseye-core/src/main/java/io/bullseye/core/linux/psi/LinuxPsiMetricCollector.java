package io.bullseye.core.linux.psi;

import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.core.linux.proc.ProcFileSource;
import io.bullseye.core.logging.BullseyeLogger;
import io.bullseye.core.metric.MetricCollector;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class LinuxPsiMetricCollector implements MetricCollector {

    static final Path CPU = Path.of("/proc/pressure/cpu");
    static final Path MEMORY = Path.of("/proc/pressure/memory");
    static final Path IO = Path.of("/proc/pressure/io");

    private final ProcFileSource source;
    private final Clock clock;
    private final BullseyeLogger logger;

    public LinuxPsiMetricCollector(ProcFileSource source, Clock clock) {
        this(source, clock, null);
    }

    public LinuxPsiMetricCollector(ProcFileSource source, Clock clock, BullseyeLogger logger) {
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = logger;
    }

    @Override
    public String name() {
        return "linux-psi";
    }

    @Override
    public Collection<MetricSample> collect() {
        long timestamp = clock.millis();
        List<MetricSample> samples = new ArrayList<>(5);
        read(CPU, MetricType.HOST_CPU_PSI_SOME_AVG10, null, timestamp, samples);
        read(
                MEMORY,
                MetricType.HOST_MEMORY_PSI_SOME_AVG10,
                MetricType.HOST_MEMORY_PSI_FULL_AVG10,
                timestamp,
                samples);
        read(
                IO,
                MetricType.HOST_IO_PSI_SOME_AVG10,
                MetricType.HOST_IO_PSI_FULL_AVG10,
                timestamp,
                samples);
        return List.copyOf(samples);
    }

    private void read(
            Path path,
            MetricType someType,
            MetricType fullType,
            long timestamp,
            List<MetricSample> samples) {
        String component = "psi-" + path.getFileName();
        try {
            PsiParser.Snapshot pressure = PsiParser.parse(source.read(path));
            samples.add(new MetricSample(someType, pressure.some().avg10(), timestamp));
            if (fullType != null && pressure.full().isPresent()) {
                samples.add(
                        new MetricSample(
                                fullType, pressure.full().orElseThrow().avg10(), timestamp));
            }
            if (logger != null) {
                logger.componentRestored(component);
            }
        } catch (Exception failure) {
            if (logger != null) {
                logger.componentInterrupted(component, failure);
            }
        }
    }
}
