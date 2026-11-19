package io.bullseye.core.runtime;

import io.bullseye.common.metric.MetricSample;
import io.bullseye.core.diagnostic.DiagnosticCoordinator;
import io.bullseye.core.linux.cgroup.CgroupMonitor;
import io.bullseye.core.logging.BullseyeLogger;
import io.bullseye.core.metric.MetricCollector;
import io.bullseye.core.metric.MetricNormalizer;
import io.bullseye.core.metric.MetricView;
import io.bullseye.core.metric.MetricWindow;
import io.bullseye.core.state.DiagnosticStateRepository;
import io.bullseye.core.state.publish.AsyncStatePublisher;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BullseyeRuntime implements AutoCloseable {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final String METRIC_WINDOW_COMPONENT = "metric-window";

    private final List<MetricCollector> collectors;
    private final MetricNormalizer normalizer;
    private final MetricWindow metricWindow;
    private final DiagnosticCoordinator diagnosticCoordinator;
    private final DiagnosticStateRepository stateRepository;
    private final AsyncStatePublisher publisher;
    private final BullseyeLogger logger;
    private final Clock timelineClock;
    private final Clock wallClock;
    private final Duration samplingInterval;
    private final Duration windowDuration;
    private final CgroupMonitor cgroupMonitor;
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
            Duration windowDuration) {
        this(
                collectors,
                normalizer,
                metricWindow,
                diagnosticCoordinator,
                stateRepository,
                publisher,
                logger,
                clock,
                samplingInterval,
                windowDuration,
                null);
    }

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
            Duration windowDuration,
            CgroupMonitor cgroupMonitor) {
        this(
                collectors,
                normalizer,
                metricWindow,
                diagnosticCoordinator,
                stateRepository,
                publisher,
                logger,
                clock,
                clock,
                samplingInterval,
                windowDuration,
                cgroupMonitor);
    }

    public BullseyeRuntime(
            List<MetricCollector> collectors,
            MetricNormalizer normalizer,
            MetricWindow metricWindow,
            DiagnosticCoordinator diagnosticCoordinator,
            DiagnosticStateRepository stateRepository,
            AsyncStatePublisher publisher,
            BullseyeLogger logger,
            Clock timelineClock,
            Clock wallClock,
            Duration samplingInterval,
            Duration windowDuration,
            CgroupMonitor cgroupMonitor) {
        this.collectors = List.copyOf(Objects.requireNonNull(collectors, "collectors"));
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.metricWindow = Objects.requireNonNull(metricWindow, "metricWindow");
        this.diagnosticCoordinator =
                Objects.requireNonNull(diagnosticCoordinator, "diagnosticCoordinator");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.timelineClock = Objects.requireNonNull(timelineClock, "timelineClock");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.samplingInterval = Objects.requireNonNull(samplingInterval, "samplingInterval");
        this.windowDuration = Objects.requireNonNull(windowDuration, "windowDuration");
        this.cgroupMonitor = cgroupMonitor;
        this.collectorExecutor =
                Executors.newSingleThreadScheduledExecutor(
                        new NamedThreadFactory("bullseye-collector"));
        this.diagnosticExecutor =
                Executors.newSingleThreadExecutor(new NamedThreadFactory("bullseye-diagnostic"));
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
                this::collectSafely, 0, intervalMillis, TimeUnit.MILLISECONDS);
        if (cgroupMonitor != null) {
            cgroupMonitor.initialize();
        }
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
        List<MetricSample> batch = new ArrayList<>();
        for (MetricCollector collector : collectors) {
            String component = "collector-" + collector.name();
            try {
                List<MetricSample> normalized = List.copyOf(normalizer.normalize(collector.collect()));
                batch.addAll(normalized);
                logger.componentRestored(component);
            } catch (Exception failure) {
                logger.componentInterrupted(component, failure);
            }
        }
        if (!batch.isEmpty()) {
            try {
                metricWindow.appendAll(batch);
                logger.componentRestored(METRIC_WINDOW_COMPONENT);
            } catch (RuntimeException failure) {
                logger.componentInterrupted(METRIC_WINDOW_COMPONENT, failure);
            }
        }
        requestDiagnostics();
    }

    private void requestDiagnostics() {
        if (!diagnosticPending.compareAndSet(false, true)) {
            return;
        }
        try {
            // Capture the committed inputs before this thread starts the next collection round.
            long evaluatedAt = timelineClock.millis();
            long wallClockAt = wallClock == timelineClock ? evaluatedAt : wallClock.millis();
            MetricView inputs = metricWindow.snapshot(evaluatedAt);
            diagnosticExecutor.execute(
                    () -> {
                        try {
                            diagnoseSafely(evaluatedAt, wallClockAt, inputs);
                        } finally {
                            diagnosticPending.set(false);
                        }
                    });
        } catch (RuntimeException failure) {
            diagnosticPending.set(false);
            if (running.get()) {
                logger.interrupted("Diagnostic evaluation interrupted.", failure);
            }
        }
    }

    private void diagnoseSafely(long evaluatedAt, long wallClockAt, MetricView inputs) {
        try {
            diagnosticCoordinator
                    .evaluate(evaluatedAt, wallClockAt, inputs)
                    .ifPresent(logger::transition);
            publisher.publish(stateRepository.current());
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
