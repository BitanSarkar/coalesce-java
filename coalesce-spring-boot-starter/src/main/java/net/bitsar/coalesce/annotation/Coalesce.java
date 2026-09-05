package net.bitsar.coalesce.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Coalesces concurrent executions of a {@code Mono}/{@code Flux}-returning method across
 * the whole cluster, caches the result, and refreshes it in the background once stale.
 *
 * <p>Annotated methods must be idempotent: this is best-effort deduplication and a TPS
 * shield, not a distributed transaction primitive.
 *
 * <p><b>Every attribute accepts a property placeholder.</b> Java requires annotation values
 * to be compile-time constants, so the numeric settings are declared as {@code String} and
 * parsed after resolution — the same trick Spring's own {@code @Scheduled} uses for
 * {@code fixedDelayString}. A literal still works:
 *
 * <pre>{@code
 * @Coalesce(key = "#orderId", freshTtlSeconds = "30")
 * }</pre>
 *
 * and so does anything Spring's {@code Environment} can resolve, which includes environment
 * variables through relaxed binding — {@code ORDERS_FRESH_TTL} satisfies
 * {@code ${orders.fresh-ttl}}:
 *
 * <pre>{@code
 * @Coalesce(
 *         key = "#orderId",
 *         freshTtlSeconds = "${orders.fresh-ttl:30}",
 *         staleTtlSeconds = "${orders.stale-ttl:300}")
 * }</pre>
 *
 * <p>Always give a placeholder a default (the {@code :30} above) unless the property is
 * genuinely required: without one, a missing property fails the call rather than the
 * application start, since attributes resolve on first invocation.
 *
 * <p>Resolution happens once per method and is cached, so placeholders cost nothing per
 * call. That also means a property changing at runtime is not picked up.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Coalesce {

    /**
     * SpEL expression evaluated against the method's parameters, e.g. "#orderId"
     * or "#request.orderId". Must be deterministic: given the same logical call,
     * every pod must produce the same string. Do NOT derive this from
     * Object#toString() of a whole DTO (identity-based, differs per instance) —
     * reference explicit fields instead.
     *
     * <p>Placeholders are resolved before the expression is parsed, so
     * {@code "${orders.key-expression:#orderId}"} is valid.
     *
     * @return the key expression
     */
    String key();

    /**
     * HTTP header names to fold into the key, e.g. {"X-Tenant-Id"}. Sorted
     * internally before joining, so declaration order does not matter. Requires
     * either the annotated method to accept ServerWebExchange/ServerHttpRequest
     * as a parameter, OR a WebFilter populating the Reactor Context.
     *
     * <p>Each entry is placeholder-resolved, and a resolved value containing commas is
     * split into several header names — so one property can supply the whole list:
     * {@code headerKeys = "${orders.headers:X-Tenant-Id,X-Region}"}.
     *
     * @return the header names
     */
    String[] headerKeys() default {};

    /**
     * Logical namespace prefixed onto the key, to disambiguate the same key
     * value used by different methods. Defaults to
     * "{DeclaringClassSimpleName}.{methodName}" if left blank.
     *
     * @return the namespace, or empty for the default
     */
    String namespace() default "";

    /**
     * How long a cached result is served with NO background refresh triggered.
     * Set equal to staleTtlSeconds to disable stale-while-revalidate entirely.
     * Set to 0 to trigger a background refresh attempt on every read past the
     * initial write (still only one actual execution wins the lock race).
     *
     * @return seconds, as a literal or a placeholder
     */
    String freshTtlSeconds() default "0";

    /**
     * Outer bound on how long a cached result is usable at all. This is also the
     * Redis bucket's TTL — once it expires, the key is ABSENT again and the next
     * caller pays a full execution with no fallback.
     *
     * @return seconds, as a literal or a placeholder
     */
    String staleTtlSeconds() default "60";

    /**
     * Safety-net TTL on the lock itself: if a leader's pod crashes mid-execution,
     * the lock expires after this long and the next caller can acquire it fresh.
     * Set comfortably above the method's p99 latency — too short and a slow-but-healthy
     * leader gets "recovered from" while still working, piling load onto an already
     * slow downstream.
     *
     * @return seconds, as a literal or a placeholder
     */
    String pendingTtlSeconds() default "30";

    /**
     * Hard cap on how long a follower waits for an outcome before CoalesceTimeoutException.
     *
     * @return seconds, as a literal or a placeholder
     */
    String waitTimeoutSeconds() default "45";
}
