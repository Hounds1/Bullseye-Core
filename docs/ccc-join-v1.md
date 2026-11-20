# CCC join protocol v1

Core opens TCP+TLS to the UOG listener that handles CCC admission. Core validates
the listener's certificate and configured hostname using the JVM TLS settings.
The listener authenticates the Core, for example using mutual TLS, and binds the
approved identity to the claimed node ID. For mutual TLS, configure the Core's
client key store and the CA trust store through the JVM TLS settings.

## Frame

Each message on the TLS stream consists of a four-byte unsigned big-endian body
length followed immediately by one UTF-8 JSON object. Length is measured in
UTF-8 bytes and excludes the header. Valid lengths are 1 through 65,536 bytes
(`00 00 00 01` through `00 01 00 00`). Read the full header and body before
processing a message. A stream can split or combine frames across reads.

Every body includes the integer `protocolVersion: 1` and a string `type`.
Duplicate field names, invalid UTF-8, unsupported versions, invalid lengths,
and unexpected message types fail the connection. Field order is arbitrary.

## Join

Core sends `JOIN_REQUEST` as its first message after the TLS handshake.

```json
{
  "protocolVersion": 1,
  "type": "JOIN_REQUEST",
  "requestId": "e0a119f8-d44a-4ef0-9d3c-89eb836fcb54",
  "nodeId": "edge-01",
  "runId": "46e5fffd-6650-452d-8476-ac41e2453c2a"
}
```

`nodeId` is the stable, nonblank installation ID. Core assigns `runId` once
per process and retains it across reconnects. Every attempt gets a new UUID
`requestId` for correlating the request and decision.

| CCC reply | Required fields beyond the envelope | Optional fields |
| --- | --- | --- |
| `JOIN_ACCEPTED` | Echoed `requestId`, nonblank `sessionId`, positive signed 32-bit integer `heartbeatIntervalMs` | |
| `JOIN_REJECTED` | Echoed `requestId`, `reasonCode` | `retryAfterMs` |

## Approved session

- CCC sends `PING` frames at the specified heartbeat interval. Every `PING`
  includes a nonnegative signed 64-bit integer `sequence`. Core replies with
  `PONG` using the same sequence on the approved connection. Core disconnects
  if it cannot read a complete session message within three heartbeat intervals.
- To end an approved session, CCC sends `SESSION_REVOKED` with a `reasonCode`
  and optional `retryAfterMs`, then closes the connection. Core closes on receipt.
- Decision reason codes are 1–64 characters matching `[A-Z][A-Z0-9_]{0,63}`.
  A `retryAfterMs` value is a nonnegative signed 32-bit integer. Core waits at
  least this long before reconnecting, then requests approval again. A decision
  without this field stops retries until Core is restarted.
- On an unexpected disconnect or transport failure, Core reconnects with
  backoff from 1 to 30 seconds. Each successful join resets the backoff. Core
  uses a five-second timeout for TCP connect, TLS handshake reads, and the
  complete join response.

This protocol revision covers connection admission and liveness. The TCC
diagnostic payload has its own `schemaVersion`.
