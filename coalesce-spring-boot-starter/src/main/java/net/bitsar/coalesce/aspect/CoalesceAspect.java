package net.bitsar.coalesce.aspect;

import net.bitsar.coalesce.annotation.Coalesce;
import net.bitsar.coalesce.codec.CoalesceCodec;
import net.bitsar.coalesce.coordinator.CoalesceCoordinator;
import net.bitsar.coalesce.core.CoalesceState;
import net.bitsar.coalesce.exception.CoalesceTimeoutException;
import net.bitsar.coalesce.metrics.CoalesceMetrics;
import net.bitsar.coalesce.web.HeaderCaptureFilter;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

@Aspect
public class CoalesceAspect {

    private static final Logger log = LoggerFactory.getLogger(CoalesceAspect.class);
    private static final AtomicLong LOCK_ID_SEQ = new AtomicLong();
    private static final Map<Method, Type> TYPE_CACHE = new ConcurrentHashMap<>();
    private static final byte[] EMPTY_MARKER = new byte[0];

    private final CoalesceCoordinator coordinator;
    private final CoalesceCodec codec;
    private final CoalesceMetrics metrics;
    private final CoalesceKeyResolver keyResolver;

    /**
     * Refuse to cache anything larger than this. Redisson buffers each command in Netty's
     * direct arena before writing it, so a handful of oversized entries in flight can
     * exhaust MaxDirectMemorySize and take the process down — the payload never reaches
     * Redis, it dies in the encoder.
     */
    private final int maxPayloadBytes;

    public CoalesceAspect(CoalesceCoordinator coordinator,
                          CoalesceCodec codec,
                          CoalesceMetrics metrics,
                          CoalesceKeyResolver keyResolver,
                          int maxPayloadBytes) {
        this.coordinator = coordinator;
        this.codec = codec;
        this.metrics = metrics;
        this.keyResolver = keyResolver;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    /** One annotated call: everything the reactive chain below needs, resolved once. */
    private record Invocation(ProceedingJoinPoint pjp, Coalesce ann, MethodSignature sig, String key, boolean flux) {
    }

    // ---------- entry point ----------

    @Around("@annotation(coalesce)")
    public Object around(ProceedingJoinPoint pjp, Coalesce coalesce) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Class<?> returnType = sig.getMethod().getReturnType();

        // The key is resolved INSIDE deferContextual: header values live in the Reactor
        // Context, which is only visible once inside the reactive chain.
        if (Flux.class.isAssignableFrom(returnType)) {
            return Flux.deferContextual(ctx -> {
                Invocation inv = new Invocation(pjp, coalesce, sig, resolveKey(pjp, coalesce, ctx), true);
                return coalesce(inv).flatMapMany(list -> Flux.fromIterable(asList(list)));
            });
        }
        if (Mono.class.isAssignableFrom(returnType)) {
            return Mono.deferContextual(ctx ->
                    coalesce(new Invocation(pjp, coalesce, sig, resolveKey(pjp, coalesce, ctx), false)));
        }
        throw new IllegalStateException("@Coalesce only supports Mono/Flux return types, got " + returnType);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    // ---------- the core rule: check the bucket BEFORE ever touching the lock ----------

    private Mono<Object> coalesce(Invocation inv) {
        return coordinator.fetchState(inv.key()).flatMap(state -> switch (state.status()) {
            case DONE -> serveDone(inv, state);
            case FAILED -> {
                metrics.failedRetry();
                yield acquireAndExecute(inv, false);
            }
            case ABSENT, PENDING -> acquireAndExecute(inv, false);
        });
    }

    // ---------- stale-while-revalidate ----------

    private Mono<Object> serveDone(Invocation inv, CoalesceState state) {
        long ageMillis = System.currentTimeMillis() - state.computedAt();
        metrics.cacheHit();

        if (ageMillis < inv.ann().freshTtlSeconds() * 1000L) {
            return decode(inv, state); // fresh: nothing else happens
        }

        // Stale: kick a refresh off the response path. This caller does not wait for it.
        // Counting the refresh happens where the lock is actually won — most of these
        // attempts lose the race to a refresh already in flight and quietly do nothing.
        acquireAndExecute(inv, true).subscribe(
                v -> {
                },
                err -> log.warn("background refresh failed for {}: {}", inv.key(), err.toString()));

        return decode(inv, state);
    }

    /** Empty payload means the method legitimately returned an empty Mono — stay empty, never null. */
    private Mono<Object> decode(Invocation inv, CoalesceState state) {
        if (state.payload() == null || state.payload().length == 0) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> codec.decode(state.payload(), payloadType(inv.sig())));
    }

    // ---------- lock acquisition ----------

    private Mono<Object> acquireAndExecute(Invocation inv, boolean isRefresh) {
        long lockId = LOCK_ID_SEQ.incrementAndGet();
        Duration pendingTtl = Duration.ofSeconds(inv.ann().pendingTtlSeconds());

        return coordinator.tryAcquire(inv.key(), lockId, pendingTtl)
                .flatMap(acquired -> acquired
                        ? runRefreshAware(inv, lockId, isRefresh)
                        // Losing the race during a background refresh is a no-op, not a wait:
                        // someone else is already refreshing and this caller already has a response.
                        : (isRefresh ? Mono.empty() : awaitAsFollower(inv)));
    }

    private Mono<Object> runRefreshAware(Invocation inv, long lockId, boolean isRefresh) {
        if (isRefresh) {
            metrics.backgroundRefresh();
        }
        return runAsLeader(inv, lockId, isRefresh);
    }

    private Mono<Object> runAsLeader(Invocation inv, long lockId, boolean isRefresh) {
        Duration ttl = Duration.ofSeconds(inv.ann().staleTtlSeconds());
        metrics.leaderExecution();

        return invokeTarget(inv)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty()) // force a signal even for an empty Mono
                .flatMap(opt -> {
                    byte[] payload = opt.map(codec::encode).orElse(EMPTY_MARKER);
                    if (payload.length > maxPayloadBytes) {
                        // Serve this caller, but do not put it in Redis. Caching it would
                        // risk the whole process for an entry that is too big to be worth
                        // sharing anyway.
                        metrics.payloadTooLarge();
                        log.warn("not caching {}: payload is {} bytes, over the {} byte limit",
                                inv.key(), payload.length, maxPayloadBytes);
                        return Mono.just(opt);
                    }
                    return coordinator.markDone(inv.key(), payload, ttl).thenReturn(opt);
                })
                .flatMap(Mono::justOrEmpty) // unwrap; an empty result stays an empty Mono
                .onErrorResume(err -> isRefresh
                        // Background refresh failed: leave the last known-good value in place.
                        ? Mono.error(err)
                        // Cold start: nothing to fall back on, so publish the failure.
                        : coordinator.markFailed(inv.key(), err, ttl).then(Mono.error(err)))
                // Release on BOTH success and error, or a failing leader holds the lock for the
                // full pendingTtl for no reason.
                .doFinally(signal -> coordinator.release(inv.key(), lockId).subscribe());
    }

    /**
     * A {@code Flux}-returning method is collected into a List before caching — the leader
     * cannot hand a live stream to other pods. Casting the Flux straight to Mono (as the
     * original sketch did) throws ClassCastException at runtime.
     */
    @SuppressWarnings("unchecked")
    private Mono<Object> invokeTarget(Invocation inv) {
        return Mono.defer(() -> {
            try {
                Object result = inv.pjp().proceed();
                return inv.flux()
                        ? ((Flux<Object>) result).collectList().map(list -> (Object) list)
                        : (Mono<Object>) result;
            } catch (Throwable t) {
                return Mono.error(t);
            }
        });
    }

    // ---------- follower wait loop ----------

    private Mono<Object> awaitAsFollower(Invocation inv) {
        long startedAt = System.currentTimeMillis();
        return waitLoop(inv)
                .timeout(Duration.ofSeconds(inv.ann().waitTimeoutSeconds()),
                        Mono.defer(() -> {
                            metrics.timeout();
                            return Mono.error(new CoalesceTimeoutException(inv.key()));
                        }))
                .doFinally(signal -> metrics.followerWait(System.currentTimeMillis() - startedAt));
    }

    private Mono<Object> waitLoop(Invocation inv) {
        return coordinator.fetchState(inv.key()).flatMap(state -> switch (state.status()) {

            case DONE -> decode(inv, state);

            // Exactly one waiter takes over; the rest fall back to polling instead of
            // re-entering the top of coalesce(), which would spin on Redis with no delay
            // for as long as the retry takes.
            case FAILED -> tryTakeOver(inv, true);

            // ABSENT is not an error: the leader holds the lock but has not written the
            // bucket yet. With no local tier this is the common case, not an edge case.
            //
            // Waiters must re-attempt the lock here rather than only re-reading the bucket.
            // If the leader's pod dies mid-execution its lease expires after pendingTtl, and
            // a waiter taking over is the ONLY thing that recovers the already-waiting
            // callers — polling the bucket alone would leave them to time out.
            case ABSENT, PENDING -> tryTakeOver(inv, false);
        });
    }

    /** Attempt to become the leader; if another caller still holds the lock, poll and loop. */
    private Mono<Object> tryTakeOver(Invocation inv, boolean afterFailure) {
        long lockId = LOCK_ID_SEQ.incrementAndGet();
        return coordinator.tryAcquire(inv.key(), lockId, Duration.ofSeconds(inv.ann().pendingTtlSeconds()))
                .flatMap(acquired -> {
                    if (acquired) {
                        if (afterFailure) {
                            metrics.failedRetry();
                        } else {
                            metrics.leaderTakeover();
                        }
                        return runAsLeader(inv, lockId, false);
                    }
                    return waitThenRetry(inv);
                });
    }

    private Mono<Object> waitThenRetry(Invocation inv) {
        return Flux.merge(
                        coordinator.listen(inv.key()).take(1), // fast path: pub/sub wake-up
                        Mono.delay(pollDelay()))               // safety net: pub/sub can be missed
                .next()
                .then(Mono.defer(() -> waitLoop(inv)));
    }

    private Duration pollDelay() {
        return Duration.ofMillis(200 + ThreadLocalRandom.current().nextInt(120)); // jittered
    }

    // ---------- key resolution ----------

    private String resolveKey(ProceedingJoinPoint pjp, Coalesce ann, ContextView ctx) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        HttpHeaders headers = ann.headerKeys().length == 0
                ? HttpHeaders.EMPTY
                : ctx.getOrDefault(HeaderCaptureFilter.CTX_KEY, HttpHeaders.EMPTY);
        return keyResolver.resolve(sig.getMethod(), pjp.getArgs(), ann, headers);
    }

    // ---------- type recovery for deserialization ----------

    private Type payloadType(MethodSignature sig) {
        Method method = Objects.requireNonNull(sig.getMethod(), "method");
        return TYPE_CACHE.computeIfAbsent(method, m -> {
            ResolvableType elem = ResolvableType.forMethodReturnType(m).getGeneric(0); // T in Mono<T>/Flux<T>
            // A Flux<T> is cached as a List<T>, so the decode type has to be the
            // parameterized List<T> — not List.class, which would decode elements as maps.
            return Flux.class.isAssignableFrom(m.getReturnType())
                    ? ResolvableType.forClassWithGenerics(List.class, elem).getType()
                    : elem.getType();
        });
    }
}
