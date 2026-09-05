package com.acme.app;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import net.bitsar.coalesce.annotation.Coalesce;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class PriceService {

    private final AtomicInteger executions = new AtomicInteger();

    /** Counts real executions. Behind a CGLIB proxy, so read it through a method. */
    public int executions() {
        return executions.get();
    }

    public void reset() {
        executions.set(0);
    }

    @Coalesce(key = "#symbol", freshTtlSeconds = 60, staleTtlSeconds = 120,
            pendingTtlSeconds = 10, waitTimeoutSeconds = 20)
    public Mono<Quote> quote(String symbol) {
        return Mono.defer(() -> {
            executions.incrementAndGet();
            return Mono.just(new Quote(symbol, 42.5))
                    .delayElement(Duration.ofMillis(250)); // a slow downstream
        });
    }
}
