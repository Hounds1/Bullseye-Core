package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.DiagnosticEvidence;
import io.bullseye.common.metric.MetricSample;
import io.bullseye.common.metric.MetricType;
import io.bullseye.core.metric.MetricView;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoublePredicate;

public final class MetricConditions {

    private MetricConditions() {}

    public static Optional<MetricSample> latest(
            MetricView window, MetricType type, long now, Duration maximumAge) {
        List<MetricSample> samples = window.get(type);
        if (samples.isEmpty()) {
            return Optional.empty();
        }
        MetricSample sample = samples.getLast();
        return isFresh(sample, now, maximumAge) ? Optional.of(sample) : Optional.empty();
    }

    public static boolean hasExpired(
            MetricView window, MetricType type, long now, Duration maximumAge) {
        List<MetricSample> samples = window.get(type);
        return !samples.isEmpty() && !isFresh(samples.getLast(), now, maximumAge);
    }

    public static boolean sustained(
            MetricView window,
            MetricType type,
            long now,
            Duration duration,
            Duration maximumAge,
            DoublePredicate predicate) {
        return !sustainedRun(window, type, now, duration, maximumAge, predicate).isEmpty();
    }

    public static List<MetricSample> sustainedRun(
            MetricView window,
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
        long newerTimestamp = samples.getLast().timestamp();
        for (int index = samples.size() - 1; index >= 0; index--) {
            MetricSample sample = samples.get(index);
            if (sample.timestamp() > now
                    || newerTimestamp - sample.timestamp() > maximumAge.toMillis()
                    || !predicate.test(sample.value())) {
                break;
            }
            start = index;
            newerTimestamp = sample.timestamp();
        }
        if (start == samples.size()) {
            return List.of();
        }
        List<MetricSample> run = samples.subList(start, samples.size());
        // Only observed time counts; an outage cannot complete a sustained condition.
        long coveredMillis = run.getLast().timestamp() - run.getFirst().timestamp();
        return run.size() >= 2 && coveredMillis >= duration.toMillis()
                ? List.copyOf(run)
                : List.of();
    }

    public static List<MetricSample> recentRun(
            MetricView window,
            MetricType type,
            long now,
            Duration duration,
            Duration maximumAge,
            DoublePredicate predicate) {
        List<MetricSample> run =
                sustainedRun(window, type, now, duration, maximumAge, predicate);
        if (run.isEmpty()) {
            return List.of();
        }
        long newestTimestamp = run.getLast().timestamp();
        int start = run.size() - 2;
        // Keep the nearest observed boundary covering the interval, without interpolation.
        while (start > 0
                && newestTimestamp - run.get(start).timestamp() < duration.toMillis()) {
            start--;
        }
        return List.copyOf(run.subList(start, run.size()));
    }

    public static Duration observedDuration(List<MetricSample> run) {
        return run.isEmpty()
                ? Duration.ZERO
                : Duration.ofMillis(run.getLast().timestamp() - run.getFirst().timestamp());
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
        long millis = duration.toMillis();
        if (millis >= 60_000 && millis % 60_000 == 0) {
            return millis / 60_000 + "m";
        }
        return BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString() + "s";
    }

    private static boolean isFresh(MetricSample sample, long now, Duration maximumAge) {
        return sample.timestamp() <= now && now - sample.timestamp() <= maximumAge.toMillis();
    }
}
