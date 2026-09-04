package io.bullseye.core.diagnostic;

import io.bullseye.common.DiagnosticSnapshot;
import io.bullseye.common.MetricSample;
import io.bullseye.common.MetricType;
import io.bullseye.common.ResourceState;
import io.bullseye.common.ResourceType;
import io.bullseye.common.Severity;
import io.bullseye.core.config.BullseyeConfiguration;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoublePredicate;

public final class CpuPressureRule implements DiagnosticRule {

    private final BullseyeConfiguration.CpuDiagnostics configuration;
    private final long maximumSampleAgeMillis;

    public CpuPressureRule(
            BullseyeConfiguration.CpuDiagnostics configuration,
            Duration maximumSampleAge
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(maximumSampleAge, "maximumSampleAge");
        if (maximumSampleAge.isZero() || maximumSampleAge.isNegative()) {
            throw new IllegalArgumentException("maximumSampleAge must be positive");
        }
        this.maximumSampleAgeMillis = maximumSampleAge.toMillis();
    }

    @Override
    public Optional<DiagnosticDecision> evaluate(DiagnosticContext context) {
        List<MetricSample> samples = context.metrics().get(MetricType.HOST_CPU_USAGE);
        DiagnosticSnapshot current = context.currentState();
        boolean ownsCurrentState = current.resource() == ResourceType.HOST_CPU
                && current.severity() != Severity.NORMAL;

        if (samples.isEmpty() || isStale(samples.getLast(), context.evaluatedAt())) {
            return ownsCurrentState ? Optional.of(hold(current)) : Optional.empty();
        }

        Optional<DiagnosticDecision> escalation = escalation(samples, context.evaluatedAt());
        if (!ownsCurrentState) {
            return escalation;
        }

        if (escalation.isPresent()
                && escalation.get().severity().isHigherThan(current.severity())) {
            return escalation;
        }

        if (isSustained(
                samples,
                context.evaluatedAt(),
                configuration.recoveryDuration(),
                value -> value <= configuration.recoveryThreshold()
        )) {
            return Optional.of(new DiagnosticDecision(
                    ResourceType.HOST_CPU,
                    ResourceState.NORMAL,
                    Severity.NORMAL,
                    evidence(
                            "CPU pressure cleared.",
                            samples.getLast().value(),
                            configuration.recoveryDuration()
                    )
            ));
        }

        return Optional.of(hold(current));
    }

    private Optional<DiagnosticDecision> escalation(List<MetricSample> samples, long now) {
        if (isSustained(
                samples,
                now,
                configuration.criticalDuration(),
                value -> value >= configuration.criticalThreshold()
        )) {
            return Optional.of(decision(
                    Severity.CRITICAL,
                    ResourceState.SATURATED,
                    evidence(
                            "Critical CPU saturation detected.",
                            samples.getLast().value(),
                            configuration.criticalDuration()
                    )
            ));
        }

        List<MetricSample> highRun = sustainedRun(
                samples,
                now,
                configuration.highDuration(),
                value -> value >= configuration.highThreshold()
        );
        if (!highRun.isEmpty()
                && highRun.getLast().value() - highRun.getFirst().value()
                > configuration.highMinimumRise()) {
            return Optional.of(decision(
                    Severity.HIGH,
                    ResourceState.SATURATION_RISK,
                    evidenceWithRise(
                            "Warning. CPU saturation risk detected.",
                            highRun.getLast().value(),
                            highRun.getLast().value() - highRun.getFirst().value(),
                            configuration.highDuration()
                    )
            ));
        }

        if (isSustained(
                samples,
                now,
                configuration.elevatedDuration(),
                value -> value >= configuration.elevatedThreshold()
        )) {
            return Optional.of(decision(
                    Severity.ELEVATED,
                    ResourceState.PRESSURE,
                    evidence(
                            "CPU pressure detected.",
                            samples.getLast().value(),
                            configuration.elevatedDuration()
                    )
            ));
        }
        return Optional.empty();
    }

    private DiagnosticDecision decision(Severity severity, ResourceState state, String reason) {
        return new DiagnosticDecision(ResourceType.HOST_CPU, state, severity, reason);
    }

    private static DiagnosticDecision hold(DiagnosticSnapshot current) {
        return new DiagnosticDecision(
                current.resource(),
                current.state(),
                current.severity(),
                "CPU pressure remains active."
        );
    }

    private static String evidence(String announcement, double usage, Duration duration) {
        return announcement
                + " usage=" + percentage(usage)
                + " sustained=" + format(duration);
    }

    private static String evidenceWithRise(
            String announcement,
            double usage,
            double rise,
            Duration duration
    ) {
        return announcement
                + " usage=" + percentage(usage)
                + " rise=" + String.format(Locale.ROOT, "%+.1fpp", rise)
                + '/' + format(duration);
    }

    private static String percentage(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    private static String format(Duration duration) {
        if (duration.toMinutes() > 0 && duration.toSecondsPart() == 0) {
            return duration.toMinutes() + "m";
        }
        return duration.toSeconds() + "s";
    }

    private boolean isStale(MetricSample sample, long now) {
        return sample.timestamp() > now || now - sample.timestamp() > maximumSampleAgeMillis;
    }

    private static boolean isSustained(
            List<MetricSample> samples,
            long now,
            Duration duration,
            DoublePredicate predicate
    ) {
        return !sustainedRun(samples, now, duration, predicate).isEmpty();
    }

    private static List<MetricSample> sustainedRun(
            List<MetricSample> samples,
            long now,
            Duration duration,
            DoublePredicate predicate
    ) {
        int start = samples.size();
        for (int index = samples.size() - 1; index >= 0; index--) {
            MetricSample sample = samples.get(index);
            if (sample.timestamp() > now || !predicate.test(sample.value())) {
                break;
            }
            start = index;
        }
        if (start == samples.size()) {
            return List.of();
        }
        List<MetricSample> run = samples.subList(start, samples.size());
        long coveredMillis = now - run.getFirst().timestamp();
        return run.size() >= 2 && coveredMillis >= duration.toMillis() ? run : List.of();
    }
}
