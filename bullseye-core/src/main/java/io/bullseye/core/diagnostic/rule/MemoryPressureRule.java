package io.bullseye.core.diagnostic.rule;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.diagnostic.ResourceState;
import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.diagnostic.Severity;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.core.config.BullseyeConfiguration;
import io.bullseye.core.diagnostic.DiagnosticContext;
import io.bullseye.core.diagnostic.DiagnosticDecision;
import io.bullseye.core.diagnostic.MetricConditions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MemoryPressureRule implements DiagnosticRule {

    private final BullseyeConfiguration.MemoryDiagnostics configuration;
    private final Duration maximumSampleAge;

    public MemoryPressureRule(
            BullseyeConfiguration.MemoryDiagnostics configuration, Duration maximumSampleAge) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.maximumSampleAge = Objects.requireNonNull(maximumSampleAge, "maximumSampleAge");
    }

    @Override
    public ResourceType resource() {
        return ResourceType.HOST_MEMORY;
    }

    @Override
    public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
        Optional<MetricSample> usage = latest(context, MetricType.HOST_MEMORY_USAGE);
        Optional<MetricSample> some = latest(context, MetricType.HOST_MEMORY_PSI_SOME_AVG10);
        Optional<MetricSample> full = latest(context, MetricType.HOST_MEMORY_PSI_FULL_AVG10);
        if (usage.isEmpty() || some.isEmpty()) {
            return Optional.empty();
        }

        DiagnosticDecision current = context.resourceState(resource());
        if (current.severity() != Severity.NORMAL && recovered(context, full.isPresent())) {
            return Optional.of(
                    decision(
                            Severity.NORMAL,
                            ResourceState.NORMAL,
                            "Memory pressure cleared.",
                            usage.orElseThrow().value(),
                            some.orElseThrow().value(),
                            full.map(MetricSample::value).orElse(null),
                            null,
                            configuration.recoveryDuration()));
        }

        DiagnosticDecision candidate =
                escalation(
                                context,
                                usage.orElseThrow().value(),
                                some.orElseThrow().value(),
                                full.map(MetricSample::value).orElse(null))
                        .orElse(null);
        if (candidate == null) {
            return Optional.empty();
        }
        if (candidate.severity().ordinal() < current.severity().ordinal()) {
            return Optional.of(current);
        }
        return Optional.of(candidate);
    }

    private Optional<DiagnosticDecision> escalation(
            DiagnosticContext context, double usage, double some, Double full) {
        long now = context.evaluatedAt();
        boolean criticalUsage =
                sustained(
                        context,
                        MetricType.HOST_MEMORY_USAGE,
                        configuration.criticalDuration(),
                        value -> value >= configuration.criticalUsage());
        boolean criticalPressure =
                sustained(
                                context,
                                MetricType.HOST_MEMORY_PSI_SOME_AVG10,
                                configuration.criticalDuration(),
                                value -> value >= configuration.criticalPsiSome())
                        || (full != null
                                && sustained(
                                        context,
                                        MetricType.HOST_MEMORY_PSI_FULL_AVG10,
                                        configuration.criticalDuration(),
                                        value -> value >= configuration.criticalPsiFull()));
        if (criticalUsage && criticalPressure) {
            return Optional.of(
                    decision(
                            Severity.CRITICAL,
                            ResourceState.SATURATED,
                            "Critical memory saturation detected.",
                            usage,
                            some,
                            full,
                            null,
                            configuration.criticalDuration()));
        }

        List<MetricSample> highPressure =
                MetricConditions.sustainedRun(
                        context.metrics(),
                        MetricType.HOST_MEMORY_PSI_SOME_AVG10,
                        now,
                        configuration.highDuration(),
                        maximumSampleAge,
                        value -> value >= configuration.highPsiSome());
        boolean highUsage =
                sustained(
                        context,
                        MetricType.HOST_MEMORY_USAGE,
                        configuration.highDuration(),
                        value -> value >= configuration.highUsage());
        double rise = MetricConditions.rise(highPressure);
        if (highUsage && !highPressure.isEmpty() && rise > configuration.highMinimumRise()) {
            return Optional.of(
                    decision(
                            Severity.HIGH,
                            ResourceState.SATURATION_RISK,
                            "Warning. Memory saturation risk detected.",
                            usage,
                            some,
                            full,
                            rise,
                            configuration.highDuration()));
        }

        boolean elevated =
                sustained(
                                context,
                                MetricType.HOST_MEMORY_USAGE,
                                configuration.elevatedDuration(),
                                value -> value >= configuration.elevatedUsage())
                        && sustained(
                                context,
                                MetricType.HOST_MEMORY_PSI_SOME_AVG10,
                                configuration.elevatedDuration(),
                                value -> value >= configuration.elevatedPsiSome());
        if (elevated) {
            return Optional.of(
                    decision(
                            Severity.ELEVATED,
                            ResourceState.PRESSURE,
                            "Memory pressure detected.",
                            usage,
                            some,
                            full,
                            null,
                            configuration.elevatedDuration()));
        }
        return Optional.empty();
    }

    private boolean recovered(DiagnosticContext context, boolean fullAvailable) {
        boolean base =
                sustained(
                                context,
                                MetricType.HOST_MEMORY_USAGE,
                                configuration.recoveryDuration(),
                                value -> value <= configuration.recoveryUsage())
                        && sustained(
                                context,
                                MetricType.HOST_MEMORY_PSI_SOME_AVG10,
                                configuration.recoveryDuration(),
                                value -> value <= configuration.recoveryPsiSome());
        return base
                && (!fullAvailable
                        || sustained(
                                context,
                                MetricType.HOST_MEMORY_PSI_FULL_AVG10,
                                configuration.recoveryDuration(),
                                value -> value <= configuration.recoveryPsiFull()));
    }

    private Optional<MetricSample> latest(DiagnosticContext context, MetricType type) {
        return MetricConditions.latest(
                context.metrics(), type, context.evaluatedAt(), maximumSampleAge);
    }

    private boolean sustained(
            DiagnosticContext context,
            MetricType type,
            Duration duration,
            java.util.function.DoublePredicate predicate) {
        return MetricConditions.sustained(
                context.metrics(),
                type,
                context.evaluatedAt(),
                duration,
                maximumSampleAge,
                predicate);
    }

    private DiagnosticDecision decision(
            Severity severity,
            ResourceState state,
            String announcement,
            double usage,
            double some,
            Double full,
            Double rise,
            Duration duration) {
        List<DiagnosticEvidence> evidence = new ArrayList<>(5);
        evidence.add(MetricConditions.evidence("usage", usage, "%"));
        evidence.add(MetricConditions.evidence("psi.some.avg10", some, "%"));
        StringBuilder reason =
                new StringBuilder(announcement)
                        .append(" usage=")
                        .append(MetricConditions.percent(usage))
                        .append(" psi.some=")
                        .append(MetricConditions.percent(some));
        if (full != null) {
            evidence.add(MetricConditions.evidence("psi.full.avg10", full, "%"));
            reason.append(" psi.full=").append(MetricConditions.percent(full));
        }
        if (rise != null) {
            evidence.add(MetricConditions.evidence("trend", rise, "pp"));
            reason.append(" rise=")
                    .append(MetricConditions.points(rise))
                    .append('/')
                    .append(MetricConditions.duration(duration));
        } else {
            reason.append(" sustained=").append(MetricConditions.duration(duration));
        }
        evidence.add(MetricConditions.evidence("duration", duration.toMillis() / 1_000.0, "s"));
        double score =
                Math.max(
                                ratio(usage, configuration.elevatedUsage()),
                                Math.max(
                                        ratio(some, configuration.elevatedPsiSome()),
                                        ratio(
                                                full == null ? 0 : full,
                                                configuration.criticalPsiFull())))
                        * 100;
        return new DiagnosticDecision(
                resource(), state, severity, reason.toString(), evidence, score);
    }

    private static double ratio(double value, double threshold) {
        return threshold == 0 ? value : value / threshold;
    }
}
