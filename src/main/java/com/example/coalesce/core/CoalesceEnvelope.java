package com.example.coalesce.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire format for the bucket: an explicit status byte and a computed-at timestamp in
 * front of the payload.
 *
 * <pre>
 *   byte  0      status  (0 = DONE, 1 = FAILED)
 *   bytes 1..8   computedAt, epoch millis, big-endian
 *   bytes 9..    payload bytes (DONE) or UTF-8 error message (FAILED)
 * </pre>
 *
 * <p>Replaces the POC placeholder that sniffed a {@code "FAILED:"} string prefix off the
 * raw payload — a real payload can legitimately start with those bytes, and the sniffing
 * version also had nowhere to record when the value was computed.
 */
public final class CoalesceEnvelope {

    private static final byte STATUS_DONE = 0;
    private static final byte STATUS_FAILED = 1;
    private static final int HEADER_BYTES = 1 + Long.BYTES;

    private CoalesceEnvelope() {
    }

    public static byte[] encodeDone(byte[] payload, long computedAt) {
        return ByteBuffer.allocate(HEADER_BYTES + payload.length)
                .put(STATUS_DONE)
                .putLong(computedAt)
                .put(payload)
                .array();
    }

    public static byte[] encodeFailed(String errorMessage, long computedAt) {
        byte[] message = (errorMessage == null ? "" : errorMessage).getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(HEADER_BYTES + message.length)
                .put(STATUS_FAILED)
                .putLong(computedAt)
                .put(message)
                .array();
    }

    public static CoalesceState decode(byte[] raw) {
        if (raw == null || raw.length < HEADER_BYTES) {
            // Not one of ours, or truncated. Treat as a cold start rather than failing the
            // caller — worst case is one redundant execution.
            return CoalesceState.absent();
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        byte status = buffer.get();
        long computedAt = buffer.getLong();
        byte[] body = new byte[buffer.remaining()];
        buffer.get(body);

        return switch (status) {
            case STATUS_DONE -> CoalesceState.done(body, computedAt);
            case STATUS_FAILED -> CoalesceState.failed(new String(body, StandardCharsets.UTF_8), computedAt);
            default -> CoalesceState.absent();
        };
    }
}
