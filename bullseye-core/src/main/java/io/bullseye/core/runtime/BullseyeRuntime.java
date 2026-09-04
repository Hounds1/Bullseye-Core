package io.bullseye.core.runtime;

import io.bullseye.common.DiagnosticSnapshot;
import io.bullseye.common.MetricSample;
import io.bullseye.core.collection.MetricCollector;
import io.bullseye.core.collection.MetricNormalizer;
import io.bullseye.core.diagnostic.DiagnosticCoordinator;
import io.bullseye.core.logging.BullseyeLogger;
import io.bullseye.core.publish.AsyncStatePublisher;
import io.bullseye.core.state.DiagnosticStateRepository;
import io.bullseye.core.store.MetricWindow;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BullseyeRuntime implements AutoCloseable {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final List<MetricCollector> collectors;
    private final MetricNormalizer normalizer;
    private final MetricWindow metricWindow;
    private final DiagnosticCoordinator diagnosticCoordinator;
    private final DiagnosticStateRepository stateRepository;
    private final AsyncStatePublisher publisher;
    private final BullseyeLogger logger;
    private final Clock clock;
    private final Duration samplingInterval;
    private final Duration windowDuration;
    private final ScheduledExecutorService collectorExecutor;
    private final ExecutorService diagnosticExecutor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean diagnosticPending = new AtomicBoolean();

    public BullseyeRuntime(
            List<MetricCollector> collectors,
            MetricNormalizer normalizer,
            MetricWindow metricWindow,
            DiagnosticCoordinator diagnosticCoordinator,
            DiagnosticStateRepository stateRepository,
            AsyncStatePublisher publisher,
            BullseyeLogger logger,
            Clock clock,
            Duration samplingInterval,
            Duration windowDuration
    ) {
        this.collectors = List.copyOf(Objects.requireNonNull(collectors, "collectors"));
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.metricWindow = Objects.requireNonNull(metricWindow, "metricWindow");
        this.diagnosticCoordinator = Objects.requireNonNull(
                diagnosticCoordinator,
                "diagnosticCoordinator"
        );
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.samplingInterval = Objects.requireNonNull(samplingInterval, "samplingInterval");
        this.windowDuration = Objects.requireNonNull(windowDuration, "windowDuration");
        this.collectorExecutor = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("bullseye-collector")
        );
        this.diagnosticExecutor = Executors.newSingleThreadExecutor(
                new NamedThreadFactory("bullseye-diagnostic")
        );
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Bullseye runtime is already active");
        }

        logger.status("Bullseye diagnostic system activated.");
        logger.status("Host telemetry online.");
        logger.status("Rolling metric window online. duration=" + format(windowDuration));
        publisher.publish(stateRepository.current());

        long intervalMillis = samplingInterval.toMillis();
        collectorExecutor.scheduleWithFixedDelay(
                this::collectSafely,
                0,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        logger.status("Shutdown sequence initiated.");
        collectorExecutor.shutdown();
        await(collectorExecutor);
        diagnosticExecutor.shutdown();
        await(diagnosticExecutor);
        publisher.close(SHUTDOWN_TIMEOUT);
        logger.status("Bullseye diagnostic system offline.");
    }

    private void collectSafely() {
        for (MetricCollector collector : collectors) {
            try {
                Collection<MetricSample> normalized = normalizer.normalize(collector.collect());
                normalized.forEach(metricWindow::append);
            } catch (Exception failure) {
                logger.interrupted(
                        "Host telemetry interrupted. collector=" + collector.name(),
                        failure
                );
            }
        }
        requestDiagnostics();
    }

    private void requestDiagnostics() {
        if (!diagnosticPending.compareAndSet(false, true)) {
            return;
        }
        try {
            diagnosticExecutor.execute(() -> {
                try {
                    diagnoseSafely();
                } finally {
                    diagnosticPending.set(false);
                }
            });
        } catch (RejectedExecutionException failure) {
            diagnosticPending.set(false);
            if (running.get()) {
                logger.interrupted("Diagnostic evaluation interrupted.", failure);
            }
        }
    }

    private void diagnoseSafely() {
        try {
            diagnosticCoordinator.evaluate(clock.millis()).ifPresent(transition -> {
                logger.transition(transition);
                publisher.publish(transition.current());
            });
        } catch (Exception failure) {
            logger.interrupted("Diagnostic evaluation interrupted.", failure);
        }
    }

    private static void await(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static String format(Duration duration) {
        if (duration.toMinutes() > 0 && duration.toSecondsPart() == 0) {
            return duration.toMinutes() + "m";
        }
        return duration.toSeconds() + "s";
    }
}
