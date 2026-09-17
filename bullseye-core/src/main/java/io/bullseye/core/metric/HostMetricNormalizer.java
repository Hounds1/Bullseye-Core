package io.bullseye.core.metric;

import io.bullseye.common.metric.MetricSample;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class HostMetricNormalizer implements MetricNormalizer {

    @Override
    public Collection<MetricSample> normalize(Collection<MetricSample> samples) {
        List<MetricSample> normalized = new ArrayList<>(samples.size());
        for (MetricSample sample : samples) {
            double value =
                    switch (sample.type()) {
                        case HOST_CPU_USAGE,
                                HOST_MEMORY_USAGE,
                                HOST_CPU_PSI_SOME_AVG10,
                                HOST_MEMORY_PSI_SOME_AVG10,
                                HOST_MEMORY_PSI_FULL_AVG10,
                                HOST_IO_PSI_SOME_AVG10,
                                HOST_IO_PSI_FULL_AVG10 ->
                                clampPercentage(sample.value());
                        default -> sample.value();
                    };
            normalized.add(new MetricSample(sample.type(), value, sample.timestamp()));
        }
        return List.copyOf(normalized);
    }

    private static double clampPercentage(double value) {
        return Math.max(0, Math.min(100, value));
    }
}
