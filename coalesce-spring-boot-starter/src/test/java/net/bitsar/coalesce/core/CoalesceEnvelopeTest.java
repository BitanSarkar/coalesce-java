package net.bitsar.coalesce.core;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoalesceEnvelopeTest {

    @Test
    void doneRoundTripsPayloadAndTimestamp() {
        byte[] payload = "{\"orderId\":\"A-1\"}".getBytes(StandardCharsets.UTF_8);
        long computedAt = 1_725_000_000_000L;

        CoalesceState state = CoalesceEnvelope.decode(CoalesceEnvelope.encodeDone(payload, computedAt));

        assertThat(state.status()).isEqualTo(CoalesceState.Status.DONE);
        assertThat(state.payload()).isEqualTo(payload);
        // The timestamp must survive the round trip, not be re-stamped at read time —
        // otherwise every cached value looks fresh and stale-while-revalidate never fires.
        assertThat(state.computedAt()).isEqualTo(computedAt);
    }

    @Test
    void emptyPayloadIsDoneNotAbsent() {
        CoalesceState state = CoalesceEnvelope.decode(CoalesceEnvelope.encodeDone(new byte[0], 123L));

        assertThat(state.status()).isEqualTo(CoalesceState.Status.DONE);
        assertThat(state.payload()).isEmpty();
    }

    @Test
    void failedRoundTripsMessage() {
        CoalesceState state = CoalesceEnvelope.decode(CoalesceEnvelope.encodeFailed("downstream is unhappy", 99L));

        assertThat(state.status()).isEqualTo(CoalesceState.Status.FAILED);
        assertThat(state.errorMessage()).isEqualTo("downstream is unhappy");
        assertThat(state.computedAt()).isEqualTo(99L);
    }

    @Test
    void aPayloadThatLooksLikeAFailureMarkerIsStillDone() {
        // The status byte is what decides, not a string prefix — this exact payload is what
        // broke the original sniff-for-"FAILED:" placeholder.
        byte[] payload = "FAILED:not really".getBytes(StandardCharsets.UTF_8);

        CoalesceState state = CoalesceEnvelope.decode(CoalesceEnvelope.encodeDone(payload, 1L));

        assertThat(state.status()).isEqualTo(CoalesceState.Status.DONE);
        assertThat(state.payload()).isEqualTo(payload);
    }

    @Test
    void garbageDecodesAsAbsentRatherThanThrowing() {
        assertThat(CoalesceEnvelope.decode(new byte[]{1, 2}).status()).isEqualTo(CoalesceState.Status.ABSENT);
        assertThat(CoalesceEnvelope.decode(null).status()).isEqualTo(CoalesceState.Status.ABSENT);
    }
}
