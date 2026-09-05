package com.example.coalesce.demo;

import com.example.coalesce.metrics.CoalesceMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class OrderController {

    private static final int MIN_BUCKET = 1;
    private static final int MAX_BUCKET = 1000000;

    private final OrderService orders;
    private final DemoMetrics demoMetrics;
    private final CoalesceMetrics coalesceMetrics;

    public OrderController(OrderService orders, DemoMetrics demoMetrics, CoalesceMetrics coalesceMetrics) {
        this.orders = orders;
        this.demoMetrics = demoMetrics;
        this.coalesceMetrics = coalesceMetrics;
    }

    @Tag(name = "Orders")
    @Operation(
            summary = "Fetch a random list of orders",
            description = """
                    The single endpoint under test. `bucket` is 1..10 and doubles as both the \
                    number of orders returned and the coalescing key, so concurrent load \
                    naturally collides on ten keys.

                    The downstream sleeps for a normally distributed service time (~300ms \
                    mean); draws in the slow tail past the 95th percentile — about 5% of \
                    calls — fail with 503 after sleeping.

                    Flip `coalesce` to compare the two paths. Both run identical work, so any \
                    difference in downstream executions comes purely from coalescing. Read the \
                    result at GET /api/stats.""")
    @ApiResponse(responseCode = "200", description = "The orders for this bucket")
    @ApiResponse(responseCode = "400", description = "bucket outside 1..10")
    @ApiResponse(responseCode = "503", description = "Downstream landed in its slow tail")
    @GetMapping("/api/orders/{bucket}")
    public Mono<List<OrderDto>> orders(
            @Parameter(description = "1..10 — order count and coalescing key", example = "3")
            @PathVariable int bucket,
            @Parameter(description = "false to bypass @Coalesce and hit the downstream every time")
            @RequestParam(defaultValue = "true") boolean coalesce) {

        if (bucket < MIN_BUCKET || bucket > MAX_BUCKET) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "bucket must be between 1 and 10, got " + bucket));
        }

        Mode mode = coalesce ? Mode.COALESCED : Mode.DIRECT;
        long startedAt = System.currentTimeMillis();

        return (coalesce ? orders.loadCoalesced(bucket) : orders.loadDirect(bucket))
                .doFinally(signal -> demoMetrics.request(mode, System.currentTimeMillis() - startedAt))
                .onErrorMap(DownstreamUnavailableException.class, e ->
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e));
    }

    @Tag(name = "Metrics")
    @Operation(
            summary = "Compare the two modes",
            description = """
                    `direct` is the baseline: one downstream execution per request, always. \
                    `coalesced` shows how many executions the same request count actually \
                    needed. `comparison` is the headline — downstream load reduction.""")
    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("direct", demoMetrics.snapshot(Mode.DIRECT));
        out.put("coalesced", demoMetrics.snapshot(Mode.COALESCED));
        out.put("comparison", demoMetrics.comparison());
        out.put("framework", coalesceMetrics.snapshot());
        return out;
    }

    @Tag(name = "Metrics")
    @Operation(summary = "Zero all counters before a run")
    @PostMapping("/api/stats/reset")
    public Map<String, String> reset() {
        demoMetrics.reset();
        coalesceMetrics.reset();
        return Map.of("status", "reset");
    }
}
