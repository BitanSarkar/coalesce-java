package net.bitsar.coalesce.toggle;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime kill switch for {@code @Coalesce}, either for everything at once or for one
 * namespace at a time.
 *
 * <p>Turning coalescing off makes annotated methods behave as if the annotation were not
 * there: the aspect calls straight through to the target and nothing touches Redis, not
 * even to read. It takes effect on the next invocation, with no restart and no
 * reconfiguration, which is the point. A coalescing layer sits in front of a dependency
 * precisely when that dependency is in trouble, and that is the worst moment to need a
 * deploy to get out of the way.
 *
 * <p>Usually only one dependency is sick, so switching the whole application off is a
 * blunt instrument: it would also strip the shield from every healthy downstream that is
 * currently being protected. A namespace override takes one method out of the path and
 * leaves the rest coalescing:
 *
 * <pre>
 * toggle.setActive("OrderService.getOrder", false);   // just this one
 * toggle.setActive(false);                            // everything, the big red button
 * </pre>
 *
 * <p>The namespace is the same string that names the method's Redis entries: the
 * {@code namespace} attribute when set, otherwise {@code ClassSimpleName.methodName}.
 *
 * <p>The global switch wins. While it is off, everything is bypassed regardless of any
 * override, so the big red button cannot be undermined by a stale per-method setting.
 *
 * <p>Distinct from {@code coalesce.enabled}, which decides at startup whether any of this
 * is wired at all. That one removes the beans; this one is a switch on beans that exist.
 * With {@code coalesce.enabled=false} there is no aspect and nothing to toggle.
 *
 * <p>The initial global position comes from {@code coalesce.active}, which defaults to
 * true. Safe for concurrent use from any thread.
 */
public class CoalesceToggle {

    private static final Logger log = LoggerFactory.getLogger(CoalesceToggle.class);

    private final AtomicBoolean globallyActive;

    /** Only namespaces that have been overridden; absent means "follow the global switch". */
    private final Map<String, Boolean> overrides = new ConcurrentHashMap<>();

    public CoalesceToggle(boolean initiallyActive) {
        this.globallyActive = new AtomicBoolean(initiallyActive);
    }

    /** Whether coalescing is on at all, ignoring any per-namespace override. */
    public boolean isActive() {
        return globallyActive.get();
    }

    /**
     * Whether the given namespace should coalesce. Checked once per annotated invocation,
     * so it stays a volatile read plus, at most, one hash lookup.
     *
     * @param namespace the effective namespace, as it appears in the Redis key
     */
    public boolean isActive(String namespace) {
        if (!globallyActive.get()) {
            return false;
        }
        return overrides.getOrDefault(namespace, Boolean.TRUE);
    }

    /**
     * @param value true to coalesce, false to call straight through
     * @return the position this replaced
     */
    public boolean setActive(boolean value) {
        boolean previous = globallyActive.getAndSet(value);
        if (previous != value) {
            // Worth a line at INFO: someone flipping this during an incident wants it in
            // the same log they are already reading.
            log.info("@Coalesce is now {} for every namespace",
                    value ? "active" : "bypassed; annotated methods call through directly");
        }
        return previous;
    }

    /**
     * Override one namespace, leaving every other one alone.
     *
     * @return the position this replaced, which is the global one when the namespace had
     *         no override of its own
     */
    public boolean setActive(String namespace, boolean value) {
        Boolean previous = overrides.put(requireNamespace(namespace), value);
        boolean previouslyActive = previous == null ? globallyActive.get() : previous;
        if (previouslyActive != value) {
            log.info("@Coalesce is now {} for namespace {}", value ? "active" : "bypassed", namespace);
        }
        return previouslyActive;
    }

    /** Drop an override so the namespace follows the global switch again. */
    public boolean clearOverride(String namespace) {
        boolean removed = overrides.remove(requireNamespace(namespace)) != null;
        if (removed) {
            log.info("@Coalesce override cleared for namespace {}", namespace);
        }
        return removed;
    }

    /** Every namespace currently overridden, for reporting. Never null. */
    public Map<String, Boolean> overrides() {
        return Collections.unmodifiableMap(overrides);
    }

    private static String requireNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        return namespace;
    }
}
