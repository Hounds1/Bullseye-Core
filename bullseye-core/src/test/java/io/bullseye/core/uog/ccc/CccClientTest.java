package io.bullseye.core.uog.ccc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.bullseye.common.diagnostic.DiagnosticSnapshot;
import io.bullseye.core.logging.BullseyeLogger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

@Timeout(20)
class CccClientTest {
    private static final String NODE_ID = "edge-01";
    private static final int PEER_TIMEOUT_MS = 5_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BullseyeLogger LOGGER = new BullseyeLogger(
            new PrintStream(OutputStream.nullOutputStream()),
            new PrintStream(OutputStream.nullOutputStream()));

    private static SSLContext serverTls;
    private static SSLContext clientTls;

    @BeforeAll
    static void loadLoopbackTls() throws Exception {
        char[] password = "fixture-only".toCharArray();
        KeyStore serverKeys = KeyStore.getInstance("PKCS12");
        try (var input = CccClientTest.class.getResourceAsStream("/ccc/localhost-test-only.p12")) {
            serverKeys.load(Objects.requireNonNull(input), password);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(serverKeys, password);
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keys.getKeyManagers(), null, null);

        KeyStore clientTrust = KeyStore.getInstance("PKCS12");
        clientTrust.load(null, null);
        clientTrust.setCertificateEntry(
                "localhost", serverKeys.getCertificate("loopback-ccc-test"));
        TrustManagerFactory trust =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(clientTrust);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trust.getTrustManagers(), null);
    }

    @Test
    void handlesFragmentedApprovalHeartbeatAndRevocation() throws Exception {
        try (SSLServerSocket server = server();
                CccClient client = client(server, "localhost", 2_000)) {
            client.start();
            try (SSLSocket socket = accept(server)) {
                JsonNode join = readJoin(socket);
                byte[] grant = frame(accepted(join));
                OutputStream output = socket.getOutputStream();
                output.write(grant, 0, 2);
                output.flush();
                output.write(grant, 2, 8);
                output.flush();
                output.write(grant, 10, grant.length - 10);
                output.write(frame(message("PING").put("sequence", 42)));
                output.flush();

                JsonNode pong = read(socket);
                assertEquals("PONG", pong.path("type").textValue());
                assertEquals(42L, pong.path("sequence").longValue());

                send(socket, message("SESSION_REVOKED").put("reasonCode", "OPERATOR_REVOKED"));
                assertPeerClosed(socket);
                assertNoReconnect(server);
            }
        }
    }

    @Test
    void stopsConnectingAfterRejectionWithoutRetryPermission() throws Exception {
        try (SSLServerSocket server = server();
                CccClient client = client(server, "localhost", 2_000)) {
            client.start();
            try (SSLSocket socket = accept(server)) {
                JsonNode join = readJoin(socket);
                send(socket, message("JOIN_REJECTED")
                        .put("requestId", join.path("requestId").textValue())
                        .put("reasonCode", "NODE_NOT_REGISTERED"));
                assertPeerClosed(socket);
                assertNoReconnect(server);
            }
        }
    }

    @Test
    void reconnectsWithSameRunIdentityAndNewJoinRequestAfterDisconnect() throws Exception {
        try (SSLServerSocket server = server();
                CccClient client = client(server, "localhost", 2_000)) {
            client.start();
            JsonNode firstJoin;
            try (SSLSocket first = accept(server)) {
                firstJoin = readJoin(first);
                send(first, accepted(firstJoin));
            }

            assertRejoins(server, firstJoin);
        }
    }

    @Test
    void retriesWhenJoinReplyTimesOut() throws Exception {
        try (SSLServerSocket server = server();
                CccClient client = client(server, "localhost", 1_000)) {
            client.start();
            try (SSLSocket first = accept(server)) {
                JsonNode firstJoin = readJoin(first);
                assertPeerClosed(first);
                assertRejoins(server, firstJoin);
            }
        }
    }

    @Test
    void reconnectsWhenAnAuthorizedSessionStopsReceivingHeartbeats() throws Exception {
        try (SSLServerSocket server = server();
                CccClient client = client(server, "localhost", 2_000)) {
            client.start();
            try (SSLSocket first = accept(server)) {
                JsonNode firstJoin = readJoin(first);
                send(first, accepted(firstJoin).put("heartbeatIntervalMs", 200));
                assertPeerClosed(first);
                assertRejoins(server, firstJoin);
            }
        }
    }

    @Test
    void closingClientClosesPendingJoinAndPreventsReconnect() throws Exception {
        try (SSLServerSocket server = server();
                CccClient client = client(server, "localhost", PEER_TIMEOUT_MS)) {
            client.start();
            try (SSLSocket socket = accept(server)) {
                readJoin(socket);
                client.close();
                assertPeerClosed(socket);
                assertNoReconnect(server);
            }
        }
    }

    @Test
    void verifiesServerHostnameBeforeSendingJoin() throws Exception {
        var clientFailure = new CompletableFuture<String>();
        var report = new PrintStream(OutputStream.nullOutputStream()) {
            @Override
            public void println(String line) {
                if (line.contains("Cluster connection interrupted.")) {
                    clientFailure.complete(line);
                }
            }
        };
        try (SSLServerSocket server = server();
                report;
                CccClient client = new CccClient(
                        server.getInetAddress().getHostAddress(), server.getLocalPort(), NODE_ID,
                        new BullseyeLogger(report, report), clientTls.getSocketFactory(), 2_000, 30)) {
            client.start();
            try (SSLSocket socket = accept(server)) {
                IOException failure = assertThrows(IOException.class, socket::startHandshake);
                assertTrue(failure instanceof SSLException || failure instanceof SocketException);
                assertTrue(clientFailure.get(5, TimeUnit.SECONDS).contains("cause=SSLHandshakeException"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void requiresBothLocalOptInAndAnAdvertisedCapability(boolean tccEnabled) throws Exception {
        try (SSLServerSocket server = server();
                CccClient client = client(server, "localhost", 2_000, tccEnabled)) {
            client.start();
            try (SSLSocket socket = accept(server)) {
                JsonNode join = readJoin(socket);
                client.offerSnapshot(snapshot(1_000));
                assertNoFrame(socket);

                ObjectNode grant = tccEnabled ? accepted(join) : acceptedWithTcc(join);
                send(socket, grant);
                client.offerSnapshot(snapshot(2_000));
                assertPong(socket, 19);
                assertNoFrame(socket);
            }
        }
    }

    @Test
    void answersHeartbeatsWhileWaitingForAckAndThenSendsOnlyTheNewestPendingSnapshot()
            throws Exception {
        try (SSLServerSocket server = server();
                CccClient client = client(server, "localhost", 2_000, true)) {
            client.start();
            try (SSLSocket socket = accept(server)) {
                JsonNode join = readJoin(socket);
                client.offerSnapshot(snapshot(1_000));
                assertNoFrame(socket);
                send(socket, acceptedWithTcc(join));
                assertSnapshot(read(socket), join, 1, 1_000);

                client.offerSnapshot(snapshot(2_000));
                client.offerSnapshot(snapshot(3_000));
                assertPong(socket, 42);
                send(socket, acknowledgement(1, "ACCEPTED"));
                assertSnapshot(read(socket), join, 2, 3_000);

                send(socket, acknowledgement(2, "REJECTED"));
                client.offerSnapshot(snapshot(4_000));
                assertSnapshot(read(socket), join, 3, 4_000);
                send(socket, acknowledgement(3, "ACCEPTED"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reconnectsAndResendsExactSnapshotAfterRetryableOrMissingAck(boolean retryable)
            throws Exception {
        try (SSLServerSocket server = server();
                CccClient client = client(server, "localhost", 1_500, true)) {
            client.start();
            JsonNode firstJoin;
            byte[] beforeRetry;
            try (SSLSocket first = accept(server)) {
                firstJoin = readJoin(first);
                send(first, acceptedWithTcc(firstJoin));
                client.offerSnapshot(snapshot(1_000));
                beforeRetry = readFramePayload(first);
                assertSnapshot(JSON.readTree(beforeRetry), firstJoin, 1, 1_000);
                client.offerSnapshot(snapshot(2_000));
                if (retryable) {
                    send(first, acknowledgement(1, "RETRYABLE"));
                }
                assertPeerClosed(first);
            }

            try (SSLSocket second = accept(server)) {
                JsonNode secondJoin = readJoin(second);
                assertEquals(firstJoin.path("runId"), secondJoin.path("runId"));
                assertNotEquals(firstJoin.path("requestId"), secondJoin.path("requestId"));
                assertNoFrame(second);
                send(second, acceptedWithTcc(secondJoin).put("sessionId", "session-02"));
                assertArrayEquals(beforeRetry, readFramePayload(second));

                send(second, acknowledgement(1, "DUPLICATE"));
                assertSnapshot(read(second), secondJoin, 2, 2_000);
                send(second, acknowledgement(2, "ACCEPTED"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "DUPLICATE"})
    void preservesBackoffAcrossFailedTelemetrySessionsUntilMatchingPositiveAck(String success)
            throws Exception {
        int initialRetryMillis = 200;
        var logs = new ByteArrayOutputStream();
        try (SSLServerSocket server = server();
                var report = new PrintStream(logs, true, StandardCharsets.UTF_8);
                CccClient client = new CccClient(
                        "localhost", server.getLocalPort(), NODE_ID, new BullseyeLogger(report, report),
                        clientTls.getSocketFactory(), 2_000, initialRetryMillis, true)) {
            client.offerSnapshot(snapshot(1_000));
            client.start();

            byte[] original;
            long firstFailureAt;
            try (SSLSocket first = accept(server)) {
                JsonNode join = readJoin(first);
                send(first, acceptedWithTcc(join));
                original = readFramePayload(first);
                assertSnapshot(JSON.readTree(original), join, 1, 1_000);
                firstFailureAt = retry(first, acknowledgement(1, "RETRYABLE")
                        .put("reasonCode", "PERSISTENCE_UNAVAILABLE"));
            }

            long secondFailureAt;
            try (SSLSocket second = accept(server)) {
                assertElapsedMillis(firstFailureAt, initialRetryMillis);
                send(second, acceptedWithTcc(readJoin(second)));
                assertArrayEquals(original, readFramePayload(second));
                secondFailureAt = retry(second, acknowledgement(1, "RETRYABLE")
                        .put("reasonCode", "UNSAFE\nTEXT"));
            }

            long thirdFailureAt;
            byte[] next;
            try (SSLSocket third = accept(server)) {
                assertElapsedMillis(secondFailureAt, 2 * initialRetryMillis);
                JsonNode join = readJoin(third);
                send(third, acceptedWithTcc(join));
                assertArrayEquals(original, readFramePayload(third));

                client.offerSnapshot(snapshot(2_000));
                send(third, acknowledgement(1, "REJECTED").put("reasonCode", "INVALID_TELEMETRY"));
                next = readFramePayload(third);
                assertSnapshot(JSON.readTree(next), join, 2, 2_000);
                send(third, acknowledgement(1, "ACCEPTED"));
                send(third, acknowledgement(1, "RETRYABLE").put("reasonCode", "STALE_REASON"));
                assertPong(third, 42);
                thirdFailureAt = retry(third, acknowledgement(2, "RETRYABLE"));
            }

            long fourthFailureAt;
            try (SSLSocket fourth = accept(server)) {
                assertElapsedMillis(thirdFailureAt, 4 * initialRetryMillis);
                JsonNode join = readJoin(fourth);
                send(fourth, acceptedWithTcc(join));
                assertArrayEquals(next, readFramePayload(fourth));

                client.offerSnapshot(snapshot(3_000));
                send(fourth, acknowledgement(2, success));
                assertSnapshot(read(fourth), join, 3, 3_000);
                fourthFailureAt = retry(fourth, acknowledgement(3, "RETRYABLE"));
            }

            server.setSoTimeout(5 * initialRetryMillis);
            try (SSLSocket afterSuccess = accept(server)) {
                assertElapsedMillis(fourthFailureAt, initialRetryMillis);
                readJoin(afterSuccess);
            }
        }

        String output = logs.toString(StandardCharsets.UTF_8);
        var lines = output.lines().toList();
        assertTrue(lines.contains("[BULLSEYE] Telemetry snapshot retry requested. sequence=1"
                + " reason=PERSISTENCE_UNAVAILABLE"));
        assertTrue(lines.contains("[BULLSEYE] Telemetry snapshot retry requested. sequence=1"));
        assertTrue(lines.contains("[BULLSEYE] Telemetry snapshot rejected. sequence=1"
                + " reason=INVALID_TELEMETRY"));
        assertFalse(output.contains("UNSAFE"));
        assertFalse(output.contains("STALE_REASON"));
    }

    @Test
    void omitsTheReasonForAnUnmatchedAcknowledgement() throws Exception {
        var logs = new ByteArrayOutputStream();
        try (SSLServerSocket server = server();
                var report = new PrintStream(logs, true, StandardCharsets.UTF_8);
                CccClient client = new CccClient(
                        "localhost", server.getLocalPort(), NODE_ID, new BullseyeLogger(report, report),
                        clientTls.getSocketFactory(), 2_000, 200, true)) {
            client.offerSnapshot(snapshot(1_000));
            client.start();
            try (SSLSocket socket = accept(server)) {
                JsonNode join = readJoin(socket);
                send(socket, acceptedWithTcc(join));
                assertSnapshot(read(socket), join, 1, 1_000);
                send(socket, acknowledgement(2, "RETRYABLE").put("reasonCode", "UNEXPECTED_REASON"));
                assertPeerClosed(socket);
            }
        }

        String output = logs.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("UNEXPECTED_REASON"));
        assertFalse(output.contains("Telemetry snapshot retry requested."));
    }

    private static long retry(SSLSocket socket, ObjectNode acknowledgement) throws IOException {
        long sentAt = System.nanoTime();
        send(socket, acknowledgement);
        assertPeerClosed(socket);
        return sentAt;
    }

    private static void assertElapsedMillis(long startedAt, int minimumMillis) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue(elapsed >= minimumMillis,
                () -> "Reconnected after " + elapsed + " ms, expected at least " + minimumMillis + " ms");
    }

    @Test
    void revocationClosesTelemetrySessionWhileAnAckIsOutstanding() throws Exception {
        try (SSLServerSocket server = server();
                CccClient client = client(server, "localhost", 2_000, true)) {
            client.start();
            try (SSLSocket socket = accept(server)) {
                JsonNode join = readJoin(socket);
                send(socket, acceptedWithTcc(join));
                assertPong(socket, 17);
                client.offerSnapshot(snapshot(1_000));
                assertSnapshot(read(socket), join, 1, 1_000);
                client.offerSnapshot(snapshot(2_000));

                send(socket, message("SESSION_REVOKED").put("reasonCode", "OPERATOR_REVOKED"));
                assertPeerClosed(socket);
                assertNoReconnect(server);
            }
        }
    }

    private static SSLServerSocket server() throws IOException {
        SSLServerSocket server = (SSLServerSocket) serverTls.getServerSocketFactory()
                .createServerSocket(0, 4, InetAddress.getByName("localhost"));
        server.setSoTimeout(PEER_TIMEOUT_MS);
        return server;
    }

    private static CccClient client(SSLServerSocket server, String host, int timeoutMillis) {
        return new CccClient(
                host, server.getLocalPort(), NODE_ID, LOGGER, clientTls.getSocketFactory(),
                timeoutMillis, 25);
    }

    private static CccClient client(
            SSLServerSocket server, String host, int timeoutMillis, boolean tccEnabled) {
        return new CccClient(
                host, server.getLocalPort(), NODE_ID, LOGGER, clientTls.getSocketFactory(),
                timeoutMillis, 25, tccEnabled);
    }

    private static SSLSocket accept(SSLServerSocket server) throws IOException {
        SSLSocket socket = (SSLSocket) server.accept();
        socket.setSoTimeout(PEER_TIMEOUT_MS);
        return socket;
    }

    private static void assertPeerClosed(SSLSocket socket) throws IOException {
        try {
            assertEquals(-1, socket.getInputStream().read());
        } catch (SocketException closed) {
            // Closing the underlying TCP connection can terminate TLS with a reset.
        }
    }

    private static void assertNoReconnect(SSLServerSocket server) throws IOException {
        server.setSoTimeout(500);
        assertThrows(SocketTimeoutException.class, () -> {
            try (SSLSocket ignored = (SSLSocket) server.accept()) {
                // Close unexpected connections before asserting the timeout.
            }
        });
    }

    private static void assertNoFrame(SSLSocket socket) throws IOException {
        socket.setSoTimeout(250);
        try {
            assertThrows(SocketTimeoutException.class, () -> read(socket));
        } finally {
            socket.setSoTimeout(PEER_TIMEOUT_MS);
        }
    }

    private static void assertPong(SSLSocket socket, long sequence) throws IOException {
        send(socket, message("PING").put("sequence", sequence));
        JsonNode pong = read(socket);
        assertEquals("PONG", pong.path("type").textValue());
        assertEquals(sequence, pong.path("sequence").longValue());
    }

    private static void assertSnapshot(JsonNode frame, JsonNode join, long sequence, long evaluatedAt) {
        assertEquals(1, frame.path("protocolVersion").intValue());
        assertEquals("TCC_SNAPSHOT", frame.path("type").textValue());
        JsonNode telemetry = frame.path("telemetry");
        assertEquals(1, telemetry.path("schemaVersion").intValue());
        assertEquals(join.path("nodeId"), telemetry.path("nodeId"));
        assertEquals(join.path("runId"), telemetry.path("runId"));
        assertEquals(sequence, telemetry.path("sequence").longValue());
        assertEquals(evaluatedAt, telemetry.path("snapshot").path("evaluatedAt").longValue());
    }

    private static DiagnosticSnapshot snapshot(long evaluatedAt) {
        return DiagnosticSnapshot.initial(NODE_ID, "core", evaluatedAt);
    }

    private static void assertRejoins(SSLServerSocket server, JsonNode previous) throws IOException {
        try (SSLSocket socket = accept(server)) {
            JsonNode next = readJoin(socket);
            assertEquals(previous.path("runId"), next.path("runId"));
            assertNotEquals(previous.path("requestId"), next.path("requestId"));
        }
    }

    private static JsonNode readJoin(SSLSocket socket) throws IOException {
        JsonNode join = read(socket);
        assertEquals("JOIN_REQUEST", join.path("type").textValue());
        assertEquals(NODE_ID, join.path("nodeId").textValue());
        assertEquals(5, join.size());
        String requestId = join.path("requestId").textValue();
        assertEquals(requestId, UUID.fromString(requestId).toString());
        String runId = join.path("runId").textValue();
        assertEquals(runId, UUID.fromString(runId).toString());
        return join;
    }

    private static JsonNode read(SSLSocket socket) throws IOException {
        JsonNode result = JSON.readTree(readFramePayload(socket));
        assertEquals(1, result.path("protocolVersion").intValue());
        return result;
    }

    private static byte[] readFramePayload(SSLSocket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int length = input.readInt();
        assertTrue(length > 0 && length <= 65_536);
        byte[] payload = new byte[length];
        input.readFully(payload);
        return payload;
    }

    private static ObjectNode accepted(JsonNode join) {
        return message("JOIN_ACCEPTED")
                .put("requestId", join.path("requestId").textValue())
                .put("sessionId", "session-01")
                .put("heartbeatIntervalMs", 2_000);
    }

    private static ObjectNode acceptedWithTcc(JsonNode join) {
        ObjectNode grant = accepted(join);
        grant.putArray("capabilities").add("tcc.snapshot.v1");
        return grant;
    }

    private static ObjectNode acknowledgement(long sequence, String status) {
        return message("TCC_ACK").put("sequence", sequence).put("status", status);
    }

    private static ObjectNode message(String type) {
        return JSON.createObjectNode().put("protocolVersion", 1).put("type", type);
    }

    private static void send(SSLSocket socket, ObjectNode message) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(frame(message));
        output.flush();
    }

    private static byte[] frame(ObjectNode message) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(message);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Integer.BYTES + payload.length);
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(payload.length);
        output.write(payload);
        return bytes.toByteArray();
    }
}
