package com.example.coalesce.demo;

import com.example.coalesce.annotation.Coalesce;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The two paths under comparison. Both call exactly the same downstream, so any difference
 * in execution count is entirely down to the coalescing layer.
 */
@Service
public class OrderService {

    private final SimulatedOrderSource source;

    public OrderService(SimulatedOrderSource source) {
        this.source = source;
    }

    /**
     * freshTtl is deliberately short: with a long one a load test would cache everything
     * after the first ten calls and prove very little. At 2s a sustained run keeps
     * re-executing, so the numbers reflect genuine coalescing rather than a warm cache.
     */
    @Coalesce(
            key = "#bucket",
            freshTtlSeconds = 2,
            staleTtlSeconds = 30,
            pendingTtlSeconds = 10,
            waitTimeoutSeconds = 20)
    public Mono<List<OrderDto>> loadCoalesced(int bucket) {
        return source.fetch(bucket, Mode.COALESCED);
    }

    public Mono<List<OrderDto>> loadDirect(int bucket) {
        return source.fetch(bucket, Mode.DIRECT);
    }
}
