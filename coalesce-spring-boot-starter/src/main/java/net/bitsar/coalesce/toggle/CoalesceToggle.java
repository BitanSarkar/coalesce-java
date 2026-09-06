package net.bitsar.coalesce.toggle;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime kill switch for every {@code @Coalesce} method in the application.
 *
 * <p>Turning this off makes annotated methods behave exactly as if the annotation were not
 * there: the aspect calls straight through to the target and nothing touches Redis, not
 * even to read. It takes effect on the next invocation, with no restart and no
 * reconfiguration, which is the point. A coalescing layer sits in front of a dependency
 * precisely when that dependency is in trouble, and that is the worst moment to need a
 * deploy to get out of the way.
 *
 * <p>Distinct from {@code coalesce.enabled}, which decides at startup whether any of this
 * is wired at all. That one removes the beans; this one is a switch on beans that exist.
 * With {@code coalesce.enabled=false} there is no aspect and nothing to toggle.
 *
 * <p>The initial position comes from {@code coalesce.active}, which defaults to true.
 */
public class CoalesceToggle {

    private static final Logger log = LoggerFactory.getLogger(CoalesceToggle.class);

    private final AtomicBoolean active;

    public CoalesceToggle(boolean initiallyActive) {
        this.active = new AtomicBoolean(initiallyActive);
    }

    /** Checked once per annotated invocation, so it stays a plain volatile read. */
    public boolean isActive() {
        return active.get();
    }

    /**
     * @param value true to coalesce, false to call straight through
     * @return the position this replaced
     */
    public boolean setActive(boolean value) {
        boolean previous = active.getAndSet(value);
        if (previous != value) {
            // Worth a line at INFO: someone flipping this during an incident wants it in
            // the same log they are already reading.
            log.info("@Coalesce is now {}", value ? "active" : "bypassed; annotated methods call through directly");
        }
        return previous;
    }
}
