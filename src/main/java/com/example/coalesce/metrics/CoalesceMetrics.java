package com.example.coalesce.metrics;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The leader/hit/wait ratio is the entire point of the framework — if it is not
 * visible there is no way to tell whether coalescing is actually happening.
 */
@Component
public class CoalesceMetrics {

    private final AtomicLong leaderExecutions = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong followerWaits = new AtomicLong();
    private final AtomicLong backgroundRefreshes = new AtomicLong();
    private final AtomicLong failedRetries = new AtomicLong();
    private final AtomicLong leaderTakeovers = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong followerWaitMillisTotal = new AtomicLong();

    public void leaderExecution() {
        leaderExecutions.incrementAndGet();
    }

    public void cacheHit() {
        cacheHits.incrementAndGet();
    }

    public void followerWait(long millis) {
        followerWaits.incrementAndGet();
        followerWaitMillisTotal.addAndGet(millis);
    }

    public void backgroundRefresh() {
        backgroundRefreshes.incrementAndGet();
    }

    public void failedRetry() {
        failedRetries.incrementAndGet();
    }

    /** A waiter took the lock over after the previous leader's lease expired (crash recovery). */
    public void leaderTakeover() {
        leaderTakeovers.incrementAndGet();
    }

    public void timeout() {
        timeouts.incrementAndGet();
    }

    public Map<String, Long> snapshot() {
        long waits = followerWaits.get();
        return Map.of(
                "leaderExecutions", leaderExecutions.get(),
                "cacheHits", cacheHits.get(),
                "followerWaits", waits,
                "backgroundRefreshes", backgroundRefreshes.get(),
                "failedRetries", failedRetries.get(),
                "leaderTakeovers", leaderTakeovers.get(),
                "timeouts", timeouts.get(),
                "meanFollowerWaitMillis", waits == 0 ? 0 : followerWaitMillisTotal.get() / waits);
    }

    public void reset() {
        leaderExecutions.set(0);
        cacheHits.set(0);
        followerWaits.set(0);
        backgroundRefreshes.set(0);
        failedRetries.set(0);
        leaderTakeovers.set(0);
        timeouts.set(0);
        followerWaitMillisTotal.set(0);
    }
}
