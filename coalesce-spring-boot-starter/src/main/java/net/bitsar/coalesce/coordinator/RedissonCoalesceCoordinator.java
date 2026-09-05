package net.bitsar.coalesce.coordinator;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import net.bitsar.coalesce.core.CoalesceEnvelope;
import net.bitsar.coalesce.core.CoalesceKeys;
import net.bitsar.coalesce.core.CoalesceState;
import org.redisson.api.RBucketReactive;
import org.redisson.api.RLockReactive;
import org.redisson.api.RTopicReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Owns the three Redis-backed objects behind one coalescing key: the lock (leader
 * election), the bucket (cached status + payload), and the topic (wake-up signal).
 *
 * <p><b>Three distinct Redis keys, one shared hash tag.</b> The lock, bucket and topic
 * must NOT share a key string: Redisson's lock stores a hash there, while the bucket
 * stores a plain value, so pointing both at the same key makes the bucket write clobber
 * the lock and every later lock operation fail with WRONGTYPE. They are instead
 * discriminated by an infix ({@code state:} / {@code lock:} / {@code notify:}) placed
 * BEFORE the {@code {...}} hash tag, so all three still hash to the same Redis Cluster
 * slot and stay cross-slot safe.
 */
public class RedissonCoalesceCoordinator implements CoalesceCoordinator {

    private final RedissonReactiveClient redisson;

    public RedissonCoalesceCoordinator(RedissonReactiveClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public Mono<Boolean> tryAcquire(String key, long lockId, Duration leaseTime) {
        RLockReactive lock = redisson.getLock(lockKey(key));
        return lock.tryLock(0, leaseTime.toSeconds(), TimeUnit.SECONDS, lockId);
    }

    @Override
    public Mono<Void> release(String key, long lockId) {
        return redisson.getLock(lockKey(key)).unlock(lockId)
                // Already expired, or the lease was taken over after pendingTtl elapsed.
                // Not fatal: the leader is done either way.
                .onErrorResume(e -> Mono.empty());
    }

    @Override
    public Mono<Void> markDone(String key, byte[] payload, Duration ttl) {
        return bucket(key).set(CoalesceEnvelope.encodeDone(payload, System.currentTimeMillis()), ttl)
                .then(topic(key).publish("DONE"))
                .then();
    }

    @Override
    public Mono<Void> markFailed(String key, Throwable err, Duration ttl) {
        String message = err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
        return bucket(key).set(CoalesceEnvelope.encodeFailed(message, System.currentTimeMillis()), ttl)
                .then(topic(key).publish("FAILED"))
                .then();
    }

    @Override
    public Mono<CoalesceState> fetchState(String key) {
        return bucket(key).get()
                .map(CoalesceEnvelope::decode)
                .defaultIfEmpty(CoalesceState.absent());
    }

    /**
     * One-shot wake-up signal. The listener is registered on subscribe and removed on
     * cancellation or completion — without that teardown every follower wait would leak a
     * Redisson listener (and its Redis subscription) for the life of the process.
     */
    @Override
    public Flux<String> listen(String key) {
        RTopicReactive topic = topic(key);
        return Flux.create(sink ->
                topic.addListener(String.class, (channel, msg) -> sink.next(msg))
                        .subscribe(
                                listenerId -> sink.onDispose(() -> topic.removeListener(listenerId).subscribe()),
                                sink::error));
    }

    // ---------- key derivation: distinct keys, shared hash tag ----------

    private RBucketReactive<byte[]> bucket(String key) {
        return redisson.getBucket(CoalesceKeys.discriminate(key, "state"), ByteArrayCodec.INSTANCE);
    }

    private RTopicReactive topic(String key) {
        // On Redis 7+ Cluster prefer getShardedTopic(...) so publishes stay on the owning
        // shard instead of fanning out to every node. The shared hash tag already
        // guarantees publisher and subscriber agree on the slot.
        return redisson.getTopic(CoalesceKeys.discriminate(key, "notify"), StringCodec.INSTANCE);
    }

    private String lockKey(String key) {
        return CoalesceKeys.discriminate(key, "lock");
    }

}
