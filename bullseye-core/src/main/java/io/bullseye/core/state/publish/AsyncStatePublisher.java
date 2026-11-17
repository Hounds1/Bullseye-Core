package io.bullseye.core.state.publish;

import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.core.logging.BullseyeLogger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class AsyncStatePublisher implements StatePublisher, AutoCloseable {

    private static final String COMPONENT = "state-publisher";
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(1);

    private final StatePublisher delegate;
    private final BullseyeLogger logger;
    private final long retryIntervalNanos;
    private final Object lock = new Object();
    private final Thread worker;
    private DiagnosticSnapshot pending;
    private boolean retryPending;
    private long retryStartedAt;
    private boolean closing;
    private boolean stopped;
    private long closeStartedAt;
    private long closeTimeoutNanos;

    public AsyncStatePublisher(StatePublisher delegate, BullseyeLogger logger) {
        this(delegate, logger, RETRY_INTERVAL);
    }

    AsyncStatePublisher(
            StatePublisher delegate, BullseyeLogger logger, Duration retryInterval) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.logger = Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(retryInterval, "retryInterval");
        if (retryInterval.isZero() || retryInterval.isNegative()) {
            throw new IllegalArgumentException("retryInterval must be positive");
        }
        this.retryIntervalNanos = retryInterval.toNanos();
        this.worker = new Thread(this::run, "bullseye-publisher");
        // A filesystem call ignoring interruption must not prevent process shutdown.
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void publish(DiagnosticSnapshot state) {
        Objects.requireNonNull(state, "state");
        synchronized (lock) {
            if (!closing && !stopped) {
                pending = state;
                lock.notifyAll();
                return;
            }
        }
        logger.componentInterrupted(
                COMPONENT, new RejectedExecutionException("State publisher is closed"));
    }

    public void close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long timeoutNanos = timeout.toNanos();
        synchronized (lock) {
            if (!closing) {
                closing = true;
                closeStartedAt = System.nanoTime();
                closeTimeoutNanos = timeoutNanos;
                retryPending = false;
                lock.notifyAll();
            }
        }

        try {
            if (timeoutNanos > 0) {
                TimeUnit.NANOSECONDS.timedJoin(worker, timeoutNanos);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } finally {
            if (worker.isAlive()) {
                synchronized (lock) {
                    stopped = true;
                    pending = null;
                    lock.notifyAll();
                }
                worker.interrupt();
            }
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(5));
    }

    private void run() {
        try {
            DiagnosticSnapshot state;
            while ((state = awaitPending()) != null) {
                publishSafely(state);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (lock) {
                stopped = true;
                pending = null;
                lock.notifyAll();
            }
        }
    }

    private DiagnosticSnapshot awaitPending() throws InterruptedException {
        synchronized (lock) {
            while (!stopped) {
                long shutdownRemaining =
                        closing
                                ? closeTimeoutNanos - (System.nanoTime() - closeStartedAt)
                                : Long.MAX_VALUE;
                if (closing && (pending == null || shutdownRemaining <= 0)) {
                    return null;
                }
                if (pending == null) {
                    lock.wait();
                    continue;
                }
                long retryRemaining =
                        retryPending
                                ? retryIntervalNanos - (System.nanoTime() - retryStartedAt)
                                : 0;
                if (retryRemaining > 0) {
                    TimeUnit.NANOSECONDS.timedWait(
                            lock, Math.min(retryRemaining, shutdownRemaining));
                    continue;
                }
                DiagnosticSnapshot state = pending;
                pending = null;
                retryPending = false;
                return state;
            }
            return null;
        }
    }

    private void publishSafely(DiagnosticSnapshot state) {
        try {
            delegate.publish(state);
            logger.componentRestored(COMPONENT);
        } catch (Exception failure) {
            synchronized (lock) {
                // Do not let a failed, older write replace a newer pending snapshot.
                if (!stopped && pending == null) {
                    pending = state;
                }
                retryPending = true;
                retryStartedAt = System.nanoTime();
            }
            logger.componentInterrupted(COMPONENT, failure);
        }
    }
}
