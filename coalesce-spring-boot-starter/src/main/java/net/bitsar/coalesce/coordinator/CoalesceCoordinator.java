package net.bitsar.coalesce.coordinator;

import java.time.Duration;
import net.bitsar.coalesce.core.CoalesceState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The shared state every pod coordinates through: a lock that elects one leader per key, a
 * bucket holding that leader's outcome, and a wake-up channel telling waiters to re-read it.
 *
 * <p>{@link RedissonCoalesceCoordinator} is the implementation shipped here. Replacing it is
 * a supported extension point — declare your own {@code CoalesceCoordinator} bean and the
 * auto-configuration backs off — but every pod in a cluster must use the same one, and must
 * derive keys through {@link net.bitsar.coalesce.core.CoalesceKeys}.
 */
public interface CoalesceCoordinator {

    /**
     * Attempts to become the leader for {@code key} without blocking.
     *
     * @param lockId    identifies this acquisition, so only the holder can release it
     * @param leaseTime how long the lock survives if the holder dies mid-execution
     * @return {@code true} if this caller is now the leader
     */
    Mono<Boolean> tryAcquire(String key, long lockId, Duration leaseTime);

    /** Releases a lock this caller holds. Never fails: an expired lease is not an error. */
    Mono<Void> release(String key, long lockId);

    /** Publishes a successful result and wakes every waiter. */
    Mono<Void> markDone(String key, byte[] payload, Duration ttl);

    /** Publishes a failure and wakes every waiter, so they retry rather than time out. */
    Mono<Void> markFailed(String key, Throwable err, Duration ttl);

    /** Reads the current outcome, or {@link CoalesceState#absent()} if there is none. */
    Mono<CoalesceState> fetchState(String key);

    /**
     * Wake-up signals for {@code key}. Best-effort by nature — callers must also poll, since
     * a dropped message would otherwise strand every waiter until its timeout.
     */
    Flux<String> listen(String key);
}
