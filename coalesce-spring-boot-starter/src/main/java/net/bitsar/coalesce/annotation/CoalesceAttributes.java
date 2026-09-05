package net.bitsar.coalesce.annotation;

import java.time.Duration;
import java.util.List;

/**
 * One {@link Coalesce} annotation with every placeholder resolved and every number parsed.
 *
 * <p>Produced once per annotated method and cached, so the hot path never re-reads a
 * property or re-parses a string.
 *
 * @param keyExpression   SpEL to evaluate against the invocation's arguments
 * @param headerKeys      header names folded into the key, already split and trimmed
 * @param namespace       resolved namespace, or empty to derive it from the method
 * @param freshTtl        age below which no background refresh is triggered
 * @param staleTtl        outer bound on usable staleness; also the Redis TTL
 * @param pendingTtl      lock lease, the crash-recovery safety net
 * @param waitTimeout     a follower's hard cap before {@code CoalesceTimeoutException}
 */
public record CoalesceAttributes(
        String keyExpression,
        List<String> headerKeys,
        String namespace,
        Duration freshTtl,
        Duration staleTtl,
        Duration pendingTtl,
        Duration waitTimeout) {

    public CoalesceAttributes {
        headerKeys = List.copyOf(headerKeys);
    }

    /** Milliseconds, because the aspect compares against {@code System.currentTimeMillis()}. */
    public long freshTtlMillis() {
        return freshTtl.toMillis();
    }
}
