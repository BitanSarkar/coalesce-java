package net.bitsar.coalesce.aspect;

import net.bitsar.coalesce.annotation.Coalesce;
import net.bitsar.coalesce.annotation.CoalesceAttributeResolver;
import net.bitsar.coalesce.annotation.CoalesceAttributes;
import net.bitsar.coalesce.core.CoalesceKeys;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure key-derivation tests: no Redis, no reactive chain. */
class CoalesceKeyResolverTest {

    private static final String SAMPLE = Sample.class.getName();

    private final CoalesceKeyResolver resolver = new CoalesceKeyResolver();
    // null value resolver: attributes are treated as literals, which is what these tests want.
    private final CoalesceAttributeResolver attributes = new CoalesceAttributeResolver(null);

    @SuppressWarnings("unused")
    static class Sample {
        // Both carry the same explicit namespace so these two differ in header order and
        // nothing else, which is the only thing the ordering test is about.
        @Coalesce(key = "#orderId", namespace = "orders", headerKeys = {"X-Tenant-Id", "X-Region"})
        Mono<String> withHeaders(String orderId) {
            return Mono.empty();
        }

        @Coalesce(key = "#orderId", namespace = "orders", headerKeys = {"X-Region", "X-Tenant-Id"})
        Mono<String> withHeadersReversed(String orderId) {
            return Mono.empty();
        }

        @Coalesce(key = "#orderId")
        Mono<String> plain(String orderId) {
            return Mono.empty();
        }

        @Coalesce(key = "#orderId", namespace = "orders.v2")
        Mono<String> namespaced(String orderId) {
            return Mono.empty();
        }

        @Coalesce(key = "#missing")
        Mono<String> unknownVariable(String orderId) {
            return Mono.empty();
        }
    }

    private Method method(String name) throws Exception {
        return Sample.class.getDeclaredMethod(name, String.class);
    }

    private CoalesceAttributes attributes(String name) throws Exception {
        Method m = method(name);
        return attributes.resolve(m, m.getAnnotation(Coalesce.class));
    }

    @Test
    void headerDeclarationOrderDoesNotChangeTheKey() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Tenant-Id", "acme");
        headers.add("X-Region", "eu-west-1");

        String forward = resolver.resolve(method("withHeaders"), new Object[]{"A-1"}, attributes("withHeaders"), headers);
        String reversed = resolver.resolve(method("withHeadersReversed"), new Object[]{"A-1"}, attributes("withHeadersReversed"), headers);

        assertThat(forward).isEqualTo(reversed);
    }

    @Test
    void headerValuesAreTrimmed() throws Exception {
        HttpHeaders padded = new HttpHeaders();
        padded.add("X-Tenant-Id", "  acme  ");
        padded.add("X-Region", "eu-west-1");

        HttpHeaders clean = new HttpHeaders();
        clean.add("X-Tenant-Id", "acme");
        clean.add("X-Region", "eu-west-1");

        assertThat(resolver.resolve(method("withHeaders"), new Object[]{"A-1"}, attributes("withHeaders"), padded))
                .isEqualTo(resolver.resolve(method("withHeaders"), new Object[]{"A-1"}, attributes("withHeaders"), clean));
    }

    @Test
    void differentArgumentsProduceDifferentKeys() throws Exception {
        String a = resolver.resolve(method("plain"), new Object[]{"A-1"}, attributes("plain"), HttpHeaders.EMPTY);
        String b = resolver.resolve(method("plain"), new Object[]{"A-2"}, attributes("plain"), HttpHeaders.EMPTY);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void keyCarriesAHashTagAroundEverythingAfterThePrefix() throws Exception {
        String key = resolver.resolve(method("plain"), new Object[]{"A-1"}, attributes("plain"), HttpHeaders.EMPTY);

        assertThat(key).startsWith("coalesce:{").endsWith("}");
        assertThat(key.chars().filter(c -> c == '{').count()).isEqualTo(1);
        assertThat(key.chars().filter(c -> c == '}').count()).isEqualTo(1);
    }

    /**
     * The derived namespace carries the package and the parameter types, so overloads and
     * same-named classes in different packages cannot land on one entry. An explicit
     * namespace replaces all of it, which is how a long default gets shortened.
     */
    @Test
    void namespaceDefaultsToTheFullSignatureAndIsOverridable() throws Exception {
        assertThat(resolver.resolve(method("plain"), new Object[]{"A-1"}, attributes("plain"), HttpHeaders.EMPTY))
                .isEqualTo("coalesce:{" + SAMPLE + ".plain(String):A-1}");
        assertThat(resolver.resolve(method("namespaced"), new Object[]{"A-1"}, attributes("namespaced"), HttpHeaders.EMPTY))
                .isEqualTo("coalesce:{orders.v2:A-1}");
    }

    @Test
    void lockBucketAndTopicGetDistinctKeysSharingOneHashTag() throws Exception {
        String key = resolver.resolve(method("plain"), new Object[]{"A-1"}, attributes("plain"), HttpHeaders.EMPTY);

        String state = CoalesceKeys.discriminate(key, "state");
        String lock = CoalesceKeys.discriminate(key, "lock");
        String notify = CoalesceKeys.discriminate(key, "notify");

        assertThat(state).isEqualTo("coalesce:state:{" + SAMPLE + ".plain(String):A-1}");
        assertThat(lock).isEqualTo("coalesce:lock:{" + SAMPLE + ".plain(String):A-1}");
        assertThat(notify).isEqualTo("coalesce:notify:{" + SAMPLE + ".plain(String):A-1}");

        // Distinct Redis keys, or the bucket write clobbers the lock hash.
        assertThat(state).isNotEqualTo(lock).isNotEqualTo(notify);
        // Identical hash tag, or they can land on different cluster shards.
        String tag = key.substring(key.indexOf('{'));
        assertThat(state).endsWith(tag);
        assertThat(lock).endsWith(tag);
        assertThat(notify).endsWith(tag);
    }

    // ---------- a key that does not resolve must never become a shared key ----------

    /**
     * The failure this guards against is the worst one the library has: a null key makes
     * every distinct call collapse onto the single key {@code Class.method:null}, so one
     * caller is served another caller's cached result. Loud beats silent.
     */
    @Test
    void nullKeyIsRejectedRatherThanSharedAcrossCallers() throws Exception {
        assertThatThrownBy(() ->
                resolver.resolve(method("plain"), new Object[]{null}, attributes("plain"), HttpHeaders.EMPTY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("#orderId")
                .hasMessageContaining("plain")
                .hasMessageContaining("resolved to null");
    }

    /** Same reasoning: a blank key groups unrelated calls together. */
    @Test
    void blankKeyIsRejected() throws Exception {
        assertThatThrownBy(() ->
                resolver.resolve(method("plain"), new Object[]{"   "}, attributes("plain"), HttpHeaders.EMPTY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolved to an empty string");
    }

    /** An expression naming a parameter that does not exist evaluates to null, not an error. */
    @Test
    void expressionReferencingAnUnknownParameterIsRejected() throws Exception {
        assertThatThrownBy(() -> resolver.resolve(
                method("unknownVariable"), new Object[]{"A-1"}, attributes("unknownVariable"), HttpHeaders.EMPTY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("#missing");
    }
}
