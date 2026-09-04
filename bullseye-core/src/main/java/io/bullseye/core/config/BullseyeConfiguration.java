package io.bullseye.core.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public record BullseyeConfiguration(
        String host,
        String application,
        Duration samplingInterval,
        Duration windowDuration,
        Path stateOutput,
        CpuDiagnostics cpuDiagnostics
) {

    public BullseyeConfiguration {
        requireText(host, "host");
        requireText(application, "application");
        requirePositive(samplingInterval, "samplingInterval");
        requirePositive(windowDuration, "windowDuration");
        Objects.requireNonNull(stateOutput, "stateOutput");
        Objects.requireNonNull(cpuDiagnostics, "cpuDiagnostics");
        if (windowDuration.compareTo(samplingInterval) < 0) {
            throw new IllegalArgumentException("windowDuration must not be shorter than samplingInterval");
        }
    }

    public record CpuDiagnostics(
            double elevatedThreshold,
            Duration elevatedDuration,
            double highThreshold,
            Duration highDuration,
            double highMinimumRise,
            double criticalThreshold,
            Duration criticalDuration,
            double recoveryThreshold,
            Duration recoveryDuration
    ) {

        public CpuDiagnostics {
            requirePercentage(elevatedThreshold, "elevatedThreshold");
            requirePercentage(highThreshold, "highThreshold");
            requirePercentage(criticalThreshold, "criticalThreshold");
            requirePercentage(recoveryThreshold, "recoveryThreshold");
            requirePositive(elevatedDuration, "elevatedDuration");
            requirePositive(highDuration, "highDuration");
            requirePositive(criticalDuration, "criticalDuration");
            requirePositive(recoveryDuration, "recoveryDuration");
            if (!Double.isFinite(highMinimumRise) || highMinimumRise < 0) {
                throw new IllegalArgumentException("highMinimumRise must be finite and non-negative");
            }
            if (!(recoveryThreshold < elevatedThreshold
                    && elevatedThreshold < highThreshold
                    && highThreshold < criticalThreshold)) {
                throw new IllegalArgumentException(
                        "CPU thresholds must satisfy recovery < elevated < high < critical"
                );
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePercentage(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }
}
