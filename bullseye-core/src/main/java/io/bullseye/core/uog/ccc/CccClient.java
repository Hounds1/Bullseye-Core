package io.bullseye.core.uog.ccc;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.core.logging.BullseyeLogger;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class CccClient implements AutoCloseable {
    private static final int TIMEOUT_MILLIS = 5_000;
    private static final int INITIAL_RETRY_MILLIS = 1_000;
    private static final int MAXIMUM_RETRY_MILLIS = 30_000;
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final String host;
    private final int port;
    private final String nodeId;
    private final BullseyeLogger logger;
    private final SSLSocketFactory tlsFactory;
    private final int timeoutMillis;
    private final int initialRetryMillis;
    private final UUID runId = UUID.randomUUID();
    private final TccSnapshotSender telemetry;
    private final Thread worker = Thread.ofPlatform().daemon().name("bullseye-ccc").unstarted(this::run);

    private volatile boolean closed;
    private Socket connectingOrActiveSocket;
    private int retryMillis;
    private boolean interrupted;

    public CccClient(String host, int port, String nodeId, BullseyeLogger logger) {
        this(host, port, nodeId, logger, false);
    }

    public CccClient(String host, int port, String nodeId, BullseyeLogger logger, boolean tccEnabled) {
        this(host, port, nodeId, logger, null, TIMEOUT_MILLIS, INITIAL_RETRY_MILLIS, tccEnabled);
    }

    CccClient(String host, int port, String nodeId, BullseyeLogger logger,
            SSLSocketFactory tlsFactory, int timeoutMillis, int initialRetryMillis) {
        this(host, port, nodeId, logger, tlsFactory, timeoutMillis, initialRetryMillis, false);
    }

    CccClient(String host, int port, String nodeId, BullseyeLogger logger,
            SSLSocketFactory tlsFactory, int timeoutMillis, int initialRetryMillis, boolean tccEnabled) {
        this.host = host;
        this.port = port;
        this.nodeId = nodeId;
        this.logger = logger;
        this.tlsFactory = tlsFactory;
        this.timeoutMillis = timeoutMillis;
        this.initialRetryMillis = initialRetryMillis;
        this.retryMillis = initialRetryMillis;
        telemetry = tccEnabled ? new TccSnapshotSender(nodeId, runId, logger) : null;
    }

    public void offerSnapshot(DiagnosticSnapshot snapshot) {
        if (!closed && telemetry != null) {
            telemetry.offer(snapshot);
        }
    }

    public synchronized void start() {
        if (!closed && worker.getState() == Thread.State.NEW) {
            worker.start();
        }
    }

    @Override
    public void close() {
        Socket socket;
        synchronized (this) {
            closed = true;
            socket = connectingOrActiveSocket;
            worker.interrupt();
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException failure) {
                logger.interrupted("Cluster connection shutdown interrupted.", failure);
            }
        }
    }

    private void run() {
        while (!closed) {
            long retryAfterMillis = 0;
            try {
                retryAfterMillis = connectAndServe();
            } catch (IOException failure) {
                if (!closed && !interrupted) {
                    logger.status("Cluster connection interrupted. cause="
                            + failure.getClass().getSimpleName());
                    interrupted = true;
                }
            }
            if (closed || retryAfterMillis < 0) {
                return;
            }
            try {
                Thread.sleep(Math.max(retryAfterMillis, retryMillis));
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            }
            retryMillis = Math.min(retryMillis * 2, MAXIMUM_RETRY_MILLIS);
        }
    }

    private long connectAndServe() throws IOException {
        try (Socket connection = new Socket()) {
            track(connection);
            connection.connect(new InetSocketAddress(host, port), timeoutMillis);
            try (SSLSocket tls = openTls(connection)) {
                tls.startHandshake();
                if (closed) {
                    throw new SocketException("Cluster connection is closing");
                }
                return join(tls, connection);
            }
        } finally {
            synchronized (this) {
                connectingOrActiveSocket = null;
            }
        }
    }

    private synchronized void track(Socket connection) throws SocketException {
        if (closed) {
            throw new SocketException("Cluster connection is closing");
        }
        connectingOrActiveSocket = connection;
    }

    private SSLSocket openTls(Socket connection) throws IOException {
        SSLSocketFactory factory = tlsFactory == null
                ? (SSLSocketFactory) SSLSocketFactory.getDefault()
                : tlsFactory;
        SSLSocket tls = (SSLSocket) factory.createSocket(connection, host, port, true);
        var parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(parameters);
        tls.setSoTimeout(timeoutMillis);
        return tls;
    }

    private long join(SSLSocket tls, Socket transport) throws IOException {
        UUID requestId = UUID.randomUUID();
        CccProtocol.writeJoin(tls.getOutputStream(), requestId, nodeId, runId);
        ObjectNode response = readFrame(tls, timeoutMillis);
        if (!requestId.toString().equals(CccProtocol.text(response, "requestId"))) {
            throw new IOException("Mismatched join request identifier");
        }
        return switch (CccProtocol.type(response)) {
            case "JOIN_ACCEPTED" -> {
                CccProtocol.text(response, "sessionId");
                int heartbeatMillis = CccProtocol.positiveInt(response, "heartbeatIntervalMs");
                interrupted = false;
                retryMillis = initialRetryMillis;
                logger.status("Cluster connection authorized.");
                if (telemetry != null && CccProtocol.supportsTcc(response)) {
                    yield maintainTelemetrySession(tls, transport, heartbeatMillis);
                }
                yield maintainSession(tls, heartbeatMillis);
            }
            case "JOIN_REJECTED" -> endSession(response, "denied");
            default -> throw new IOException("Unexpected join response");
        };
    }

    private long maintainSession(SSLSocket tls, int heartbeatMillis) throws IOException {
        long deadlineMillis = 3L * heartbeatMillis;
        while (!closed) {
            ObjectNode message = readFrame(tls, deadlineMillis);
            switch (CccProtocol.type(message)) {
                case "PING" -> CccProtocol.writePong(
                        tls.getOutputStream(), CccProtocol.nonnegativeLong(message, "sequence"));
                case "SESSION_REVOKED" -> {
                    return endSession(message, "revoked");
                }
                default -> throw new IOException("Unexpected cluster session message");
            }
        }
        return -1;
    }

    private long maintainTelemetrySession(SSLSocket tls, Socket transport, int heartbeatMillis)
            throws IOException {
        tls.setSoTimeout(0);
        var input = tls.getInputStream();
        try (var session = telemetry.open(
                transport, tls.getOutputStream(), timeoutMillis, heartbeatMillis)) {
            while (!closed) {
                ObjectNode message = CccProtocol.read(input);
                switch (CccProtocol.type(message)) {
                    case "PING" -> session.pong(CccProtocol.nonnegativeLong(message, "sequence"));
                    case "TCC_ACK" -> session.acknowledge(message);
                    case "SESSION_REVOKED" -> {
                        session.close();
                        return endSession(message, "revoked");
                    }
                    default -> throw new IOException("Unexpected cluster session message");
                }
            }
        }
        return -1;
    }

    private long endSession(ObjectNode message, String outcome) throws IOException {
        String reason = CccProtocol.text(message, "reasonCode");
        if (!REASON_CODE.matcher(reason).matches()) {
            throw new IOException("Invalid cluster decision reason code");
        }
        long retryAfterMillis = CccProtocol.retryAfterMillis(message);
        logger.status("Cluster connection " + outcome + ". reason=" + reason);
        return retryAfterMillis;
    }

    private static ObjectNode readFrame(Socket socket, long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        return CccProtocol.read(new FilterInputStream(socket.getInputStream()) {
            @Override
            public int read() throws IOException {
                applyDeadline();
                return in.read();
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                applyDeadline();
                return in.read(bytes, offset, length);
            }

            private void applyDeadline() throws IOException {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new SocketTimeoutException("Cluster frame deadline expired");
                }
                long remainingMillis = 1 + TimeUnit.NANOSECONDS.toMillis(remaining - 1);
                socket.setSoTimeout((int) Math.min(remainingMillis, Integer.MAX_VALUE));
            }
        });
    }
}
