package io.bullseye.core.uog.ccc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.bullseye.core.logging.BullseyeLogger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.KeyStore;
import java.util.Objects;
import java.util.UUID;

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
        try (SSLServerSocket server = server();
                CccClient client = client(server, server.getInetAddress().getHostAddress(), 2_000)) {
            client.start();
            try (SSLSocket socket = accept(server)) {
                assertThrows(SSLException.class, socket::startHandshake);
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
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int length = input.readInt();
        assertTrue(length > 0 && length <= 65_536);
        byte[] payload = new byte[length];
        input.readFully(payload);
        JsonNode result = JSON.readTree(payload);
        assertEquals(1, result.path("protocolVersion").intValue());
        return result;
    }

    private static ObjectNode accepted(JsonNode join) {
        return message("JOIN_ACCEPTED")
                .put("requestId", join.path("requestId").textValue())
                .put("sessionId", "session-01")
                .put("heartbeatIntervalMs", 2_000);
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
