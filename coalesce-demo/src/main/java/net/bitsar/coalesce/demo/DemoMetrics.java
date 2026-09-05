package net.bitsar.coalesce.demo;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-mode counters, so "how much work did coalescing save" is a number rather than a
 * feeling. {@code requests} counts HTTP calls in; {@code downstreamExecutions} counts how
 * many of those actually reached the expensive method. In DIRECT mode the two are equal by
 * definition — that is the baseline the COALESCED numbers are measured against.
 */
@Component
public class DemoMetrics {

    private final Map<Mode, Counters> byMode = new EnumMap<>(Mode.class);

    public DemoMetrics() {
        for (Mode mode : Mode.values()) {
            byMode.put(mode, new Counters());
        }
    }

    private static final class Counters {
        final AtomicLong requests = new AtomicLong();
        final AtomicLong executions = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicLong latencyMillis = new AtomicLong();

        void reset() {
            requests.set(0);
            executions.set(0);
            failures.set(0);
            latencyMillis.set(0);
        }
    }

    public void request(Mode mode, long latencyMillis) {
        Counters c = byMode.get(mode);
        c.requests.incrementAndGet();
        c.latencyMillis.addAndGet(latencyMillis);
    }

    public void execution(Mode mode) {
        byMode.get(mode).executions.incrementAndGet();
    }

    public void failure(Mode mode) {
        byMode.get(mode).failures.incrementAndGet();
    }

    public Map<String, Object> snapshot(Mode mode) {
        Counters c = byMode.get(mode);
        long requests = c.requests.get();
        long executions = c.executions.get();
        long saved = Math.max(0, requests - executions);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", requests);
        out.put("downstreamExecutions", executions);
        out.put("executionsSaved", saved);
        out.put("savedPercent", requests == 0 ? 0.0 : round(100.0 * saved / requests));
        out.put("downstreamFailures", c.failures.get());
        out.put("meanLatencyMillis", requests == 0 ? 0 : c.latencyMillis.get() / requests);
        return out;
    }

    /** How much less work the downstream did, holding request count constant. */
    public Map<String, Object> comparison() {
        Counters direct = byMode.get(Mode.DIRECT);
        Counters coalesced = byMode.get(Mode.COALESCED);

        long directPerRequest = direct.requests.get();
        long coalescedRequests = coalesced.requests.get();
        long coalescedExecutions = coalesced.executions.get();

        Map<String, Object> out = new LinkedHashMap<>();
        if (directPerRequest == 0 || coalescedRequests == 0) {
            out.put("note", "run both modes, then read this again");
            return out;
        }

        // DIRECT executes once per request, so its execution rate is 1.0 by construction.
        double coalescedRate = (double) coalescedExecutions / coalescedRequests;
        out.put("directExecutionsPerRequest", 1.0);
        out.put("coalescedExecutionsPerRequest", round(coalescedRate));
        out.put("downstreamLoadReductionPercent", round(100.0 * (1.0 - coalescedRate)));
        out.put("timesFewerExecutions", coalescedRate == 0 ? "infinite" : round(1.0 / coalescedRate) + "x");
        out.put("meanLatencyDirectMillis", direct.requests.get() == 0 ? 0 : direct.latencyMillis.get() / direct.requests.get());
        out.put("meanLatencyCoalescedMillis", coalesced.latencyMillis.get() / coalescedRequests);
        return out;
    }

    public void reset() {
        byMode.values().forEach(Counters::reset);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
