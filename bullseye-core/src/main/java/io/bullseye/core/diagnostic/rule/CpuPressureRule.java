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

public final class CpuPressureRule implements DiagnosticRule {

    private final BullseyeConfiguration.CpuDiagnostics configuration;
    private final Duration maximumSampleAge;

    public CpuPressureRule(
            BullseyeConfiguration.CpuDiagnostics configuration, Duration maximumSampleAge) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.maximumSampleAge = Objects.requireNonNull(maximumSampleAge, "maximumSampleAge");
    }

    @Override
    public ResourceType resource() {
        return ResourceType.HOST_CPU;
    }

    @Override
    public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
        Optional<MetricSample> usage =
                MetricConditions.latest(
                        context.metrics(),
                        MetricType.HOST_CPU_USAGE,
                        context.evaluatedAt(),
                        maximumSampleAge);
        if (usage.isEmpty()) {
            return Optional.empty();
        }

        DiagnosticDecision current = context.resourceState(resource());
        Optional<MetricSample> psi =
                MetricConditions.latest(
                        context.metrics(),
                        MetricType.HOST_CPU_PSI_SOME_AVG10,
                        context.evaluatedAt(),
                        maximumSampleAge);

        if (current.severity() != Severity.NORMAL && recovered(context, psi.isPresent())) {
            return Optional.of(
                    decision(
                            Severity.NORMAL,
                            ResourceState.NORMAL,
                            "CPU pressure cleared.",
                            usage.orElseThrow().value(),
                            psi.map(MetricSample::value).orElse(null),
                            null,
                            configuration.recoveryDuration()));
        }

        DiagnosticDecision candidate = escalation(context, usage.orElseThrow(), psi).orElse(null);
        if (candidate == null) {
            return Optional.empty();
        }
        if (candidate.severity().ordinal() < current.severity().ordinal()) {
            return Optional.of(current);
        }
        return Optional.of(candidate);
    }

    private Optional<DiagnosticDecision> escalation(
            DiagnosticContext context, MetricSample usage, Optional<MetricSample> psi) {
        long now = context.evaluatedAt();
        Severity severity;
        ResourceState state;
        String announcement;
        Duration duration;
        Double rise = null;

        if (MetricConditions.sustained(
                context.metrics(),
                MetricType.HOST_CPU_USAGE,
                now,
                configuration.criticalDuration(),
                maximumSampleAge,
                value -> value >= configuration.criticalThreshold())) {
            severity = Severity.CRITICAL;
            state = ResourceState.SATURATED;
            announcement = "Critical CPU saturation detected.";
            duration = configuration.criticalDuration();
        } else {
            List<MetricSample> highRun =
                    MetricConditions.sustainedRun(
                            context.metrics(),
                            MetricType.HOST_CPU_USAGE,
                            now,
                            configuration.highDuration(),
                            maximumSampleAge,
                            value -> value >= configuration.highThreshold());
            if (!highRun.isEmpty()
                    && MetricConditions.rise(highRun) > configuration.highMinimumRise()) {
                severity = Severity.HIGH;
                state = ResourceState.SATURATION_RISK;
                announcement = "Warning. CPU saturation risk detected.";
                duration = configuration.highDuration();
                rise = MetricConditions.rise(highRun);
            } else if (MetricConditions.sustained(
                    context.metrics(),
                    MetricType.HOST_CPU_USAGE,
                    now,
                    configuration.elevatedDuration(),
                    maximumSampleAge,
                    value -> value >= configuration.elevatedThreshold())) {
                severity = Severity.ELEVATED;
                state = ResourceState.PRESSURE;
                announcement = "CPU pressure detected.";
                duration = configuration.elevatedDuration();
            } else {
                return Optional.empty();
            }
        }

        if (psi.isPresent()
                && severity.ordinal() >= Severity.HIGH.ordinal()
                && psi.orElseThrow().value() < configuration.psiCorroborationThreshold()) {
            severity = severity == Severity.CRITICAL ? Severity.HIGH : Severity.ELEVATED;
            state =
                    severity == Severity.HIGH
                            ? ResourceState.SATURATION_RISK
                            : ResourceState.PRESSURE;
            announcement = "CPU utilization elevated without PSI corroboration.";
        }
        return Optional.of(
                decision(
                        severity,
                        state,
                        announcement,
                        usage.value(),
                        psi.map(MetricSample::value).orElse(null),
                        rise,
                        duration));
    }

    private boolean recovered(DiagnosticContext context, boolean psiAvailable) {
        boolean usageStable =
                MetricConditions.sustained(
                        context.metrics(),
                        MetricType.HOST_CPU_USAGE,
                        context.evaluatedAt(),
                        configuration.recoveryDuration(),
                        maximumSampleAge,
                        value -> value <= configuration.recoveryThreshold());
        return usageStable
                && (!psiAvailable
                        || MetricConditions.sustained(
                                context.metrics(),
                                MetricType.HOST_CPU_PSI_SOME_AVG10,
                                context.evaluatedAt(),
                                configuration.recoveryDuration(),
                                maximumSampleAge,
                                value -> value <= configuration.psiRecoveryThreshold()));
    }

    private DiagnosticDecision decision(
            Severity severity,
            ResourceState state,
            String announcement,
            double usage,
            Double psi,
            Double rise,
            Duration duration) {
        List<DiagnosticEvidence> evidence = new ArrayList<>(4);
        evidence.add(MetricConditions.evidence("usage", usage, "%"));
        StringBuilder reason =
                new StringBuilder(announcement)
                        .append(" usage=")
                        .append(MetricConditions.percent(usage));
        if (psi != null) {
            evidence.add(MetricConditions.evidence("psi.some.avg10", psi, "%"));
            reason.append(" psi.some=").append(MetricConditions.percent(psi));
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
        double usageRatio = ratio(usage, configuration.elevatedThreshold());
        double psiRatio = psi == null ? 0 : ratio(psi, configuration.psiCorroborationThreshold());
        return new DiagnosticDecision(
                resource(),
                state,
                severity,
                reason.toString(),
                evidence,
                Math.max(usageRatio, psiRatio) * 100);
    }

    private static double ratio(double value, double threshold) {
        return threshold == 0 ? value : value / threshold;
    }
}
