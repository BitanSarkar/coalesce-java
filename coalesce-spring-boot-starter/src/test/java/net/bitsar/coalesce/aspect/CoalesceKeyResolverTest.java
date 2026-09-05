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

/** Pure key-derivation tests: no Redis, no reactive chain. */
class CoalesceKeyResolverTest {

    private final CoalesceKeyResolver resolver = new CoalesceKeyResolver();
    // null value resolver: attributes are treated as literals, which is what these tests want.
    private final CoalesceAttributeResolver attributes = new CoalesceAttributeResolver(null);

    @SuppressWarnings("unused")
    static class Sample {
        @Coalesce(key = "#orderId", headerKeys = {"X-Tenant-Id", "X-Region"})
        Mono<String> withHeaders(String orderId) {
            return Mono.empty();
        }

        @Coalesce(key = "#orderId", headerKeys = {"X-Region", "X-Tenant-Id"})
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
        String reversed = resolver.resolve(method("withHeaders"), new Object[]{"A-1"}, attributes("withHeadersReversed"), headers);

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

    @Test
    void namespaceDefaultsToClassAndMethodAndIsOverridable() throws Exception {
        assertThat(resolver.resolve(method("plain"), new Object[]{"A-1"}, attributes("plain"), HttpHeaders.EMPTY))
                .isEqualTo("coalesce:{Sample.plain:A-1}");
        assertThat(resolver.resolve(method("namespaced"), new Object[]{"A-1"}, attributes("namespaced"), HttpHeaders.EMPTY))
                .isEqualTo("coalesce:{orders.v2:A-1}");
    }

    @Test
    void lockBucketAndTopicGetDistinctKeysSharingOneHashTag() throws Exception {
        String key = resolver.resolve(method("plain"), new Object[]{"A-1"}, attributes("plain"), HttpHeaders.EMPTY);

        String state = CoalesceKeys.discriminate(key, "state");
        String lock = CoalesceKeys.discriminate(key, "lock");
        String notify = CoalesceKeys.discriminate(key, "notify");

        assertThat(state).isEqualTo("coalesce:state:{Sample.plain:A-1}");
        assertThat(lock).isEqualTo("coalesce:lock:{Sample.plain:A-1}");
        assertThat(notify).isEqualTo("coalesce:notify:{Sample.plain:A-1}");

        // Distinct Redis keys, or the bucket write clobbers the lock hash.
        assertThat(state).isNotEqualTo(lock).isNotEqualTo(notify);
        // Identical hash tag, or they can land on different cluster shards.
        String tag = key.substring(key.indexOf('{'));
        assertThat(state).endsWith(tag);
        assertThat(lock).endsWith(tag);
        assertThat(notify).endsWith(tag);
    }
}
