package net.bitsar.coalesce.toggle;

import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Runtime kill switch for {@code @Coalesce}, either for everything at once or for one
 * namespace at a time.
 *
 * <p>Turning coalescing off makes annotated methods behave as if the annotation were not
 * there: the aspect calls straight through to the target and nothing else touches Redis.
 * It takes effect on the next invocation, with no restart and no reconfiguration, which is
 * the point. A coalescing layer sits in front of a dependency precisely when that
 * dependency is in trouble, and that is the worst moment to need a deploy to get out of
 * the way.
 *
 * <p>Usually only one dependency is sick, so switching the whole application off is a
 * blunt instrument: it would also strip the shield from every healthy downstream that is
 * currently being protected. A namespace override takes one method out of the path and
 * leaves the rest coalescing. The namespace is the same string that names the method's
 * Redis entries: the {@code namespace} attribute when set, otherwise the method's full
 * signature.
 *
 * <p>The global switch wins. While it is off, everything is bypassed regardless of any
 * override, so the big red button cannot be undermined by a stale per-method setting.
 *
 * <p><b>Every operation is reactive because the state is shared, not per-pod.</b> A switch
 * held in a field would only affect the one pod whose endpoint happened to be reached
 * through the load balancer, leaving every other pod coalescing and the operator believing
 * otherwise. See {@link RedisCoalesceToggle}.
 *
 * <p>Distinct from {@code coalesce.enabled}, which decides at startup whether any of this
 * is wired at all. That one removes the beans; this one is a switch on beans that exist.
 */
public interface CoalesceToggle {

    /** Whether coalescing is on at all, ignoring any per-namespace override. */
    Mono<Boolean> isActive();

    /**
     * Whether the given namespace should coalesce, taking both the global switch and any
     * override into account.
     *
     * @param namespace the effective namespace, as it appears in the Redis key
     */
    Mono<Boolean> isActive(String namespace);

    /**
     * @param value true to coalesce, false to call straight through
     * @return the position this replaced
     */
    Mono<Boolean> setActive(boolean value);

    /**
     * Override one namespace, leaving every other one alone.
     *
     * @return the position this replaced, which is the global one when the namespace had
     *         no override of its own
     */
    Mono<Boolean> setActive(String namespace, boolean value);

    /** Drop an override so the namespace follows the global switch again. */
    Mono<Boolean> clearOverride(String namespace);

    /** Every namespace currently overridden, for reporting. */
    Mono<Map<String, Boolean>> overrides();
}
