package net.bitsar.coalesce.annotation;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringValueResolver;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Annotation attributes must be compile-time constants, so every configurable setting is a
 * String that is resolved and parsed here. These tests pin both halves: that a literal still
 * behaves as a number, and that a placeholder reaches the environment.
 */
class CoalesceAttributeResolverTest {

    /** Stands in for the environment; {@code ${a:b}} falls back to b when a is absent. */
    private static StringValueResolver env(Map<String, String> properties) {
        return value -> {
            if (value == null || !value.startsWith("${") || !value.endsWith("}")) {
                return value;
            }
            String body = value.substring(2, value.length() - 1);
            int colon = body.indexOf(':');
            String name = colon < 0 ? body : body.substring(0, colon);
            String fallback = colon < 0 ? null : body.substring(colon + 1);
            String resolved = properties.get(name);
            if (resolved != null) {
                return resolved;
            }
            if (fallback == null) {
                throw new IllegalArgumentException("Could not resolve placeholder '" + name + "'");
            }
            return fallback;
        };
    }

    @SuppressWarnings("unused")
    static class Sample {

        @Coalesce(key = "#id")
        Mono<String> defaults(String id) {
            return Mono.empty();
        }

        @Coalesce(key = "#id", freshTtlSeconds = "15", staleTtlSeconds = "600",
                pendingTtlSeconds = "20", waitTimeoutSeconds = "25")
        Mono<String> literals(String id) {
            return Mono.empty();
        }

        @Coalesce(key = "#id",
                freshTtlSeconds = "${orders.fresh-ttl:30}",
                staleTtlSeconds = "${orders.stale-ttl:300}",
                pendingTtlSeconds = "${orders.pending-ttl:10}",
                waitTimeoutSeconds = "${orders.wait-timeout:45}",
                namespace = "${orders.namespace:}",
                headerKeys = "${orders.headers:}")
        Mono<String> placeholders(String id) {
            return Mono.empty();
        }

        @Coalesce(key = "${orders.key:#id}")
        Mono<String> placeholderKey(String id) {
            return Mono.empty();
        }

        @Coalesce(key = "#id", freshTtlSeconds = "${orders.fresh-ttl:not-a-number}")
        Mono<String> unparseable(String id) {
            return Mono.empty();
        }

        @Coalesce(key = "#id", staleTtlSeconds = "${orders.stale-ttl:-5}")
        Mono<String> negative(String id) {
            return Mono.empty();
        }

        @Coalesce(key = "#id", pendingTtlSeconds = "10", waitTimeoutSeconds = "${orders.wait-timeout:3}")
        Mono<String> waitBelowPending(String id) {
            return Mono.empty();
        }

        @Coalesce(key = "#id", pendingTtlSeconds = "10", waitTimeoutSeconds = "10")
        Mono<String> waitEqualToPending(String id) {
            return Mono.empty();
        }

        @Coalesce(key = "#id", freshTtlSeconds = "120", staleTtlSeconds = "60",
                pendingTtlSeconds = "10", waitTimeoutSeconds = "30")
        Mono<String> freshAboveStale(String id) {
            return Mono.empty();
        }

        @Coalesce(key = "#id", freshTtlSeconds = "60", staleTtlSeconds = "60")
        Mono<String> freshEqualToStale(String id) {
            return Mono.empty();
        }

        // An overload: same class, same method name, so the same derived namespace.
        @Coalesce(key = "#id")
        Mono<String> defaults(String id, boolean full) {
            return Mono.empty();
        }

        @Coalesce(key = "#id", namespace = "shared")
        Mono<String> explicitlyShared(String id) {
            return Mono.empty();
        }

        @Coalesce(key = "#id", namespace = "shared")
        Mono<String> alsoExplicitlyShared(String id) {
            return Mono.empty();
        }
    }

    private CoalesceAttributes resolve(String method, Map<String, String> properties) throws Exception {
        Method m = Sample.class.getDeclaredMethod(method, String.class);
        return new CoalesceAttributeResolver(env(properties)).resolve(m, m.getAnnotation(Coalesce.class));
    }

    // ---------- literals ----------

    @Test
    void annotationDefaultsSurviveAsNumbers() throws Exception {
        CoalesceAttributes attrs = resolve("defaults", Map.of());

        assertThat(attrs.freshTtl()).isEqualTo(Duration.ZERO);
        assertThat(attrs.staleTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(attrs.pendingTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(attrs.waitTimeout()).isEqualTo(Duration.ofSeconds(45));
        // A blank attribute no longer stays blank: the effective namespace is derived
        // here so the key and the runtime toggle are built from the same string.
        assertThat(attrs.namespace()).isEqualTo("Sample.defaults");
        assertThat(attrs.headerKeys()).isEmpty();
    }

    @Test
    void literalStringsParseAsSeconds() throws Exception {
        CoalesceAttributes attrs = resolve("literals", Map.of());

        assertThat(attrs.freshTtl()).isEqualTo(Duration.ofSeconds(15));
        assertThat(attrs.staleTtl()).isEqualTo(Duration.ofSeconds(600));
        assertThat(attrs.freshTtlMillis()).isEqualTo(15_000);
    }

    // ---------- placeholders ----------

    @Test
    void placeholdersFallBackToTheirDefaultsWhenUnset() throws Exception {
        CoalesceAttributes attrs = resolve("placeholders", Map.of());

        assertThat(attrs.freshTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(attrs.staleTtl()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void configuredPropertiesWin() throws Exception {
        CoalesceAttributes attrs = resolve("placeholders", Map.of(
                "orders.fresh-ttl", "5",
                "orders.stale-ttl", "900",
                "orders.namespace", "orders.v3"));

        assertThat(attrs.freshTtl()).isEqualTo(Duration.ofSeconds(5));
        assertThat(attrs.staleTtl()).isEqualTo(Duration.ofSeconds(900));
        assertThat(attrs.namespace()).isEqualTo("orders.v3");
    }

    @Test
    void theKeyExpressionItselfCanComeFromAProperty() throws Exception {
        assertThat(resolve("placeholderKey", Map.of()).keyExpression()).isEqualTo("#id");
        assertThat(resolve("placeholderKey", Map.of("orders.key", "#id.toUpperCase()")).keyExpression())
                .isEqualTo("#id.toUpperCase()");
    }

    @Test
    void oneCommaSeparatedPropertySuppliesEveryHeaderName() throws Exception {
        // Otherwise a list would need one placeholder per entry, fixing its length at compile time.
        CoalesceAttributes attrs = resolve("placeholders",
                Map.of("orders.headers", "X-Tenant-Id, X-Region ,X-Channel"));

        assertThat(attrs.headerKeys()).containsExactly("X-Tenant-Id", "X-Region", "X-Channel");
    }

    @Test
    void anEmptyHeaderPropertyMeansNoHeaders() throws Exception {
        assertThat(resolve("placeholders", Map.of()).headerKeys()).isEmpty();
    }

    // ---------- failures name the method, because they surface on a request ----------

    @Test
    void aNonNumericValueFailsWithTheMethodAndTheRawAttribute() throws Exception {
        assertThatThrownBy(() -> resolve("unparseable", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("freshTtlSeconds")
                .hasMessageContaining("Sample.unparseable")
                .hasMessageContaining("not-a-number");
    }

    @Test
    void aNegativeTtlIsRejected() throws Exception {
        assertThatThrownBy(() -> resolve("negative", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staleTtlSeconds")
                .hasMessageContaining("negative");
    }

    // ---------- caching ----------

    @Test
    void resolutionHappensOncePerMethod() throws Exception {
        Method m = Sample.class.getDeclaredMethod("placeholders", String.class);
        int[] calls = {0};
        StringValueResolver counting = value -> {
            calls[0]++;
            return env(Map.of()).resolveStringValue(value);
        };
        CoalesceAttributeResolver resolver = new CoalesceAttributeResolver(counting);

        CoalesceAttributes first = resolver.resolve(m, m.getAnnotation(Coalesce.class));
        int afterFirst = calls[0];
        CoalesceAttributes second = resolver.resolve(m, m.getAnnotation(Coalesce.class));

        // Placeholder resolution reads the environment; it must not run once per request.
        assertThat(calls[0]).isEqualTo(afterFirst);
        assertThat(second).isSameAs(first);
    }

    @Test
    void aNullValueResolverTreatsEverythingAsALiteral() throws Exception {
        Method m = Sample.class.getDeclaredMethod("literals", String.class);

        CoalesceAttributes attrs = new CoalesceAttributeResolver(null).resolve(m, m.getAnnotation(Coalesce.class));

        assertThat(attrs.freshTtl()).isEqualTo(Duration.ofSeconds(15));
    }

    // ---------- relationships between attributes ----------

    /**
     * The nastiest misconfiguration this can have: every attribute parses, nothing warns,
     * and crash recovery simply never happens because followers are gone before the dead
     * leader's lease expires.
     */
    @Test
    void waitTimeoutBelowPendingTtlIsRejected() {
        assertThatThrownBy(() -> resolve("waitBelowPending", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("waitTimeoutSeconds")
                .hasMessageContaining("pendingTtlSeconds")
                .hasMessageContaining("crash recovery");
    }

    /** Equal is not good enough: the lease has to expire while a follower is still waiting. */
    @Test
    void waitTimeoutEqualToPendingTtlIsRejected() {
        assertThatThrownBy(() -> resolve("waitEqualToPending", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not greater than pendingTtlSeconds");
    }

    @Test
    void freshTtlAboveStaleTtlIsRejected() {
        assertThatThrownBy(() -> resolve("freshAboveStale", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("freshTtlSeconds")
                .hasMessageContaining("staleTtlSeconds");
    }

    /** The relationship is checked against resolved values, not the literals in the source. */
    @Test
    void relationshipIsCheckedAfterPlaceholderResolution() throws Exception {
        CoalesceAttributes attrs = resolve("waitBelowPending", Map.of("orders.wait-timeout", "45"));
        assertThat(attrs.waitTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    /** Equal fresh and stale is legal: it disables stale-while-revalidate, which is documented. */
    @Test
    void freshTtlEqualToStaleTtlIsAllowed() throws Exception {
        CoalesceAttributes attrs = resolve("freshEqualToStale", Map.of());
        assertThat(attrs.freshTtl()).isEqualTo(attrs.staleTtl());
    }

    // ---------- namespace collisions ----------

    /**
     * The derived namespace is ClassSimpleName.methodName, which overloads share. Left
     * alone they would name one Redis entry and serve each other's results, so the second
     * one to resolve is refused.
     */
    @Test
    void overloadsCannotSilentlyShareADerivedNamespace() throws Exception {
        CoalesceAttributeResolver resolver = new CoalesceAttributeResolver(null);
        Method one = Sample.class.getDeclaredMethod("defaults", String.class);
        Method overload = Sample.class.getDeclaredMethod("defaults", String.class, boolean.class);

        resolver.resolve(one, one.getAnnotation(Coalesce.class));

        assertThatThrownBy(() -> resolver.resolve(overload, overload.getAnnotation(Coalesce.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sample.defaults")
                .hasMessageContaining("serve each other's results")
                .hasMessageContaining("explicit namespace");
    }

    /** Resolving the same method twice is a cache hit, not a collision with itself. */
    @Test
    void resolvingOneMethodRepeatedlyIsNotACollision() throws Exception {
        CoalesceAttributeResolver resolver = new CoalesceAttributeResolver(null);
        Method m = Sample.class.getDeclaredMethod("defaults", String.class);

        CoalesceAttributes first = resolver.resolve(m, m.getAnnotation(Coalesce.class));
        CoalesceAttributes second = resolver.resolve(m, m.getAnnotation(Coalesce.class));

        assertThat(second).isSameAs(first);
    }

    /**
     * Two methods pointed at one namespace on purpose is someone deliberately sharing an
     * entry, which is their call. Only the accidental version is an error.
     */
    @Test
    void anExplicitNamespaceMayBeSharedDeliberately() throws Exception {
        CoalesceAttributeResolver resolver = new CoalesceAttributeResolver(null);
        Method one = Sample.class.getDeclaredMethod("explicitlyShared", String.class);
        Method two = Sample.class.getDeclaredMethod("alsoExplicitlyShared", String.class);

        assertThat(resolver.resolve(one, one.getAnnotation(Coalesce.class)).namespace()).isEqualTo("shared");
        assertThat(resolver.resolve(two, two.getAnnotation(Coalesce.class)).namespace()).isEqualTo("shared");
    }

    /** The derived namespace is what both the Redis key and the runtime toggle are built on. */
    @Test
    void aBlankNamespaceIsDerivedFromTheMethod() throws Exception {
        assertThat(resolve("literals", Map.of()).namespace()).isEqualTo("Sample.literals");
    }
}
