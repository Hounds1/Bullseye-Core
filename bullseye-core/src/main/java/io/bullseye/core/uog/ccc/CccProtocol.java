package io.bullseye.core.uog.ccc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

final class CccProtocol {

    private static final int VERSION = 1;
    private static final int MAX_FRAME_BYTES = 65_536;
    private static final String TCC_CAPABILITY = "tcc.snapshot.v1";
    private static final String TCC_SNAPSHOT_PREFIX =
            "{\"protocolVersion\":" + VERSION + ",\"type\":\"TCC_SNAPSHOT\",\"telemetry\":";
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private CccProtocol() {}

    static void writeJoin(OutputStream out, UUID requestId, String nodeId, UUID runId)
            throws IOException {
        ObjectNode message = message("JOIN_REQUEST");
        message.put("requestId", Objects.requireNonNull(requestId, "requestId").toString());
        message.put("nodeId", nodeId);
        message.put("runId", Objects.requireNonNull(runId, "runId").toString());
        text(message, "nodeId");
        write(out, message);
    }

    static void writePong(OutputStream out, long sequence) throws IOException {
        ObjectNode message = message("PONG");
        message.put("sequence", sequence);
        nonnegativeLong(message, "sequence");
        write(out, message);
    }

    static boolean supportsTcc(ObjectNode response) throws IOException {
        JsonNode capabilities = response.path("capabilities");
        if (capabilities.isMissingNode()) {
            return false;
        }
        if (!capabilities.isArray()) {
            throw invalidField("capabilities");
        }
        boolean supportsTcc = false;
        for (JsonNode capability : capabilities) {
            if (!capability.isTextual()) {
                throw invalidField("capabilities");
            }
            supportsTcc |= TCC_CAPABILITY.equals(capability.textValue());
        }
        return supportsTcc;
    }

    /** Returns the complete body, or null if it exceeds the frame size limit. */
    static byte[] encodeTccSnapshot(String telemetryJson) throws IOException {
        parseObject(telemetryJson);
        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .encode(CharBuffer.wrap(TCC_SNAPSHOT_PREFIX + telemetryJson + '}'));
        } catch (CharacterCodingException failure) {
            throw new IOException("Invalid CCC UTF-8 payload");
        }
        if (encoded.remaining() > MAX_FRAME_BYTES) {
            return null;
        }
        byte[] payload = new byte[encoded.remaining()];
        encoded.get(payload);
        return payload;
    }

    static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        requireFrameLength(payload.length);
        DataOutputStream frame = new DataOutputStream(out);
        frame.writeInt(payload.length);
        frame.write(payload);
        frame.flush();
    }

    static ObjectNode read(InputStream in) throws IOException {
        DataInputStream frame = new DataInputStream(in);
        int length = frame.readInt();
        requireFrameLength(length);
        byte[] payload = new byte[length];
        frame.readFully(payload);

        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("Invalid CCC UTF-8 payload");
        }

        ObjectNode message = parseObject(json);
        if (positiveInt(message, "protocolVersion") != VERSION) {
            throw new IOException("Unsupported CCC protocol version");
        }
        type(message);
        return message;
    }

    static String type(ObjectNode message) throws IOException {
        return text(message, "type");
    }

    static String text(ObjectNode message, String field) throws IOException {
        JsonNode value = message.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalidField(field);
        }
        return value.textValue();
    }

    static boolean validReasonCode(String reason) {
        return reason != null && REASON_CODE.matcher(reason).matches();
    }

    static int positiveInt(ObjectNode message, String field) throws IOException {
        JsonNode value = message.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw invalidField(field);
        }
        return value.intValue();
    }

    static long nonnegativeLong(ObjectNode message, String field) throws IOException {
        JsonNode value = message.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalidField(field);
        }
        return value.longValue();
    }

    static long retryAfterMillis(ObjectNode message) throws IOException {
        if (!message.has("retryAfterMs")) {
            return -1;
        }
        long value = nonnegativeLong(message, "retryAfterMs");
        if (value > Integer.MAX_VALUE) {
            throw invalidField("retryAfterMs");
        }
        return value;
    }

    private static ObjectNode message(String type) {
        return JSON.createObjectNode().put("protocolVersion", VERSION).put("type", type);
    }

    private static void write(OutputStream out, ObjectNode message) throws IOException {
        writeFrame(out, JSON.writeValueAsBytes(message));
    }

    private static ObjectNode parseObject(String json) throws IOException {
        JsonNode parsed;
        try {
            parsed = JSON.readTree(json);
        } catch (JsonProcessingException failure) {
            throw new IOException("Invalid CCC JSON payload");
        }
        if (!(parsed instanceof ObjectNode object)) {
            throw new IOException("Expected a CCC JSON object");
        }
        return object;
    }

    private static void requireFrameLength(int length) throws IOException {
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Invalid CCC frame length");
        }
    }

    private static IOException invalidField(String field) {
        return new IOException("Invalid CCC field: " + field);
    }
}
