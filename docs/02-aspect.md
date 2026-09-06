# The aspect

This is the consolidated, final version reflecting every fix discussed:
bucket-checked-before-lock, `lockId` instead of thread id, `ABSENT` handled like
`PENDING`, and the stale-while-revalidate branch.

```java
package net.bitsar.coalesce;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Aspect
@Component
public class CoalesceAspect {

    private final RedissonCoalesceCoordinator coordinator;
    private final CoalesceCodec codec;
    private final ObjectMapper mapper; // only used by ResolvableType/JavaType resolution
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer paramNames = new DefaultParameterNameDiscoverer();
    private static final AtomicLong LOCK_ID_SEQ = new AtomicLong();
    private static final Map<Method, JavaType> TYPE_CACHE = new ConcurrentHashMap<>();
    private static final byte[] EMPTY_MARKER = new byte[0];

    public CoalesceAspect(RedissonCoalesceCoordinator coordinator, CoalesceCodec codec, ObjectMapper mapper) {
        this.coordinator = coordinator;
        this.codec = codec;
        this.mapper = mapper;
    }

    // ---------- entry point ----------

    @Around("@annotation(coalesce)")
    public Object around(ProceedingJoinPoint pjp, Coalesce coalesce) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Class<?> returnType = sig.getMethod().getReturnType();

        if (Flux.class.isAssignableFrom(returnType)) {
            return Flux.deferContextual(ctx -> {
                String key = resolveKey(pjp, coalesce, ctx);
                return coalesce(pjp, coalesce, key)
                    .flatMapMany(list -> Flux.fromIterable((List<Object>) list));
            });
        }
        if (Mono.class.isAssignableFrom(returnType)) {
            return Mono.deferContextual(ctx -> coalesce(pjp, coalesce, resolveKey(pjp, coalesce, ctx)));
        }
        throw new IllegalStateException("@Coalesce only supports Mono/Flux return types");
    }

    // ---------- the core rule: check the bucket BEFORE ever touching the lock ----------

    private Mono<Object> coalesce(ProceedingJoinPoint pjp, Coalesce ann, String key) {
        return coordinator.fetchState(key).flatMap(state -> switch (state.status()) {
            case DONE -> serveDone(pjp, ann, key, state);
            case FAILED, ABSENT, PENDING -> acquireAndExecute(pjp, ann, key, false);
        });
    }

    // ---------- stale-while-revalidate ----------

    private Mono<Object> serveDone(ProceedingJoinPoint pjp, Coalesce ann, String key, CoalesceState state) {
        Object result = decode(pjp, state);
        long ageMillis = System.currentTimeMillis() - state.computedAt();

        if (ageMillis < ann.freshTtlSeconds() * 1000L) {
            return Mono.just(result); // fresh: nothing else happens
        }

        // stale: kick off a refresh attempt off the response path, don't make this caller wait
        acquireAndExecute(pjp, ann, key, true)
            .subscribe(v -> {}, err -> { /* log at WARN: background refresh failed for {key} */ });

        return Mono.just(result);
    }

    private Object decode(ProceedingJoinPoint pjp, CoalesceState state) {
        if (state.payload().length == 0) return null; // legitimate empty Mono result
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        return codec.decode(state.payload(), payloadType(sig));
    }

    // ---------- lock acquisition ----------

    private Mono<Object> acquireAndExecute(ProceedingJoinPoint pjp, Coalesce ann, String key, boolean isRefresh) {
        long lockId = LOCK_ID_SEQ.incrementAndGet();
        Duration pendingTtl = Duration.ofSeconds(ann.pendingTtlSeconds());

        return coordinator.tryAcquire(key, lockId, pendingTtl)
            .flatMap(acquired -> acquired
                ? runAsLeader(pjp, ann, key, lockId, isRefresh)
                : (isRefresh ? Mono.empty() : awaitAsFollower(pjp, ann, key)));
                // losing the race during a background refresh is a no-op, not a wait:
                // someone else is already refreshing, and the caller already got a response
    }

    private Mono<Object> runAsLeader(ProceedingJoinPoint pjp, Coalesce ann, String key, long lockId, boolean isRefresh) {
        Duration ttl = Duration.ofSeconds(ann.staleTtlSeconds());

        return Mono.defer(() -> {
                    try { return (Mono<Object>) pjp.proceed(); }
                    catch (Throwable t) { return Mono.error(t); }
                })
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty()) // force a signal even for Mono.empty()
                .flatMap(opt -> {
                    byte[] payload = opt.map(codec::encode).orElse(EMPTY_MARKER);
                    return coordinator.markDone(key, payload, ttl).thenReturn(opt.orElse(null));
                })
                .onErrorResume(err -> isRefresh
                    ? Mono.error(err) // background refresh failed: leave the last good value in place
                    : coordinator.markFailed(key, err, ttl).then(Mono.error(err))) // cold start: nothing to fall back on
                .doFinally(sig -> coordinator.release(key, lockId).subscribe());
    }

    // ---------- follower wait loop ----------

    private Mono<Object> awaitAsFollower(ProceedingJoinPoint pjp, Coalesce ann, String key) {
        return waitLoop(pjp, ann, key)
            .timeout(Duration.ofSeconds(ann.waitTimeoutSeconds()), Mono.error(new CoalesceTimeoutException(key)));
    }

    private Mono<Object> waitLoop(ProceedingJoinPoint pjp, Coalesce ann, String key) {
        return coordinator.fetchState(key).flatMap(state -> switch (state.status()) {

            case DONE -> Mono.just(decode(pjp, state));

            case FAILED -> coalesce(pjp, ann, key); // re-enters at the top; races tryLock with a fresh lockId

            case ABSENT, PENDING -> Flux.merge(
                        coordinator.listen(key).take(1),   // fast path: pub/sub wake-up
                        Mono.delay(pollDelay()))            // safety net: pub/sub is fire-and-forget, can be missed
                    .next()
                    .then(Mono.defer(() -> waitLoop(pjp, ann, key)));
        });
    }

    private Duration pollDelay() {
        return Duration.ofMillis(200 + ThreadLocalRandom.current().nextInt(120)); // jittered, avoids thundering herd
    }

    // ---------- key resolution ----------

    private String resolveKey(ProceedingJoinPoint pjp, Coalesce ann, ContextView ctx) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        StandardEvaluationContext spelCtx = new StandardEvaluationContext();

        String[] names = paramNames.getParameterNames(sig.getMethod()); // NOT sig.getParameterNames(), see note below
        Object[] args = pjp.getArgs();
        for (int i = 0; i < names.length; i++) spelCtx.setVariable(names[i], args[i]);

        String base = parser.parseExpression(ann.key()).getValue(spelCtx, String.class);

        String headerPart = "";
        if (ann.headerKeys().length > 0) {
            HttpHeaders headers = ctx.getOrDefault(HeaderCaptureFilter.CTX_KEY, HttpHeaders.EMPTY);
            headerPart = Arrays.stream(ann.headerKeys())
                .sorted()
                .map(h -> h + "=" + Optional.ofNullable(headers.getFirst(h)).orElse(""))
                .collect(Collectors.joining("|"));
        }

        String namespace = ann.namespace().isEmpty()
            ? sig.getDeclaringType().getSimpleName() + "." + sig.getName()
            : ann.namespace();

        String raw = namespace + ":" + base + (headerPart.isEmpty() ? "" : ":" + headerPart);
        // {} hash tag is REQUIRED for Redis Cluster, see 05-cluster-considerations.md
        return "coalesce:{" + raw + "}";
    }

    // ---------- type recovery for deserialization ----------

    private JavaType payloadType(MethodSignature sig) {
        return TYPE_CACHE.computeIfAbsent(sig.getMethod(), m -> {
            ResolvableType rt = ResolvableType.forMethodReturnType(m);
            ResolvableType elem = rt.getGeneric(0); // the T in Mono<T> / Flux<T>
            JavaType elemType = mapper.getTypeFactory().constructType(elem.getType());
            return Flux.class.isAssignableFrom(m.getReturnType())
                ? mapper.getTypeFactory().constructCollectionType(List.class, elemType) // Flux<T> stores as List<T>
                : elemType;
        });
    }
}
```

## Notes on things that are easy to get wrong

- **`paramNames.getParameterNames(sig.getMethod())`, not `sig.getParameterNames()`.**
  The AspectJ signature's method silently returns unusable names unless the project
  was compiled with `-parameters`. `DefaultParameterNameDiscoverer` is more reliable.
- **`doFinally`, not `.flatMap` after success.** The lock must release on both the
  success and error paths, or a failing leader holds the lock for the full
  `pendingTtlSeconds` for no reason.
- **`lockId`, never `Thread.currentThread().getId()`.** WebFlux hops event-loop
  threads between reactive operators; by the time `doFinally` runs you are very
  likely on a different thread than the one that acquired the lock. Redisson's
  `unlock` requires the exact id used at `tryLock`, or it throws
  `IllegalMonitorStateException` and the lock sits until its lease expires.
- **`ABSENT` must be handled exactly like `PENDING`.** Without a local tier, this is
  hit constantly: a follower's `tryLock` fails, but the leader hasn't written the
  bucket yet. This is normal, not an error condition.
