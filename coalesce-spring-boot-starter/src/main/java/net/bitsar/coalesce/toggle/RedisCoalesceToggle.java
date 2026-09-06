package net.bitsar.coalesce.toggle;

import java.util.LinkedHashMap;
import java.util.Map;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The kill switch, held in Redis so one call moves the whole fleet.
 *
 * <p>State is a single Redis hash. The global position lives under a reserved field, and
 * each overridden namespace gets a field of its own, so a check reads the two fields it
 * needs in one round trip rather than one per switch.
 *
 * <p><b>This is read through on every annotated invocation.</b> That is the deliberate
 * trade: every pod always agrees, with no propagation delay and nothing to reconcile, at
 * the cost of one extra Redis round trip per call. A cache hit therefore costs two round
 * trips rather than one. Caching the value locally and invalidating over pub/sub would
 * remove that cost, and the interface is shaped so such an implementation drops straight
 * in without the aspect changing.
 *
 * <p>An unset field means "not configured", not "off": the global position falls back to
 * {@code coalesce.active} and a namespace with no field of its own follows the global
 * switch. Nothing is written at startup, so pods cannot race each other to seed it and a
 * fresh Redis simply behaves as configured.
 *
 * <p>A Redis failure while reading the switch is reported as active. Coalescing needs
 * Redis anyway, so a request that cannot read the switch would fail moments later
 * regardless; treating the error as "on" keeps a Redis outage behaving exactly as it did
 * before the switch existed rather than silently turning the framework into a passthrough.
 * The corollary is that the switch cannot be relied on to be readable during an outage.
 */
public class RedisCoalesceToggle implements CoalesceToggle {

    private static final Logger log = LoggerFactory.getLogger(RedisCoalesceToggle.class);

    /** Reserved field for the global position. Parentheses cannot appear in a namespace. */
    static final String GLOBAL_FIELD = "(global)";

    private final RedissonReactiveClient redisson;
    private final String key;
    private final boolean activeByDefault;

    public RedisCoalesceToggle(RedissonReactiveClient redisson, String key, boolean activeByDefault) {
        this.redisson = redisson;
        this.key = key;
        this.activeByDefault = activeByDefault;
    }

    @Override
    public Mono<Boolean> isActive() {
        return map().get(GLOBAL_FIELD)
                .map(RedisCoalesceToggle::parse)
                .defaultIfEmpty(activeByDefault)
                .onErrorResume(this::assumeActive);
    }

    @Override
    public Mono<Boolean> isActive(String namespace) {
        requireNamespace(namespace);
        // One round trip for both fields. Redisson returns only the fields that exist, so
        // an absent entry falls back rather than reading as false.
        return map().getAll(java.util.Set.of(GLOBAL_FIELD, namespace))
                .map(fields -> {
                    boolean global = fields.containsKey(GLOBAL_FIELD)
                            ? parse(fields.get(GLOBAL_FIELD))
                            : activeByDefault;
                    if (!global) {
                        return false; // the global switch wins
                    }
                    return !fields.containsKey(namespace) || parse(fields.get(namespace));
                })
                .onErrorResume(this::assumeActive);
    }

    @Override
    public Mono<Boolean> setActive(boolean value) {
        return isActive()
                .flatMap(previous -> map().fastPut(GLOBAL_FIELD, Boolean.toString(value))
                        .doOnSuccess(ignored -> {
                            if (previous != value) {
                                log.info("@Coalesce is now {} for every namespace and every pod",
                                        value ? "active" : "bypassed");
                            }
                        })
                        .thenReturn(previous));
    }

    @Override
    public Mono<Boolean> setActive(String namespace, boolean value) {
        requireNamespace(namespace);
        return isActive(namespace)
                .flatMap(previous -> map().fastPut(namespace, Boolean.toString(value))
                        .doOnSuccess(ignored -> {
                            if (previous != value) {
                                log.info("@Coalesce is now {} for namespace {} on every pod",
                                        value ? "active" : "bypassed", namespace);
                            }
                        })
                        .thenReturn(previous));
    }

    @Override
    public Mono<Boolean> clearOverride(String namespace) {
        requireNamespace(namespace);
        return map().fastRemove(namespace)
                .map(removed -> removed > 0)
                .doOnNext(removed -> {
                    if (removed) {
                        log.info("@Coalesce override cleared for namespace {}", namespace);
                    }
                });
    }

    @Override
    public Mono<Map<String, Boolean>> overrides() {
        return map().readAllMap().map(all -> {
            Map<String, Boolean> overrides = new LinkedHashMap<>();
            all.forEach((field, value) -> {
                if (!GLOBAL_FIELD.equals(field)) {
                    overrides.put(field, parse(value));
                }
            });
            return overrides;
        });
    }

    private RMapReactive<String, String> map() {
        return redisson.getMap(key, StringCodec.INSTANCE);
    }

    /**
     * Cannot read the switch, so behave as though it is on: the coalescing that follows
     * will hit the same Redis problem and fail the way it always has.
     */
    private Mono<Boolean> assumeActive(Throwable error) {
        log.warn("could not read the @Coalesce toggle from Redis, assuming active: {}", error.toString());
        return Mono.just(true);
    }

    private static boolean parse(String value) {
        return Boolean.parseBoolean(value);
    }

    private static void requireNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
    }
}
