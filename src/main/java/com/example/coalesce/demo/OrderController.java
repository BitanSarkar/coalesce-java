package com.example.coalesce.demo;

import com.example.coalesce.metrics.CoalesceMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class OrderController {

    private final OrderService orders;
    private final CoalesceMetrics metrics;

    public OrderController(OrderService orders, CoalesceMetrics metrics) {
        this.orders = orders;
        this.metrics = metrics;
    }

    @Tag(name = "Orders")
    @Operation(
            summary = "Coalesced order read",
            description = """
                    Backed by a ~400ms fake downstream. Fire many concurrent requests at the same \
                    id and the downstream runs exactly once — everyone else waits on the leader \
                    and shares its result.

                    freshTtl=5s, staleTtl=120s: a read more than 5s after the last write is still \
                    answered instantly, and triggers one background refresh.

                    X-Tenant-Id is folded into the coalescing key, so two tenants asking for the \
                    same order id do not share a result.""")
    @ApiResponse(responseCode = "200", description = "The order, freshly executed or from cache")
    @ApiResponse(responseCode = "500", description = "Downstream failed (see POST /coalesce/failing)",
            content = @Content)
    @GetMapping("/orders/{id}")
    public Mono<OrderDto> get(
            @Parameter(description = "Order id — this is the coalescing key", example = "A-1")
            @PathVariable String id) {
        return orders.getOrder(id);
    }

    @Tag(name = "Orders")
    @Operation(
            summary = "Coalesced item list (bounded Flux)",
            description = """
                    The Flux path: the leader collects the whole stream into a List before caching, \
                    so this is for bounded streams only — there is no live multicast of an \
                    unbounded stream across pods.""")
    @GetMapping("/orders/{id}/items")
    public Flux<String> items(
            @Parameter(description = "Order id", example = "A-1") @PathVariable String id) {
        return orders.listItems(id);
    }

    @Tag(name = "Orders")
    @Operation(
            summary = "Coalesced read that returns nothing",
            description = """
                    Exercises the empty-Mono path: an empty result is a legitimate answer and gets \
                    cached as one, so later callers get an immediate empty response rather than \
                    waiting for a value that will never arrive.""")
    @ApiResponse(responseCode = "200", description = "Empty body — the cached 'no such order'")
    @GetMapping("/orders/{id}/maybe")
    public Mono<OrderDto> maybe(
            @Parameter(description = "Order id", example = "C-1") @PathVariable String id) {
        return orders.findMissingOrder(id);
    }

    @Tag(name = "Orders")
    @Operation(
            summary = "Crash-recovery demo (pendingTtl = 3s)",
            description = """
                    Call POST /coalesce/hang-once first. The next caller to win the lock never \
                    completes, simulating a pod dying mid-execution — it never writes a result and \
                    never releases the lock.

                    Concurrent callers cannot be woken by pub/sub, because nothing is ever \
                    published. Recovery comes from the lock lease expiring after pendingTtlSeconds, \
                    at which point exactly one waiter takes over and the rest share its result. \
                    Watch leaderTakeovers in /coalesce/stats.""")
    @GetMapping("/orders/{id}/flaky")
    public Mono<OrderDto> flaky(
            @Parameter(description = "Order id", example = "F-1") @PathVariable String id) {
        return orders.flakyOrder(id);
    }

    @Tag(name = "Coalesce control")
    @Operation(
            summary = "Framework counters",
            description = """
                    downstreamExecutions is how often the annotated method body actually ran; \
                    everything else is what the framework did around it. The ratio of \
                    leaderExecutions to cacheHits and followerWaits is the whole point — if they \
                    are equal, nothing is being coalesced.""")
    @GetMapping("/coalesce/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("downstreamExecutions", orders.executions());
        out.putAll(metrics.snapshot());
        return out;
    }

    @Tag(name = "Coalesce control")
    @Operation(summary = "Reset all counters and fault injection")
    @PostMapping("/coalesce/reset")
    public Map<String, String> reset() {
        orders.reset();
        metrics.reset();
        return Map.of("status", "reset");
    }

    @Tag(name = "Coalesce control")
    @Operation(
            summary = "Make the fake downstream fail",
            description = """
                    With this on, a cold call errors and the failure is cached as FAILED. Turn it \
                    back off and fire several concurrent requests: exactly one retries and the \
                    rest share its result, rather than every caller retrying independently.

                    Note there is deliberately no backoff between retries — retry policy and \
                    circuit breaking are a separate concern from coalescing.""")
    @PostMapping("/coalesce/failing")
    public Map<String, Boolean> failing(
            @Parameter(description = "true to make the downstream throw") @RequestParam boolean enabled) {
        orders.failing(enabled);
        return Map.of("failing", enabled);
    }

    @Tag(name = "Coalesce control")
    @Operation(
            summary = "Arm the simulated crash",
            description = "The next leader to win the lock hangs forever. Then call GET /orders/{id}/flaky.")
    @ApiResponse(responseCode = "200",
            content = @Content(schema = @Schema(example = "{\"status\":\"next leader will hang\"}")))
    @PostMapping("/coalesce/hang-once")
    public Map<String, String> hangOnce() {
        orders.hangOnce();
        return Map.of("status", "next leader will hang");
    }
}
