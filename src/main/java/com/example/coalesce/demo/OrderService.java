package com.example.coalesce.demo;

import com.example.coalesce.annotation.Coalesce;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stands in for a slow, expensive downstream dependency. Every method body increments an
 * execution counter — the whole point of the framework is that the counter grows far more
 * slowly than the request count.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final Duration DOWNSTREAM_LATENCY = Duration.ofMillis(400);

    private final AtomicInteger executions = new AtomicInteger();
    private final AtomicBoolean failing = new AtomicBoolean(false);
    private final AtomicBoolean hangOnce = new AtomicBoolean(false);

    @Coalesce(
            key = "#orderId",
            headerKeys = {"X-Tenant-Id"},
            freshTtlSeconds = 5,      // within 5s of the last write: instant, no refresh
            staleTtlSeconds = 120,    // up to 2 minutes old: instant + background refresh
            pendingTtlSeconds = 20,   // must exceed the downstream p99
            waitTimeoutSeconds = 30)
    public Mono<OrderDto> getOrder(String orderId) {
        return Mono.defer(() -> {
            int n = executions.incrementAndGet();
            log.info("EXECUTING downstream fetch #{} for order {}", n, orderId);
            if (failing.get()) {
                return Mono.<OrderDto>error(new IllegalStateException("downstream is unhappy"))
                        .delaySubscription(DOWNSTREAM_LATENCY);
            }
            return Mono.just(new OrderDto(orderId, "CONFIRMED", 4999, Instant.now()))
                    .delayElement(DOWNSTREAM_LATENCY);
        });
    }

    /** Exercises the empty-Mono path: nothing cached, but "nothing" is the real answer. */
    @Coalesce(key = "#orderId", freshTtlSeconds = 5, staleTtlSeconds = 60)
    public Mono<OrderDto> findMissingOrder(String orderId) {
        return Mono.defer(() -> {
            executions.incrementAndGet();
            log.info("EXECUTING downstream lookup for missing order {}", orderId);
            return Mono.<OrderDto>empty().delaySubscription(DOWNSTREAM_LATENCY);
        });
    }

    /** Exercises the bounded-Flux path: the leader collects it to a List before caching. */
    @Coalesce(key = "#orderId", freshTtlSeconds = 5, staleTtlSeconds = 60)
    public Flux<String> listItems(String orderId) {
        return Flux.defer(() -> {
            executions.incrementAndGet();
            log.info("EXECUTING downstream item list for order {}", orderId);
            return Flux.just("widget", "gizmo", "doohickey").delaySequence(DOWNSTREAM_LATENCY);
        });
    }

    /**
     * Simulates a leader crashing mid-execution: the first invocation never completes, so
     * it never writes the bucket and never releases the lock. Recovery has to come from the
     * lock's own lease expiring after pendingTtlSeconds (kept short here so it is
     * observable in seconds rather than a leisurely 30).
     */
    @Coalesce(
            key = "#orderId",
            freshTtlSeconds = 5,
            staleTtlSeconds = 60,
            pendingTtlSeconds = 3,
            waitTimeoutSeconds = 30)
    public Mono<OrderDto> flakyOrder(String orderId) {
        return Mono.defer(() -> {
            executions.incrementAndGet();
            if (hangOnce.compareAndSet(true, false)) {
                log.info("SIMULATED CRASH: leader for {} will never complete", orderId);
                return Mono.never();
            }
            log.info("EXECUTING recovery fetch for order {}", orderId);
            return Mono.just(new OrderDto(orderId, "RECOVERED", 1234, Instant.now()))
                    .delayElement(Duration.ofMillis(100));
        });
    }

    public void hangOnce() {
        hangOnce.set(true);
    }

    public int executions() {
        return executions.get();
    }

    public void reset() {
        executions.set(0);
        failing.set(false);
        hangOnce.set(false);
    }

    public void failing(boolean value) {
        failing.set(value);
    }
}
