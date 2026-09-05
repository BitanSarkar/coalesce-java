package net.bitsar.coalesce.codec;

import java.lang.reflect.Type;

/**
 * Pluggable wire format for the cached payload itself (inside the envelope).
 *
 * <p>Deliberately expressed in {@code java.lang.reflect.Type} rather than any particular
 * serialization library's type token, so an implementation can use whatever it likes.
 * {@code Type} rather than {@code Class} because a {@code Flux<T>} is cached as a
 * {@code List<T>} — a bare {@code Class} cannot carry that element type, and the payload
 * would come back as a list of untyped maps.
 */
public interface CoalesceCodec {

    byte[] encode(Object value);

    Object decode(byte[] bytes, Type type);
}
