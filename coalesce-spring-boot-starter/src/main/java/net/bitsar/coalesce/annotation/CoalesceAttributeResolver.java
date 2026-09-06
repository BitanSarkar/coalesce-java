package net.bitsar.coalesce.annotation;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.util.StringValueResolver;

/**
 * Resolves the placeholders in a {@link Coalesce} annotation and parses the result.
 *
 * <p>Java annotation attributes must be compile-time constants, so a TTL cannot be written
 * as {@code ${orders.fresh-ttl}} while typed as {@code long}. Declaring them as
 * {@code String} and resolving here is how Spring solves the same problem for
 * {@code @Scheduled(fixedDelayString = ...)}.
 *
 * <p>Results are cached per {@link Method}: resolution touches the {@code Environment} and
 * parses strings, neither of which belongs on a path that runs once per request. The
 * consequence is that a property changed after first invocation is not picked up.
 */
public class CoalesceAttributeResolver {

    private final StringValueResolver valueResolver;
    private final Map<Method, CoalesceAttributes> cache = new ConcurrentHashMap<>();

    /**
     * @param valueResolver resolves {@code ${...}} against the environment; pass
     *                      {@code null} to treat every attribute as a literal, which is
     *                      what a plain unit test wants
     */
    public CoalesceAttributeResolver(StringValueResolver valueResolver) {
        this.valueResolver = valueResolver;
    }

    /**
     * @param method the annotated method, used as the cache key
     * @param ann    its annotation
     * @return the resolved attributes
     */
    public CoalesceAttributes resolve(Method method, Coalesce ann) {
        return cache.computeIfAbsent(method, m -> {
            CoalesceAttributes attrs = new CoalesceAttributes(
                    requireText(resolve(ann.key()), m, "key"),
                    headerKeys(ann.headerKeys()),
                    resolve(ann.namespace()),
                    seconds(ann.freshTtlSeconds(), m, "freshTtlSeconds"),
                    seconds(ann.staleTtlSeconds(), m, "staleTtlSeconds"),
                    seconds(ann.pendingTtlSeconds(), m, "pendingTtlSeconds"),
                    seconds(ann.waitTimeoutSeconds(), m, "waitTimeoutSeconds"));
            checkRelationships(attrs, m);
            return attrs;
        });
    }

    /**
     * Each TTL can be individually valid and still combine into a configuration that
     * quietly does not work. Both invariants below are load-bearing:
     *
     * <ul>
     *   <li>{@code waitTimeout > pendingTtl}, or every follower gives up before a dead
     *       leader's lease expires and crash recovery never fires. Nothing fails visibly;
     *       a crash simply surfaces as a wave of timeouts instead of one takeover.
     *   <li>{@code freshTtl <= staleTtl}, or the freshness window outlives the entry
     *       itself and no value is ever served stale, disabling the background refresh
     *       the annotation exists to provide.
     * </ul>
     */
    private void checkRelationships(CoalesceAttributes attrs, Method method) {
        if (attrs.waitTimeout().compareTo(attrs.pendingTtl()) <= 0) {
            throw new IllegalStateException(describe(method, "waitTimeoutSeconds", "",
                    "is " + attrs.waitTimeout().toSeconds() + "s, which is not greater than pendingTtlSeconds ("
                            + attrs.pendingTtl().toSeconds() + "s). Followers would time out before a dead leader's"
                            + " lease expires, so crash recovery could never take over."));
        }
        if (attrs.freshTtl().compareTo(attrs.staleTtl()) > 0) {
            throw new IllegalStateException(describe(method, "freshTtlSeconds", "",
                    "is " + attrs.freshTtl().toSeconds() + "s, which is greater than staleTtlSeconds ("
                            + attrs.staleTtl().toSeconds() + "s). The entry would expire before it could ever be"
                            + " served stale, so no background refresh would run."));
        }
    }

    private String resolve(String value) {
        if (value == null || value.isEmpty() || valueResolver == null) {
            return value == null ? "" : value;
        }
        String resolved = valueResolver.resolveStringValue(value);
        return resolved == null ? "" : resolved;
    }

    /**
     * A resolved value may itself be a comma-separated list, so one property can supply
     * every header name rather than needing one placeholder per entry.
     */
    private List<String> headerKeys(String[] declared) {
        List<String> names = new ArrayList<>();
        for (String entry : declared) {
            for (String name : resolve(entry).split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    names.add(trimmed);
                }
            }
        }
        return names;
    }

    private Duration seconds(String raw, Method method, String attribute) {
        String resolved = resolve(raw).trim();
        if (resolved.isEmpty()) {
            throw new IllegalStateException(describe(method, attribute, raw, "resolved to an empty string"));
        }
        long value;
        try {
            value = Long.parseLong(resolved);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    describe(method, attribute, raw, "resolved to \"" + resolved + "\", which is not a number"), e);
        }
        if (value < 0) {
            throw new IllegalStateException(describe(method, attribute, raw, "resolved to " + value + ", which is negative"));
        }
        return Duration.ofSeconds(value);
    }

    private String requireText(String resolved, Method method, String attribute) {
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException(describe(method, attribute, "", "resolved to an empty string"));
        }
        return resolved;
    }

    /** Naming the method and the raw value matters: the failure surfaces on a request, not at startup. */
    private String describe(Method method, String attribute, String raw, String problem) {
        return "@Coalesce " + attribute + " on "
                + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                + (raw.isEmpty() ? "" : " (\"" + raw + "\")") + " " + problem;
    }
}
