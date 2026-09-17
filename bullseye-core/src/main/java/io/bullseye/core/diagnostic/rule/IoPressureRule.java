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

public final class IoPressureRule implements DiagnosticRule {

    private final BullseyeConfiguration.IoDiagnostics configuration;
    private final Duration maximumSampleAge;

    public IoPressureRule(
            BullseyeConfiguration.IoDiagnostics configuration, Duration maximumSampleAge) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.maximumSampleAge = Objects.requireNonNull(maximumSampleAge, "maximumSampleAge");
    }

    @Override
    public ResourceType resource() {
        return ResourceType.DISK_IO;
    }

    @Override
    public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
        Optional<MetricSample> some = latest(context, MetricType.HOST_IO_PSI_SOME_AVG10);
        Optional<MetricSample> full = latest(context, MetricType.HOST_IO_PSI_FULL_AVG10);
        if (some.isEmpty()) {
            return Optional.empty();
        }

        DiagnosticDecision current = context.resourceState(resource());
        if (current.severity() != Severity.NORMAL && recovered(context, full.isPresent())) {
            return Optional.of(
                    decision(
                            Severity.NORMAL,
                            ResourceState.NORMAL,
                            "IO pressure cleared.",
                            some.orElseThrow().value(),
                            full.map(MetricSample::value).orElse(null),
                            configuration.recoveryDuration()));
        }

        DiagnosticDecision candidate =
                escalation(
                                context,
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
            DiagnosticContext context, double some, Double full) {
        if (full != null
                && sustained(
                        context,
                        MetricType.HOST_IO_PSI_FULL_AVG10,
                        configuration.criticalDuration(),
                        value -> value >= configuration.criticalPsiFull())) {
            return Optional.of(
                    decision(
                            Severity.CRITICAL,
                            ResourceState.SATURATED,
                            "Critical IO saturation detected.",
                            some,
                            full,
                            configuration.criticalDuration()));
        }

        boolean high =
                sustained(
                                context,
                                MetricType.HOST_IO_PSI_SOME_AVG10,
                                configuration.highDuration(),
                                value -> value >= configuration.highPsiSome())
                        || (full != null
                                && sustained(
                                        context,
                                        MetricType.HOST_IO_PSI_FULL_AVG10,
                                        configuration.highDuration(),
                                        value -> value >= configuration.highPsiFull()));
        if (high) {
            return Optional.of(
                    decision(
                            Severity.HIGH,
                            ResourceState.SATURATION_RISK,
                            "Warning. IO saturation risk detected.",
                            some,
                            full,
                            configuration.highDuration()));
        }

        if (sustained(
                context,
                MetricType.HOST_IO_PSI_SOME_AVG10,
                configuration.elevatedDuration(),
                value -> value >= configuration.elevatedPsiSome())) {
            return Optional.of(
                    decision(
                            Severity.ELEVATED,
                            ResourceState.PRESSURE,
                            "IO pressure detected.",
                            some,
                            full,
                            configuration.elevatedDuration()));
        }
        return Optional.empty();
    }

    private boolean recovered(DiagnosticContext context, boolean fullAvailable) {
        boolean someStable =
                sustained(
                        context,
                        MetricType.HOST_IO_PSI_SOME_AVG10,
                        configuration.recoveryDuration(),
                        value -> value <= configuration.recoveryPsiSome());
        return someStable
                && (!fullAvailable
                        || sustained(
                                context,
                                MetricType.HOST_IO_PSI_FULL_AVG10,
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
            double some,
            Double full,
            Duration duration) {
        List<DiagnosticEvidence> evidence = new ArrayList<>(3);
        evidence.add(MetricConditions.evidence("psi.some.avg10", some, "%"));
        StringBuilder reason =
                new StringBuilder(announcement)
                        .append(" psi.some=")
                        .append(MetricConditions.percent(some));
        if (full != null) {
            evidence.add(MetricConditions.evidence("psi.full.avg10", full, "%"));
            reason.append(" psi.full=").append(MetricConditions.percent(full));
        }
        reason.append(" sustained=").append(MetricConditions.duration(duration));
        evidence.add(MetricConditions.evidence("duration", duration.toMillis() / 1_000.0, "s"));
        double score =
                Math.max(
                                ratio(some, configuration.elevatedPsiSome()),
                                ratio(full == null ? 0 : full, configuration.highPsiFull()))
                        * 100;
        return new DiagnosticDecision(
                resource(), state, severity, reason.toString(), evidence, score);
    }

    private static double ratio(double value, double threshold) {
        return threshold == 0 ? value : value / threshold;
    }
}
