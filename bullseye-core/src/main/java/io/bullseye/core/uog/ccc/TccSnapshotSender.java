package io.bullseye.core.uog.ccc;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.core.logging.BullseyeLogger;
import io.bullseye.core.state.publish.TccSnapshotV1Encoder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class TccSnapshotSender {
    private final String nodeId;
    private final UUID runId;
    private final BullseyeLogger logger;
    private final TccSnapshotV1Encoder encoder = new TccSnapshotV1Encoder();
    private final Object lock = new Object();

    private DiagnosticSnapshot latest;
    private Record inFlight;
    private long nextSequence = 1;
    private long lastCompletedSequence;

    TccSnapshotSender(String nodeId, UUID runId, BullseyeLogger logger) {
        this.nodeId = nodeId;
        this.runId = runId;
        this.logger = logger;
    }

    void offer(DiagnosticSnapshot snapshot) {
        synchronized (lock) {
            latest = snapshot;
            lock.notifyAll();
        }
    }

    Session open(Socket transport, OutputStream output, int timeoutMillis, int heartbeatMillis) {
        return new Session(transport, output, timeoutMillis, heartbeatMillis);
    }

    final class Session implements AutoCloseable {
        private static final int MAXIMUM_PENDING_PONGS = 16;

        private final Socket transport;
        private final OutputStream output;
        private final long timeoutNanos;
        private final long heartbeatNanos;
        private final ArrayDeque<Long> pongs = new ArrayDeque<>();
        private final Thread writer;
        private final Thread watchdog;

        private boolean closed;
        private boolean expired;
        private boolean awaitingAck;
        private boolean writing;
        private long lastPingAt;
        private long ackStartedAt;
        private long writeStartedAt;

        private Session(Socket transport, OutputStream output, int timeoutMillis, int heartbeatMillis) {
            this.transport = transport;
            this.output = output;
            timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            heartbeatNanos = TimeUnit.MILLISECONDS.toNanos(3L * heartbeatMillis);
            lastPingAt = System.nanoTime();
            writer = Thread.ofPlatform().daemon().name("bullseye-uog-writer").start(this::writeLoop);
            watchdog = Thread.ofVirtual().name("bullseye-uog-deadlines").start(this::watchDeadlines);
        }

        void pong(long sequence) throws IOException {
            synchronized (lock) {
                requireLive();
                if (pongs.size() >= MAXIMUM_PENDING_PONGS) {
                    throw new IOException("Too many pending cluster heartbeat replies");
                }
                lastPingAt = System.nanoTime();
                pongs.addLast(sequence);
                lock.notifyAll();
            }
        }

        void acknowledge(ObjectNode message) throws IOException {
            long sequence = CccProtocol.nonnegativeLong(message, "sequence");
            if (sequence == 0) {
                throw new IOException("Invalid telemetry acknowledgement sequence");
            }
            String status = CccProtocol.text(message, "status");
            synchronized (lock) {
                requireLive();
                if (sequence <= lastCompletedSequence) {
                    return;
                }
                if (!awaitingAck || inFlight == null || sequence != inFlight.sequence()) {
                    throw new IOException("Unexpected telemetry acknowledgement sequence");
                }
                switch (status) {
                    case "ACCEPTED", "DUPLICATE", "REJECTED" -> {
                        inFlight = null;
                        awaitingAck = false;
                        lastCompletedSequence = sequence;
                        lock.notifyAll();
                    }
                    case "RETRYABLE" -> throw new IOException("Telemetry retry requested");
                    default -> throw new IOException("Invalid telemetry acknowledgement status");
                }
            }
            if ("REJECTED".equals(status)) {
                logger.status("Telemetry snapshot rejected. sequence=" + sequence);
            }
        }

        private void requireLive() throws SocketTimeoutException {
            if (closed || expired || remainingNanos() <= 0) {
                throw new SocketTimeoutException("Cluster session deadline expired");
            }
        }

        private long remainingNanos() {
            long now = System.nanoTime();
            long remaining = heartbeatNanos - (now - lastPingAt);
            if (awaitingAck) {
                remaining = Math.min(remaining, timeoutNanos - (now - ackStartedAt));
            }
            return writing ? Math.min(remaining, timeoutNanos - (now - writeStartedAt)) : remaining;
        }

        private void watchDeadlines() {
            try {
                synchronized (lock) {
                    while (!closed) {
                        long remaining = remainingNanos();
                        if (remaining <= 0) {
                            expired = true;
                            lock.notifyAll();
                            break;
                        }
                        TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                    }
                    if (!expired) {
                        return;
                    }
                }
                // Socket read timeouts are idle limits. Close TCP to enforce a whole-message deadline.
                closeTransport();
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                pongs.clear();
                lock.notifyAll();
            }
            closeTransport();
            writer.interrupt();
            watchdog.interrupt();
        }

        private void writeLoop() {
            try {
                Outbound next;
                while ((next = awaitOutbound()) != null) {
                    write(next);
                }
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
            } catch (IOException failure) {
                closeTransport();
            }
        }

        private Outbound awaitOutbound() throws InterruptedException {
            synchronized (lock) {
                while (!closed && !expired) {
                    if (!pongs.isEmpty()) {
                        beginWrite();
                        return new Outbound(pongs.removeFirst(), null, null);
                    }
                    if (!awaitingAck && inFlight != null) {
                        beginWrite();
                        ackStartedAt = writeStartedAt;
                        awaitingAck = true;
                        return new Outbound(null, inFlight, null);
                    }
                    if (!awaitingAck && latest != null) {
                        return new Outbound(null, null, latest);
                    }
                    lock.wait();
                }
                return null;
            }
        }

        private void beginWrite() {
            writing = true;
            writeStartedAt = System.nanoTime();
            lock.notifyAll();
        }

        private void write(Outbound outbound) throws IOException {
            if (outbound.snapshot() != null) {
                prepare(outbound.snapshot());
                return;
            }
            try {
                if (outbound.pongSequence() != null) {
                    CccProtocol.writePong(output, outbound.pongSequence());
                } else {
                    CccProtocol.writeFrame(output, outbound.record().payload());
                }
            } finally {
                synchronized (lock) {
                    writing = false;
                    lock.notifyAll();
                }
            }
        }

        private void prepare(DiagnosticSnapshot snapshot) {
            long sequence;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                sequence = nextSequence++;
            }

            byte[] payload = null;
            String failureReason = "FRAME_SIZE";
            try {
                String json = encoder.encode(snapshot, nodeId, runId, sequence);
                payload = CccProtocol.encodeTccSnapshot(json);
            } catch (IOException | RuntimeException failure) {
                failureReason = "ENCODING";
            }
            synchronized (lock) {
                if (closed) {
                    return;
                }
                if (latest == snapshot) {
                    latest = null;
                }
                if (payload != null) {
                    inFlight = new Record(sequence, payload);
                }
            }
            if (payload == null) {
                logger.status("Telemetry snapshot dropped. reason=" + failureReason
                        + " sequence=" + sequence);
            }
        }

        private void closeTransport() {
            try {
                // Closing the TCP transport also unblocks a TLS writer waiting on the peer.
                transport.close();
            } catch (IOException failure) {
                logger.interrupted("Cluster transport close interrupted.", failure);
            }
        }
    }

    private record Record(long sequence, byte[] payload) {}

    private record Outbound(Long pongSequence, Record record, DiagnosticSnapshot snapshot) {}
}
