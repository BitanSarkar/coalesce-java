# The `@Coalesce` annotation

```java
package net.bitsar.coalesce;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Coalesce {

    /**
     * SpEL expression evaluated against the method's parameters, e.g. "#orderId"
     * or "#request.orderId". Must be deterministic: given the same logical call,
     * every pod must produce the same string. Do NOT derive this from
     * Object#toString() of a whole DTO (identity-based, differs per instance).
     * Reference explicit fields instead.
     */
    String key();

    /**
     * HTTP header names to fold into the key, e.g. {"X-Tenant-Id"}. Sorted
     * internally before joining, so declaration order does not matter. Requires
     * either the annotated method to accept ServerWebExchange/ServerHttpRequest
     * as a parameter, OR a WebFilter populating the Reactor Context. See
     * 04-key-resolution.md.
     */
    String[] headerKeys() default {};

    /**
     * Logical namespace prefixed onto the key, to disambiguate the same key
     * value used by different methods. Defaults to
     * "{DeclaringClassSimpleName}.{methodName}" if left blank.
     */
    String namespace() default "";

    /**
     * How long a cached result is served with NO background refresh triggered.
     * Set equal to staleTtlSeconds to disable stale-while-revalidate entirely
     * (pure coalescing + flat-TTL cache, no background refresh ever fires).
     * Set to 0 to trigger a background refresh attempt on every read past
     * the initial write (still only one actual execution wins the lock race).
     */
    long freshTtlSeconds() default 0;

    /**
     * Outer bound on how long a cached result is usable at all. This is also
     * the Redis bucket's TTL. Once it expires, the key is ABSENT again and
     * the next caller pays a full execution with no fallback.
     */
    long staleTtlSeconds() default 60;

    /**
     * Safety-net TTL on the lock itself: if a leader's pod crashes mid-execution,
     * the lock expires after this long and the next caller can acquire it fresh.
     * Set this comfortably above your method's expected p99 latency. Too short
     * and a slow-but-healthy leader gets "recovered from" while still working,
     * causing pile-on load on an already-slow downstream.
     */
    long pendingTtlSeconds() default 30;

    /**
     * Hard cap on how long a follower will wait for an outcome before giving up
     * with a CoalesceTimeoutException.
     */
    long waitTimeoutSeconds() default 45;
}
```

## Example usage

```java
@Coalesce(
    key = "#orderId",
    headerKeys = {"X-Tenant-Id"},
    freshTtlSeconds = 5,      // reads within 5s of the last write: instant, no refresh
    staleTtlSeconds = 120,    // reads up to 2 minutes old: instant + background refresh
    pendingTtlSeconds = 20,   // must exceed expected p99 of the downstream call
    waitTimeoutSeconds = 30
)
public Mono<OrderDto> getOrder(String orderId) {
    return orderClient.fetch(orderId);
}
```

## Choosing values

| Goal | freshTtlSeconds | staleTtlSeconds |
|---|---|---|
| Pure coalescing only (dedupe simultaneous calls, no caching after) | equal to staleTtlSeconds, both small (2 to 5s) | small (2 to 5s) |
| TPS shield with background refresh (SWR) | small, non-zero (e.g. 5 to 15s) | your real staleness tolerance (e.g. 60 to 300s) |
| Aggressive TPS shield, refresh on every miss window | 0 | your real staleness tolerance |
