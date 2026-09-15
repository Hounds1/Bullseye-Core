package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.core.metric.MetricWindow;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoublePredicate;

public final class MetricConditions {

    private MetricConditions() {}

    public static Optional<MetricSample> latest(
            MetricWindow window, MetricType type, long now, Duration maximumAge) {
        List<MetricSample> samples = window.get(type);
        if (samples.isEmpty()) {
            return Optional.empty();
        }
        MetricSample sample = samples.getLast();
        return isFresh(sample, now, maximumAge) ? Optional.of(sample) : Optional.empty();
    }

    public static boolean sustained(
            MetricWindow window,
            MetricType type,
            long now,
            Duration duration,
            Duration maximumAge,
            DoublePredicate predicate) {
        return !sustainedRun(window, type, now, duration, maximumAge, predicate).isEmpty();
    }

    public static List<MetricSample> sustainedRun(
            MetricWindow window,
            MetricType type,
            long now,
            Duration duration,
            Duration maximumAge,
            DoublePredicate predicate) {
        List<MetricSample> samples = window.get(type);
        if (samples.isEmpty() || !isFresh(samples.getLast(), now, maximumAge)) {
            return List.of();
        }
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
        return run.size() >= 2 && coveredMillis >= duration.toMillis()
                ? List.copyOf(run)
                : List.of();
    }

    public static double rise(List<MetricSample> run) {
        return run.isEmpty() ? 0 : run.getLast().value() - run.getFirst().value();
    }

    public static DiagnosticEvidence evidence(String metric, double value, String unit) {
        return new DiagnosticEvidence(metric, value, unit);
    }

    public static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    public static String points(double value) {
        return String.format(Locale.ROOT, "%+.1fpp", value);
    }

    public static String duration(Duration duration) {
        if (duration.toMinutes() > 0 && duration.toSecondsPart() == 0) {
            return duration.toMinutes() + "m";
        }
        return duration.toSeconds() + "s";
    }

    private static boolean isFresh(MetricSample sample, long now, Duration maximumAge) {
        return sample.timestamp() <= now && now - sample.timestamp() <= maximumAge.toMillis();
    }
}
