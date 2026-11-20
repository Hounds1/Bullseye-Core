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
        CpuDiagnostics cpuDiagnostics,
        MemoryDiagnostics memoryDiagnostics,
        IoDiagnostics ioDiagnostics,
        Cgroup cgroup,
        Ccc ccc) {

    public BullseyeConfiguration {
        requireText(host, "host");
        requireText(application, "application");
        requirePositive(samplingInterval, "samplingInterval");
        requirePositive(windowDuration, "windowDuration");
        Objects.requireNonNull(stateOutput, "stateOutput");
        Objects.requireNonNull(cpuDiagnostics, "cpuDiagnostics");
        Objects.requireNonNull(memoryDiagnostics, "memoryDiagnostics");
        Objects.requireNonNull(ioDiagnostics, "ioDiagnostics");
        Objects.requireNonNull(cgroup, "cgroup");
        Objects.requireNonNull(ccc, "ccc");
        if (windowDuration.compareTo(samplingInterval) < 0) {
            throw new IllegalArgumentException(
                    "windowDuration must not be shorter than samplingInterval");
        }
        requireObservationWindow(
                windowDuration,
                samplingInterval,
                "cpu",
                cpuDiagnostics.elevatedDuration(),
                cpuDiagnostics.highDuration(),
                cpuDiagnostics.criticalDuration(),
                cpuDiagnostics.recoveryDuration());
        requireObservationWindow(
                windowDuration,
                samplingInterval,
                "memory",
                memoryDiagnostics.elevatedDuration(),
                memoryDiagnostics.highDuration(),
                memoryDiagnostics.criticalDuration(),
                memoryDiagnostics.recoveryDuration());
        requireObservationWindow(
                windowDuration,
                samplingInterval,
                "io",
                ioDiagnostics.elevatedDuration(),
                ioDiagnostics.highDuration(),
                ioDiagnostics.criticalDuration(),
                ioDiagnostics.recoveryDuration());
    }

    public BullseyeConfiguration(
            String host,
            String application,
            Duration samplingInterval,
            Duration windowDuration,
            Path stateOutput,
            CpuDiagnostics cpuDiagnostics,
            MemoryDiagnostics memoryDiagnostics,
            IoDiagnostics ioDiagnostics,
            Cgroup cgroup) {
        this(
                host,
                application,
                samplingInterval,
                windowDuration,
                stateOutput,
                cpuDiagnostics,
                memoryDiagnostics,
                ioDiagnostics,
                cgroup,
                Ccc.disabled());
    }

    public BullseyeConfiguration(
            String host,
            String application,
            Duration samplingInterval,
            Duration windowDuration,
            Path stateOutput,
            CpuDiagnostics cpuDiagnostics) {
        this(
                host,
                application,
                samplingInterval,
                windowDuration,
                stateOutput,
                cpuDiagnostics,
                MemoryDiagnostics.defaults(),
                IoDiagnostics.defaults(),
                Cgroup.defaults());
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
            Duration recoveryDuration,
            double psiCorroborationThreshold,
            double psiRecoveryThreshold) {

        public CpuDiagnostics {
            requirePercentage(elevatedThreshold, "elevatedThreshold");
            requirePercentage(highThreshold, "highThreshold");
            requirePercentage(criticalThreshold, "criticalThreshold");
            requirePercentage(recoveryThreshold, "recoveryThreshold");
            requirePercentage(psiCorroborationThreshold, "psiCorroborationThreshold");
            requirePercentage(psiRecoveryThreshold, "psiRecoveryThreshold");
            requirePositive(elevatedDuration, "elevatedDuration");
            requirePositive(highDuration, "highDuration");
            requirePositive(criticalDuration, "criticalDuration");
            requirePositive(recoveryDuration, "recoveryDuration");
            if (!Double.isFinite(highMinimumRise) || highMinimumRise < 0) {
                throw new IllegalArgumentException(
                        "highMinimumRise must be finite and non-negative");
            }
            if (!(recoveryThreshold < elevatedThreshold
                    && elevatedThreshold < highThreshold
                    && highThreshold < criticalThreshold)) {
                throw new IllegalArgumentException(
                        "CPU thresholds must satisfy recovery < elevated < high < critical");
            }
        }

        public CpuDiagnostics(
                double elevatedThreshold,
                Duration elevatedDuration,
                double highThreshold,
                Duration highDuration,
                double highMinimumRise,
                double criticalThreshold,
                Duration criticalDuration,
                double recoveryThreshold,
                Duration recoveryDuration) {
            this(
                    elevatedThreshold,
                    elevatedDuration,
                    highThreshold,
                    highDuration,
                    highMinimumRise,
                    criticalThreshold,
                    criticalDuration,
                    recoveryThreshold,
                    recoveryDuration,
                    5,
                    1);
        }
    }

    public record MemoryDiagnostics(
            double elevatedUsage,
            double elevatedPsiSome,
            Duration elevatedDuration,
            double highUsage,
            double highPsiSome,
            double highMinimumRise,
            Duration highDuration,
            double criticalUsage,
            double criticalPsiSome,
            double criticalPsiFull,
            Duration criticalDuration,
            double recoveryUsage,
            double recoveryPsiSome,
            double recoveryPsiFull,
            Duration recoveryDuration) {

        public MemoryDiagnostics {
            requirePercentage(elevatedUsage, "elevatedUsage");
            requirePercentage(elevatedPsiSome, "elevatedPsiSome");
            requirePercentage(highUsage, "highUsage");
            requirePercentage(highPsiSome, "highPsiSome");
            requirePercentage(criticalUsage, "criticalUsage");
            requirePercentage(criticalPsiSome, "criticalPsiSome");
            requirePercentage(criticalPsiFull, "criticalPsiFull");
            requirePercentage(recoveryUsage, "recoveryUsage");
            requirePercentage(recoveryPsiSome, "recoveryPsiSome");
            requirePercentage(recoveryPsiFull, "recoveryPsiFull");
            requirePositive(elevatedDuration, "elevatedDuration");
            requirePositive(highDuration, "highDuration");
            requirePositive(criticalDuration, "criticalDuration");
            requirePositive(recoveryDuration, "recoveryDuration");
            requireNonNegative(highMinimumRise, "highMinimumRise");
            if (!(recoveryUsage < elevatedUsage
                    && elevatedUsage < highUsage
                    && highUsage < criticalUsage)) {
                throw new IllegalArgumentException(
                        "Memory usage thresholds must satisfy recovery < elevated < high <"
                                + " critical");
            }
        }

        public static MemoryDiagnostics defaults() {
            return new MemoryDiagnostics(
                    80,
                    10,
                    Duration.ofSeconds(10),
                    85,
                    20,
                    0,
                    Duration.ofSeconds(10),
                    90,
                    40,
                    5,
                    Duration.ofSeconds(5),
                    70,
                    2,
                    1,
                    Duration.ofSeconds(30));
        }
    }

    public record IoDiagnostics(
            double elevatedPsiSome,
            Duration elevatedDuration,
            double highPsiSome,
            double highPsiFull,
            Duration highDuration,
            double criticalPsiFull,
            Duration criticalDuration,
            double recoveryPsiSome,
            double recoveryPsiFull,
            Duration recoveryDuration) {

        public IoDiagnostics {
            requirePercentage(elevatedPsiSome, "elevatedPsiSome");
            requirePercentage(highPsiSome, "highPsiSome");
            requirePercentage(highPsiFull, "highPsiFull");
            requirePercentage(criticalPsiFull, "criticalPsiFull");
            requirePercentage(recoveryPsiSome, "recoveryPsiSome");
            requirePercentage(recoveryPsiFull, "recoveryPsiFull");
            requirePositive(elevatedDuration, "elevatedDuration");
            requirePositive(highDuration, "highDuration");
            requirePositive(criticalDuration, "criticalDuration");
            requirePositive(recoveryDuration, "recoveryDuration");
            if (!(recoveryPsiSome < elevatedPsiSome && elevatedPsiSome < highPsiSome)) {
                throw new IllegalArgumentException(
                        "IO some thresholds must satisfy recovery < elevated < high");
            }
            if (!(recoveryPsiFull < highPsiFull && highPsiFull < criticalPsiFull)) {
                throw new IllegalArgumentException(
                        "IO full thresholds must satisfy recovery < high < critical");
            }
        }

        public static IoDiagnostics defaults() {
            return new IoDiagnostics(
                    10,
                    Duration.ofSeconds(10),
                    25,
                    3,
                    Duration.ofSeconds(10),
                    10,
                    Duration.ofSeconds(5),
                    2,
                    1,
                    Duration.ofSeconds(30));
        }
    }

    public record AttributionConfidence(
            int minimumObservations,
            Duration minimumDuration,
            int maximumConfirmationCollections,
            double minimumMargin) {

        public AttributionConfidence {
            if (minimumObservations < 2) {
                throw new IllegalArgumentException("minimumObservations must be at least 2");
            }
            requirePositive(minimumDuration, "minimumDuration");
            if (maximumConfirmationCollections < 0) {
                throw new IllegalArgumentException("maximumConfirmationCollections must not be negative");
            }
            requirePercentage(minimumMargin, "minimumMargin");
        }

        public static AttributionConfidence defaults() {
            return new AttributionConfidence(3, Duration.ofSeconds(10), 3, 5);
        }
    }

    public record Cgroup(
            boolean enabled,
            Path root,
            Duration discoveryInterval,
            int maximumDepth,
            int maximumGroups,
            double attributionMinimumScore,
            double attributionDominanceRatio,
            Duration attributionRefreshInterval,
            AttributionConfidence attributionConfidence) {

        public Cgroup {
            Objects.requireNonNull(root, "root");
            requirePositive(discoveryInterval, "discoveryInterval");
            requirePositive(attributionRefreshInterval, "attributionRefreshInterval");
            Objects.requireNonNull(attributionConfidence, "attributionConfidence");
            if (maximumDepth < 1 || maximumGroups < 1) {
                throw new IllegalArgumentException("cgroup bounds must be positive");
            }
            requireNonNegative(attributionMinimumScore, "attributionMinimumScore");
            if (!Double.isFinite(attributionDominanceRatio) || attributionDominanceRatio <= 1) {
                throw new IllegalArgumentException(
                        "attributionDominanceRatio must be greater than 1");
            }
        }

        public Cgroup(
                boolean enabled,
                Path root,
                Duration discoveryInterval,
                int maximumDepth,
                int maximumGroups,
                double attributionMinimumScore,
                double attributionDominanceRatio,
                Duration attributionRefreshInterval) {
            this(
                    enabled,
                    root,
                    discoveryInterval,
                    maximumDepth,
                    maximumGroups,
                    attributionMinimumScore,
                    attributionDominanceRatio,
                    attributionRefreshInterval,
                    AttributionConfidence.defaults());
        }

        public Cgroup(
                boolean enabled,
                Path root,
                Duration discoveryInterval,
                int maximumDepth,
                int maximumGroups,
                double attributionMinimumScore,
                double attributionDominanceRatio) {
            this(
                    enabled,
                    root,
                    discoveryInterval,
                    maximumDepth,
                    maximumGroups,
                    attributionMinimumScore,
                    attributionDominanceRatio,
                    Duration.ofSeconds(30));
        }

        public static Cgroup defaults() {
            return new Cgroup(
                    true, Path.of("/sys/fs/cgroup"), Duration.ofSeconds(60), 8, 512, 5, 1.2);
        }
    }

    public record Ccc(boolean enabled, String host, int port, String nodeId, boolean tccEnabled) {

        public Ccc {
            if (tccEnabled && !enabled) {
                throw new IllegalArgumentException(
                        "bullseye.tcc.enabled requires bullseye.ccc.enabled=true");
            }
            if (enabled) {
                requireText(host, "bullseye.ccc.host");
                requireText(nodeId, "bullseye.ccc.node-id");
                if (port < 1 || port > 65_535) {
                    throw new IllegalArgumentException(
                            "bullseye.ccc.port must be between 1 and 65535");
                }
            }
        }

        public Ccc(boolean enabled, String host, int port, String nodeId) {
            this(enabled, host, port, nodeId, false);
        }

        public static Ccc disabled() {
            return new Ccc(false, "", 0, "");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        long millis;
        try {
            millis = value.toMillis();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " must fit in milliseconds", overflow);
        }
        if (millis < 1) {
            throw new IllegalArgumentException(name + " must be at least 1ms");
        }
    }

    private static void requireObservationWindow(
            Duration window,
            Duration samplingInterval,
            String resource,
            Duration... durations) {
        String[] phases = {"elevated", "high", "critical", "recovery"};
        int longest = 0;
        for (int index = 1; index < durations.length; index++) {
            if (durations[index].compareTo(durations[longest]) > 0) {
                longest = index;
            }
        }
        Duration maximumSampleGap = samplingInterval.multipliedBy(2);
        // Keep the sample just before the duration boundary despite an allowed collection gap.
        Duration minimumWindow = durations[longest].plus(maximumSampleGap);
        if (window.compareTo(minimumWindow) < 0) {
            throw new IllegalArgumentException(
                    "bullseye.window.duration=" + window
                            + " must be at least " + minimumWindow
                            + " for bullseye.diagnostics." + resource + "." + phases[longest]
                            + ".duration=" + durations[longest]
                            + " plus the maximum sample gap (" + maximumSampleGap + ")");
        }
    }

    private static void requirePercentage(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
