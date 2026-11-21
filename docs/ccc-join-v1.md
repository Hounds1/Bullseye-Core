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
| `JOIN_ACCEPTED` | Echoed `requestId`, nonblank `sessionId`, positive signed 32-bit integer `heartbeatIntervalMs` | `capabilities` (array of strings) |
| `JOIN_REJECTED` | Echoed `requestId`, `reasonCode` | `retryAfterMs` |

## Approved session

- CCC sends `PING` frames at the specified heartbeat interval. Every `PING`
  includes a nonnegative signed 64-bit integer `sequence`. Core replies with
  `PONG` using the same sequence on the approved connection. Core disconnects
  if it cannot read a complete `PING` within three heartbeat intervals.
- To end an approved session, CCC sends `SESSION_REVOKED` with a `reasonCode`
  and optional `retryAfterMs`, then closes the connection. Core closes on receipt.
- Decision reason codes are 1–64 characters matching `[A-Z][A-Z0-9_]{0,63}`.
  A `retryAfterMs` value is a nonnegative signed 32-bit integer. Core waits at
  least this long before reconnecting, then requests approval again. A decision
  without this field stops retries until Core is restarted.
- On an unexpected disconnect or transport failure, Core reconnects with
  exponential backoff from 1 to 30 seconds. Joining a session without TCC
  delivery resets the backoff. Core uses five-second timeouts for TCP connect,
  TLS handshake reads, and reading the join response.

## Optional TCC snapshot delivery

Core sends telemetry when `bullseye.tcc.enabled=true` and the approved session
advertises the exact capability `tcc.snapshot.v1`. An accepted connection can
advertise the capability as follows.

```json
{
  "protocolVersion": 1,
  "type": "JOIN_ACCEPTED",
  "requestId": "e0a119f8-d44a-4ef0-9d3c-89eb836fcb54",
  "sessionId": "session-001",
  "heartbeatIntervalMs": 1000,
  "capabilities": ["tcc.snapshot.v1"]
}
```

Core sends a frame with `protocolVersion: 1`, `type: TCC_SNAPSHOT`, and
`telemetry` containing the [TCC snapshot v1 object](../README.md#tcc-snapshot-format-v1).
The v1 object carries its own `schemaVersion`. The receiver binds `nodeId` and
`runId` to the approved Core on that connection and uses
`(nodeId, runId, sequence)` to recognize a previously handled record.
The full frame body is subject to the 65,536-byte frame limit.

On the same connection, the receiver echoes the positive `sequence` of the
record in a `TCC_ACK` frame.

```json
{
  "protocolVersion": 1,
  "type": "TCC_ACK",
  "sequence": 1,
  "status": "ACCEPTED"
}
```

| Status | Core action |
| --- | --- |
| `ACCEPTED` | Complete delivery after the receiver adopts the record. |
| `DUPLICATE` | Complete delivery for the already adopted record. |
| `REJECTED` | Drop the rejected record and continue with the latest pending snapshot. |
| `RETRYABLE` | Reconnect and retry the same record on the next eligible session. |

When a session negotiates TCC delivery, a matching `ACCEPTED` or `DUPLICATE`
resets the reconnect backoff. The accumulated delay carries across joins
while delivery keeps failing. A matching `REJECTED` completes the record and
preserves the current backoff.

A `RETRYABLE` or `REJECTED` acknowledgement for the current record can carry
an optional `reasonCode`. Core includes it in the log when it matches
`[A-Z][A-Z0-9_]{0,63}`. Core ignores well-formed acknowledgements for
completed sequences.

Core keeps one in-flight snapshot and at most one newer pending snapshot in
memory. New evaluations replace the pending snapshot. Core uses a five-second
deadline from the start of a telemetry write through its acknowledgement.
After a lost acknowledgement or interrupted connection, Core resends the same
body and sequence on the next eligible session. A new Core process starts a
new run. An oversized snapshot is dropped, which can leave a sequence gap.
Durable transition history and archival acknowledgements use a separate contract.

`PING`, `PONG`, and `SESSION_REVOKED` stay active during delivery. Only a
`PING` refreshes the heartbeat deadline. The Core writer prioritizes pending
heartbeat replies between frames. On session revocation, Core closes the
transport and follows CCC's reconnect decision. Local diagnostic evaluation
and file publication continue on their own execution paths.
