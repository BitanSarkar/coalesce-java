package com.example.coalesce.integration;

import com.example.coalesce.annotation.Coalesce;
import com.example.coalesce.demo.OrderDto;
import com.example.coalesce.demo.OrderService;
import com.example.coalesce.metrics.CoalesceMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@EnabledIf("com.example.coalesce.integration.RedisAvailable#check")
class CoalesceIntegrationTest {

    private static final Duration LIMIT = Duration.ofSeconds(30);

    @Autowired
    OrderService orders;

    @Autowired
    CoalesceMetrics metrics;

    @Autowired
    SwrProbe swr;

    @Autowired
    TypedFluxProbe typedFlux;

    /** Every test uses a fresh key, so tests never collide through the shared Redis. */
    private String freshKey() {
        return "it-" + UUID.randomUUID();
    }

    @BeforeEach
    void resetCounters() {
        orders.reset();
        metrics.reset();
        swr.resetRuns();
    }

    @Test
    void concurrentCallsForOneKeyExecuteTheMethodExactlyOnce() {
        String id = freshKey();

        List<OrderDto> results = Flux.range(0, 20)
                .flatMap(i -> orders.getOrder(id))
                .collectList()
                .block(LIMIT);

        assertThat(results).hasSize(20);
        assertThat(orders.executions()).isEqualTo(1);
        // Identical instant across all 20 proves they share the leader's single result.
        assertThat(results.stream().map(OrderDto::fetchedAt).distinct().toList()).hasSize(1);
    }

    @Test
    void cachedResultIsServedWithoutReExecuting() {
        String id = freshKey();

        orders.getOrder(id).block(LIMIT);
        assertThat(orders.executions()).isEqualTo(1);

        for (int i = 0; i < 5; i++) {
            assertThat(orders.getOrder(id).block(LIMIT)).isNotNull();
        }
        assertThat(orders.executions()).isEqualTo(1);
        assertThat(metrics.snapshot().get("cacheHits")).isEqualTo(5L);
    }

    @Test
    void anEmptyMonoIsCachedAsEmptyRatherThanTreatedAsAbsent() {
        String id = freshKey();

        assertThat(orders.findMissingOrder(id).blockOptional(LIMIT)).isEmpty();
        assertThat(orders.executions()).isEqualTo(1);

        // The follower must get an empty Mono back, not hang waiting for a value that
        // will never arrive, and not NPE on a null payload.
        assertThat(orders.findMissingOrder(id).blockOptional(LIMIT)).isEmpty();
        assertThat(orders.executions()).isEqualTo(1);
    }

    @Test
    void aBoundedFluxIsCollectedCachedAndReplayed() {
        String id = freshKey();

        assertThat(orders.listItems(id).collectList().block(LIMIT))
                .containsExactly("widget", "gizmo", "doohickey");
        assertThat(orders.executions()).isEqualTo(1);

        assertThat(orders.listItems(id).collectList().block(LIMIT))
                .containsExactly("widget", "gizmo", "doohickey");
        assertThat(orders.executions()).isEqualTo(1);
    }

    @Test
    void aCachedFluxOfDtosDecodesBackIntoDtosNotUntypedMaps() {
        String id = freshKey();

        List<OrderDto> first = typedFlux.orders(id).collectList().block(LIMIT);
        assertThat(first).hasSize(2).allSatisfy(o -> assertThat(o).isInstanceOf(OrderDto.class));

        // Second call comes back through the codec from Redis. The decode type has to be a
        // parameterized List<OrderDto>: with a bare Class the elements would deserialize
        // into LinkedHashMaps and this cast would fail.
        List<OrderDto> cached = typedFlux.orders(id).collectList().block(LIMIT);
        assertThat(cached).hasSize(2).allSatisfy(o -> assertThat(o).isInstanceOf(OrderDto.class));
        assertThat(cached).isEqualTo(first);
        assertThat(cached.get(0).orderId()).isEqualTo(id + "-a");
    }

    @Test
    void exactlyOneWaiterRetriesAfterAFailureAndTheRestShareItsResult() {
        String id = freshKey();

        orders.failing(true);
        assertThatThrownBy(() -> orders.getOrder(id).block(LIMIT))
                .hasMessageContaining("downstream is unhappy");
        assertThat(orders.executions()).isEqualTo(1);

        orders.failing(false);
        List<OrderDto> results = Flux.range(0, 5)
                .flatMap(i -> orders.getOrder(id))
                .collectList()
                .block(LIMIT);

        assertThat(results).hasSize(5);
        // One retry total, not one per caller.
        assertThat(orders.executions()).isEqualTo(2);
        assertThat(results.stream().map(OrderDto::fetchedAt).distinct().toList()).hasSize(1);
    }

    @Test
    void aStaleReadIsServedImmediatelyAndTriggersOneBackgroundRefresh() throws Exception {
        String id = freshKey();

        String first = swr.value(id).block(LIMIT);
        assertThat(swr.runs()).isEqualTo(1);

        Thread.sleep(1_200); // past freshTtl (1s), still inside staleTtl (60s)

        long startedAt = System.currentTimeMillis();
        String stale = swr.value(id).block(LIMIT);
        long elapsed = System.currentTimeMillis() - startedAt;

        // Served from cache without waiting for the refresh, which takes ~300ms.
        assertThat(stale).isEqualTo(first);
        assertThat(elapsed).isLessThan(250);

        // The refresh happens off the response path, so give it a moment to land.
        Thread.sleep(1_000);
        assertThat(swr.runs()).isEqualTo(2);
        assertThat(metrics.snapshot().get("backgroundRefreshes")).isEqualTo(1L);
    }

    @Test
    void concurrentStaleReadsTriggerOnlyOneBackgroundRefresh() throws Exception {
        String id = freshKey();

        swr.value(id).block(LIMIT);
        Thread.sleep(1_200);

        Flux.range(0, 10).flatMap(i -> swr.value(id)).collectList().block(LIMIT);
        Thread.sleep(1_000);

        // 1 initial execution + exactly 1 refresh, no matter how many callers saw it stale.
        assertThat(swr.runs()).isEqualTo(2);
    }

    @TestConfiguration
    static class Probes {

        @Bean
        SwrProbe swrProbe() {
            return new SwrProbe();
        }

        @Bean
        TypedFluxProbe typedFluxProbe() {
            return new TypedFluxProbe();
        }
    }

    /** A Flux of a real DTO, to prove generic element types survive the cache round trip. */
    static class TypedFluxProbe {

        @Coalesce(key = "#id", freshTtlSeconds = 30, staleTtlSeconds = 60)
        public Flux<OrderDto> orders(String id) {
            return Flux.just(
                    new OrderDto(id + "-a", "CONFIRMED", 100, Instant.parse("2026-01-01T00:00:00Z")),
                    new OrderDto(id + "-b", "PENDING", 200, Instant.parse("2026-01-02T00:00:00Z")));
        }
    }

    /**
     * Short freshTtl so stale-while-revalidate is observable without a long sleep.
     *
     * <p>State is exposed through methods, not a public field: this bean is behind a CGLIB
     * proxy (that is how @Coalesce is applied), and reading a field through a proxy returns
     * the proxy subclass's own uninitialised field rather than the target's.
     */
    static class SwrProbe {

        private final AtomicInteger runs = new AtomicInteger();

        public int runs() {
            return runs.get();
        }

        public void resetRuns() {
            runs.set(0);
        }

        @Coalesce(
                key = "#id",
                freshTtlSeconds = 1,
                staleTtlSeconds = 60,
                pendingTtlSeconds = 10,
                waitTimeoutSeconds = 15)
        public Mono<String> value(String id) {
            return Mono.defer(() -> {
                runs.incrementAndGet();
                return Mono.just("value-for-" + id + "@" + Instant.now())
                        .delayElement(Duration.ofMillis(300));
            });
        }
    }
}
