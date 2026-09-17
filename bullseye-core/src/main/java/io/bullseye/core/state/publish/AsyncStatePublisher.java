package io.bullseye.core.state.publish;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.core.logging.BullseyeLogger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class AsyncStatePublisher implements StatePublisher, AutoCloseable {

    private final StatePublisher delegate;
    private final BullseyeLogger logger;
    private final ExecutorService executor;

    public AsyncStatePublisher(StatePublisher delegate, BullseyeLogger logger) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.logger = Objects.requireNonNull(logger, "logger");
        ThreadFactory factory =
                runnable -> {
                    Thread thread = new Thread(runnable, "bullseye-publisher");
                    thread.setDaemon(false);
                    return thread;
                };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    @Override
    public void publish(DiagnosticSnapshot state) {
        try {
            executor.execute(() -> publishSafely(state));
        } catch (RejectedExecutionException failure) {
            logger.interrupted("State publication interrupted.", failure);
        }
    }

    public void close(Duration timeout) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(5));
    }

    private void publishSafely(DiagnosticSnapshot state) {
        try {
            delegate.publish(state);
        } catch (Exception failure) {
            logger.interrupted("State publication interrupted.", failure);
        }
    }
}
