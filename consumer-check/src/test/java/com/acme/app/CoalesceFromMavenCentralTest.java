package com.acme.app;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.bitsar.coalesce.aspect.CoalesceAspect;
import net.bitsar.coalesce.coordinator.CoalesceCoordinator;
import net.bitsar.coalesce.metrics.CoalesceMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumes net.bitsar:coalesce-spring-boot-starter:0.1.0 straight off Maven Central, from a
 * package the library knows nothing about.
 */
@SpringBootTest
class CoalesceFromMavenCentralTest {

    private static final Duration LIMIT = Duration.ofSeconds(30);

    @Autowired
    PriceService prices;

    @Autowired
    CoalesceMetrics metrics;

    @Autowired
    ApplicationContext context;

    @BeforeEach
    void reset() {
        prices.reset();
        metrics.reset();
    }

    @Test
    void theStarterWiresItselfWithoutAnyComponentScan() {
        // com.acme.app never scans net.bitsar.coalesce -- these can only come from
        // META-INF/spring/...AutoConfiguration.imports inside the published jar.
        assertThat(context.getBean(CoalesceAspect.class)).isNotNull();
        assertThat(context.getBean(CoalesceCoordinator.class)).isNotNull();
    }

    @Test
    void twentyConcurrentCallsExecuteTheDownstreamOnce() {
        String symbol = "ACME-" + UUID.randomUUID();

        List<Quote> results = Flux.range(0, 20)
                .flatMap(i -> prices.quote(symbol))
                .collectList()
                .block(LIMIT);

        assertThat(results).hasSize(20);
        assertThat(prices.executions()).isEqualTo(1);
        assertThat(results).allSatisfy(q -> assertThat(q).isEqualTo(results.get(0)));
    }

    @Test
    void theCachedValueSurvivesAndDecodesBackIntoTheRecord() {
        String symbol = "ACME-" + UUID.randomUUID();

        Quote first = prices.quote(symbol).block(LIMIT);
        Quote cached = prices.quote(symbol).block(LIMIT);

        assertThat(prices.executions()).isEqualTo(1);
        // Round-tripped through Redis and the JSON codec, not handed back from memory.
        assertThat(cached).isInstanceOf(Quote.class).isEqualTo(first);
        assertThat(cached.symbol()).isEqualTo(symbol);
        assertThat(metrics.snapshot().get("cacheHits")).isEqualTo(1L);
    }

    @Test
    void differentKeysDoNotCollide() {
        String a = "A-" + UUID.randomUUID();
        String b = "B-" + UUID.randomUUID();

        // If the SpEL key resolved to null -- the failure mode when -parameters is missing --
        // both symbols would share one key and this would execute once, not twice.
        Quote qa = prices.quote(a).block(LIMIT);
        Quote qb = prices.quote(b).block(LIMIT);

        assertThat(prices.executions()).isEqualTo(2);
        assertThat(qa.symbol()).isEqualTo(a);
        assertThat(qb.symbol()).isEqualTo(b);
    }
}
