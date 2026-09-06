package net.bitsar.coalesce.actuate;

import java.util.LinkedHashMap;
import java.util.Map;
import net.bitsar.coalesce.metrics.CoalesceMetrics;
import net.bitsar.coalesce.toggle.CoalesceToggle;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * Reads and flips the runtime kill switch, and reports the framework counters alongside it.
 *
 * <p>{@code GET /actuator/coalesce} returns the current position and the counters.
 * {@code POST /actuator/coalesce} with {@code {"active": false}} takes coalescing out of
 * the path for every annotated method, from the next invocation onwards.
 *
 * <p>The counters travel with the switch deliberately. The question anyone flipping this
 * actually has is whether coalescing is helping, and that is answered by
 * {@code (cacheHits + followerWaits) / requests} rather than by the switch position.
 *
 * <p>Only registered when Actuator is on the classpath AND the endpoint has been exposed,
 * so it stays off unless the application opts in:
 *
 * <pre>
 * management.endpoints.web.exposure.include: coalesce
 * </pre>
 *
 * <p>It is a write endpoint that disables a production safeguard, so secure it as one.
 */
@Endpoint(id = "coalesce")
public class CoalesceEndpoint {

    private final CoalesceToggle toggle;
    private final CoalesceMetrics metrics;

    public CoalesceEndpoint(CoalesceToggle toggle, CoalesceMetrics metrics) {
        this.toggle = toggle;
        this.metrics = metrics;
    }

    @ReadOperation
    public Map<String, Object> status() {
        return describe(toggle.isActive());
    }

    /**
     * @param active true to coalesce, false to call straight through
     * @return the new state, including the position this replaced
     */
    @WriteOperation
    public Map<String, Object> setActive(boolean active) {
        boolean previous = toggle.setActive(active);
        Map<String, Object> body = describe(active);
        body.put("previouslyActive", previous);
        return body;
    }

    private Map<String, Object> describe(boolean active) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", active);
        body.putAll(metrics.snapshot());
        return body;
    }
}
