package net.bitsar.coalesce.actuate;

import java.util.LinkedHashMap;
import java.util.Map;
import net.bitsar.coalesce.metrics.CoalesceMetrics;
import net.bitsar.coalesce.toggle.CoalesceToggle;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import reactor.core.publisher.Mono;

/**
 * Reads and flips the runtime kill switch, and reports the framework counters alongside it.
 *
 * <p>{@code GET /actuator/coalesce} returns the current position, any per-namespace
 * overrides, and the counters. {@code POST /actuator/coalesce} with
 * {@code {"active": false}} takes coalescing out of the path for every annotated method
 * from the next invocation onwards; adding {@code "namespace"} scopes that to one method,
 * which is usually what an incident calls for, since normally one dependency is sick
 * rather than all of them:
 *
 * <pre>
 * {"active": false, "namespace": "OrderService.getOrder"}
 * </pre>
 *
 * <p>Send {@code {"namespace": "...", "active": null}} to drop an override and let the
 * namespace follow the global switch again.
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
    public Mono<Map<String, Object>> status() {
        return describe();
    }

    /**
     * @param active    true to coalesce, false to call straight through. Null is only
     *                  meaningful together with a namespace, where it drops the override.
     * @param namespace the namespace to scope this to, or null for the global switch
     * @return the new state, including the position this replaced
     */
    @WriteOperation
    public Mono<Map<String, Object>> setActive(Boolean active, String namespace) {
        if (namespace == null || namespace.isBlank()) {
            if (active == null) {
                return Mono.error(new IllegalArgumentException(
                        "active is required when no namespace is given"));
            }
            return toggle.setActive(active)
                    .flatMap(previous -> describe().doOnNext(body -> body.put("previouslyActive", previous)));
        }
        if (active == null) {
            return toggle.clearOverride(namespace)
                    .flatMap(removed -> describe().doOnNext(body -> {
                        body.put("namespace", namespace);
                        body.put("overrideCleared", removed);
                    }));
        }
        return toggle.setActive(namespace, active)
                .flatMap(previous -> describe().doOnNext(body -> {
                    body.put("namespace", namespace);
                    body.put("previouslyActive", previous);
                }));
    }

    private Mono<Map<String, Object>> describe() {
        return Mono.zip(toggle.isActive(), toggle.overrides()).map(both -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("active", both.getT1());
            body.put("overrides", both.getT2());
            body.putAll(metrics.snapshot());
            return body;
        });
    }
}
