package io.bullseye.core.uog.ccc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

class CccProtocolTest {

    private static final String PING = """
            {"protocolVersion":1,"type":"PING","sequence":42}""";
    private static final String JOIN_ACCEPTED = """
            {"protocolVersion":1,"type":"JOIN_ACCEPTED","requestId":"request","sessionId":"session",
            "heartbeatIntervalMs":1000}""";

    @Test
    void writesJoinAndPongAsFlushedBigEndianUtf8Frames() throws IOException {
        FlushedOutput out = new FlushedOutput();
        UUID requestId = UUID.fromString("e24c534a-135c-4d5a-ba5c-f57ec2183b4b");
        UUID runId = UUID.fromString("869c13b7-9d0a-4247-af6c-2f250a56b9a9");
        String nodeId = "\uB178\uB4DC-\"\uC11C\uC6B8\"";

        CccProtocol.writeJoin(out, requestId, nodeId, runId);
        byte[] joinFrame = out.toByteArray();
        assertEquals(joinFrame.length - Integer.BYTES, ByteBuffer.wrap(joinFrame).getInt());
        CccProtocol.writePong(out, Long.MAX_VALUE);
        assertEquals(2, out.flushCount);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        var join = CccProtocol.read(in);
        assertEquals(1, CccProtocol.positiveInt(join, "protocolVersion"));
        assertEquals("JOIN_REQUEST", CccProtocol.type(join));
        assertEquals(requestId.toString(), CccProtocol.text(join, "requestId"));
        assertEquals(nodeId, CccProtocol.text(join, "nodeId"));
        assertEquals(runId.toString(), CccProtocol.text(join, "runId"));
        var pong = CccProtocol.read(in);
        assertEquals("PONG", CccProtocol.type(pong));
        assertEquals(Long.MAX_VALUE, CccProtocol.nonnegativeLong(pong, "sequence"));
        assertEquals(-1, in.read());
    }

    @Test
    void readsAFrameDeliveredInSingleByteChunksAtTheSizeLimit() throws IOException {
        byte[] bytes = frame(PING + " ".repeat(65_536 - PING.length()));
        var in = new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, 1));
            }
        };

        var message = CccProtocol.read(in);

        assertEquals("PING", CccProtocol.type(message));
        assertEquals(42, CccProtocol.nonnegativeLong(message, "sequence"));
    }

    @Test
    void rejectsInvalidLengthsBeforeReadingPayload() {
        for (int length : new int[] {0, -1, 65_537, Integer.MAX_VALUE}) {
            byte[] bytes = ByteBuffer.allocate(Integer.BYTES).putInt(length).array();
            IOException failure = assertThrows(IOException.class,
                    () -> CccProtocol.read(new ByteArrayInputStream(bytes)));
            assertEquals("Invalid CCC frame length", failure.getMessage());
        }
    }

    @Test
    void reportsEofForMissingOrIncompleteFrames() {
        for (byte[] bytes : new byte[][] {{}, {0, 0, 0}, {0, 0, 0, 2, '{'}}) {
            assertThrows(EOFException.class,
                    () -> CccProtocol.read(new ByteArrayInputStream(bytes)));
        }
    }

    @Test
    void rejectsMalformedUtf8InsteadOfDecodingReplacementCharacters() throws IOException {
        byte[] payload = PING.replace("PING", "XX").getBytes(StandardCharsets.UTF_8);
        int start = PING.indexOf("PING");
        payload[start] = (byte) 0xC3;
        payload[start + 1] = (byte) 0x28;

        IOException failure = assertThrows(IOException.class,
                () -> CccProtocol.read(new ByteArrayInputStream(frame(payload))));

        assertEquals("Invalid CCC UTF-8 payload", failure.getMessage());
    }

    @Test
    void rejectsInvalidJsonEnvelopes() {
        for (String json : new String[] {
            "[]", "null", "{", "{}", PING + "{}",
            "{\"protocolVersion\":1,\"type\":\"PING\",\"type\":\"PONG\"}",
            "{\"protocolVersion\":1,\"type\":\"PING\",\"nested\":{\"key\":1,\"key\":2}}",
            "{\"protocolVersion\":2,\"type\":\"PING\"}",
            "{\"protocolVersion\":1.0,\"type\":\"PING\"}",
            "{\"protocolVersion\":\"1\",\"type\":\"PING\"}",
            "{\"protocolVersion\":1,\"type\":\" \"}",
            "{\"protocolVersion\":1,\"type\":7}"
        }) {
            assertThrows(IOException.class, () -> CccProtocol.read(input(json)), json);
        }
    }

    @Test
    void verifiesFieldTypesAndNumericBoundsWithoutCoercion() throws IOException {
        var message = CccProtocol.read(input("""
                {"protocolVersion":1,"type":"JOIN_ACCEPTED","sessionId":"session-a",
                "heartbeatIntervalMs":2147483647,"retryAfterMs":0,"tooBig":2147483648,
                "aboveLong":9223372036854775808,"negative":-1,"fraction":1.0,"nullValue":null}
                """));

        assertEquals("session-a", CccProtocol.text(message, "sessionId"));
        assertEquals(Integer.MAX_VALUE, CccProtocol.positiveInt(message, "heartbeatIntervalMs"));
        assertEquals(0, CccProtocol.retryAfterMillis(message));
        assertEquals(2_147_483_648L, CccProtocol.nonnegativeLong(message, "tooBig"));
        assertThrows(IOException.class, () -> CccProtocol.text(message, "nullValue"));
        assertThrows(IOException.class, () -> CccProtocol.positiveInt(message, "retryAfterMs"));
        assertThrows(IOException.class, () -> CccProtocol.positiveInt(message, "tooBig"));
        assertThrows(IOException.class, () -> CccProtocol.nonnegativeLong(message, "aboveLong"));
        assertThrows(IOException.class, () -> CccProtocol.nonnegativeLong(message, "negative"));
        assertThrows(IOException.class, () -> CccProtocol.nonnegativeLong(message, "fraction"));
    }

    @Test
    void treatsOnlyAnAbsentRetryAfterAsUnspecified() throws IOException {
        var message = CccProtocol.read(input(PING));
        assertEquals(-1, CccProtocol.retryAfterMillis(message));
        message.put("retryAfterMs", Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, CccProtocol.retryAfterMillis(message));
        message.put("retryAfterMs", 2_147_483_648L);
        assertThrows(IOException.class, () -> CccProtocol.retryAfterMillis(message));
        message.putNull("retryAfterMs");
        assertThrows(IOException.class, () -> CccProtocol.retryAfterMillis(message));
    }

    @Test
    void negotiatesTccOnlyWithTheExactCapabilityString() throws IOException {
        var response = CccProtocol.read(input(JOIN_ACCEPTED));
        assertFalse(CccProtocol.supportsTcc(response));

        var capabilities = response.putArray("capabilities");
        assertFalse(CccProtocol.supportsTcc(response));
        capabilities.add("tcc.snapshot.v2").add("TCC.SNAPSHOT.V1");
        assertFalse(CccProtocol.supportsTcc(response));
        capabilities.add("tcc.snapshot.v1");
        assertTrue(CccProtocol.supportsTcc(response));
    }

    @Test
    void rejectsMalformedCapabilityFields() throws IOException {
        var response = CccProtocol.read(input(JOIN_ACCEPTED));
        response.put("capabilities", "tcc.snapshot.v1");
        assertThrows(IOException.class, () -> CccProtocol.supportsTcc(response));
        response.putNull("capabilities");
        assertThrows(IOException.class, () -> CccProtocol.supportsTcc(response));
        response.putArray("capabilities").add("tcc.snapshot.v1").add(1);
        assertThrows(IOException.class, () -> CccProtocol.supportsTcc(response));
    }

    @Test
    void writesTccSnapshotAsAnObjectAndPreservesOriginalBody() throws IOException {
        String telemetry = """
                {
                  "schemaVersion": 1,
                  "nodeId": "\uD55C\uAE00",
                  "sequence": 17
                }
                """;
        byte[] payload = CccProtocol.encodeTccSnapshot(telemetry);
        assertEquals("{\"protocolVersion\":1,\"type\":\"TCC_SNAPSHOT\",\"telemetry\":" + telemetry + "}",
                new String(payload, StandardCharsets.UTF_8));

        FlushedOutput out = new FlushedOutput();
        CccProtocol.writeFrame(out, payload);
        var message = CccProtocol.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("TCC_SNAPSHOT", CccProtocol.type(message));
        assertTrue(message.path("telemetry").isObject());
        assertEquals(17, message.path("telemetry").path("sequence").longValue());
        assertEquals(1, out.flushCount);
    }

    @Test
    void measuresTccSizeUsingFullEnvelopeUtf8Bytes() throws IOException {
        String telemetry = "{\"nodeId\":\"\uD55C\"}";
        int minimumBytes = CccProtocol.encodeTccSnapshot(telemetry).length;
        String atLimit = telemetry + " ".repeat(65_536 - minimumBytes);

        assertEquals(65_536, CccProtocol.encodeTccSnapshot(atLimit).length);
        assertNull(CccProtocol.encodeTccSnapshot(atLimit + " "));
    }

    @Test
    void rejectsInvalidTccBodyBeforeEncoding() {
        for (String telemetry : new String[] {
            "null", "[]", "{}{}", "{\"sequence\":1,\"sequence\":2}", "{\"s\":\"\uD800\"}"
        }) {
            assertThrows(IOException.class, () -> CccProtocol.encodeTccSnapshot(telemetry));
        }
    }

    @Test
    void rejectsInvalidOutboundFramesBeforeWriting() {
        FlushedOutput out = new FlushedOutput();
        UUID id = UUID.randomUUID();

        assertThrows(IOException.class, () -> CccProtocol.writePong(out, -1));
        assertThrows(IOException.class, () -> CccProtocol.writeJoin(out, id, " ", id));
        assertThrows(IOException.class, () -> CccProtocol.writeJoin(out, id, "x".repeat(65_536), id));
        assertThrows(IOException.class, () -> CccProtocol.writeFrame(out, new byte[0]));
        assertThrows(IOException.class, () -> CccProtocol.writeFrame(out, new byte[65_537]));
        assertEquals(0, out.size());
        assertEquals(0, out.flushCount);
    }

    private static ByteArrayInputStream input(String payload) throws IOException {
        return new ByteArrayInputStream(frame(payload));
    }

    private static byte[] frame(String payload) throws IOException {
        return frame(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] frame(byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(payload.length);
        data.write(payload);
        return out.toByteArray();
    }

    private static final class FlushedOutput extends ByteArrayOutputStream {
        private int flushCount;

        @Override
        public void flush() {
            flushCount++;
        }
    }
}
