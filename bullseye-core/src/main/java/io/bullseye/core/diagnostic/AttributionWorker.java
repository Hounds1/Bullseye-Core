package io.bullseye.core.diagnostic;

import io.bullseye.common.diagnostic.ResourceType;
import io.bullseye.common.workload.WorkloadAttribution;
import io.bullseye.core.logging.BullseyeLogger;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class AttributionWorker implements AutoCloseable {
    private final Clock clock;
    private final BullseyeLogger logger;
    private final Object lock = new Object();
    private final EnumMap<ResourceType, Slot> slots = new EnumMap<>(ResourceType.class);
    private final ArrayDeque<ResourceType> ready = new ArrayDeque<>();
    private Thread worker;
    private volatile boolean closed;

    public AttributionWorker(Clock clock, BullseyeLogger logger) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void start(Runnable initialize) {
        Objects.requireNonNull(initialize, "initialize");
        synchronized (lock) {
            if (closed || worker != null) {
                return;
            }
            worker = Thread.ofPlatform()
                    .daemon()
                    .name("bullseye-attribution")
                    .start(() -> run(initialize));
        }
    }

    boolean request(
            ResourceType resource,
            Runnable reset,
            Supplier<WorkloadAttribution> collection,
            BooleanSupplier needsConfirmation,
            boolean queueWhileBusy) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(reset, "reset");
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(needsConfirmation, "needsConfirmation");
        synchronized (lock) {
            if (closed) {
                return false;
            }
            Slot slot = slots.computeIfAbsent(resource, ignored -> new Slot());
            if (!queueWhileBusy && (slot.running || slot.pending != null || slot.result != null)) {
                return false;
            }
            if (slot.pending == null) {
                ready.addLast(resource);
            }
            slot.pending =
                    new Request(resource, slot, slot.generation, reset, collection, needsConfirmation);
            lock.notifyAll();
            return true;
        }
    }

    void invalidate(ResourceType resource) {
        Objects.requireNonNull(resource, "resource");
        synchronized (lock) {
            if (closed) {
                return;
            }
            Slot slot = slots.computeIfAbsent(resource, ignored -> new Slot());
            slot.generation++;
            if (slot.pending != null) {
                ready.remove(resource);
                slot.pending = null;
            }
            slot.result = null;
        }
    }

    Result take(ResourceType resource, long evaluatedAt) {
        synchronized (lock) {
            Slot slot = slots.get(resource);
            if (slot == null || slot.result == null || slot.result.observedAt() > evaluatedAt) {
                return null;
            }
            Result result = slot.result;
            slot.result = null;
            return result;
        }
    }

    @Override
    public void close() {
        Thread activeWorker;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            for (Slot slot : slots.values()) {
                slot.pending = null;
                slot.result = null;
            }
            slots.clear();
            ready.clear();
            activeWorker = worker;
            lock.notifyAll();
        }
        if (activeWorker != null) {
            activeWorker.interrupt();
        }
    }

    private void run(Runnable initialize) {
        if (closed) {
            return;
        }
        try {
            initialize.run();
        } catch (RuntimeException failure) {
            if (!closed) {
                logger.componentInterrupted("cgroup-attribution-initialization", failure);
            }
        }
        try {
            Request request;
            while ((request = awaitRequest()) != null) {
                execute(request);
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
    }

    private Request awaitRequest() throws InterruptedException {
        synchronized (lock) {
            while (ready.isEmpty() && !closed) {
                lock.wait();
            }
            if (closed) {
                return null;
            }
            Slot slot = slots.get(ready.removeFirst());
            Request request = slot.pending;
            slot.pending = null;
            slot.running = true;
            return request;
        }
    }

    private void execute(Request request) {
        Slot slot = request.slot();
        String component = "cgroup-attribution-" + request.resource();
        Result result = null;
        try {
            if (request.generation() != slot.processedGeneration || slot.resetAfterFailure) {
                request.reset().run();
            }
            long startedAt = clock.millis();
            WorkloadAttribution attribution = request.collection().get();
            Long collectedAt = attribution.collectedAt();
            result = new Result(
                    attribution,
                    request.needsConfirmation().getAsBoolean(),
                    collectedAt == null ? startedAt : collectedAt);
            if (!closed) {
                logger.componentRestored(component);
            }
        } catch (RuntimeException failure) {
            if (!closed) {
                logger.componentInterrupted(component, failure);
            }
        } finally {
            slot.processedGeneration = request.generation();
            slot.resetAfterFailure = result == null;
            synchronized (lock) {
                slot.running = false;
                if (!closed && slot.generation == request.generation() && result != null) {
                    slot.result = result;
                }
            }
        }
    }

    record Result(WorkloadAttribution attribution, boolean needsConfirmation, long observedAt) {}

    private record Request(
            ResourceType resource,
            Slot slot,
            long generation,
            Runnable reset,
            Supplier<WorkloadAttribution> collection,
            BooleanSupplier needsConfirmation) {}

    private static final class Slot {
        private long generation;
        private Request pending;
        private boolean running;
        private Result result;

        // The worker alone owns the history associated with these fields.
        private long processedGeneration;
        private boolean resetAfterFailure;
    }
}
