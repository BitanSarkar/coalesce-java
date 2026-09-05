package net.bitsar.coalesce.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stands in for a slow, expensive, occasionally failing downstream.
 *
 * <p>Service time is drawn from a normal distribution. A draw landing in the slow tail —
 * beyond the 95th percentile, so ~5% of calls — sleeps for its full duration and then
 * fails, which is how a real dependency behaves when it is struggling: the failures are
 * the slow calls, not a uniform random sprinkle.
 *
 * <p>The sleep is a real blocking sleep, as requested, but it runs on the boundedElastic
 * scheduler rather than an event-loop thread. Blocking Netty's event loop would stall
 * every other in-flight request and make a throughput comparison meaningless.
 */
@Component
public class SimulatedOrderSource {

    private static final Logger log = LoggerFactory.getLogger(SimulatedOrderSource.class);

    /** z-score for the 95th percentile of a normal distribution. */
    private static final double Z_95 = 1.6448536269514722;

    private static final String[] CUSTOMERS = {"acme", "globex", "initech", "umbrella", "hooli"};
    private static final String[] STATUSES = {"CONFIRMED", "PENDING", "SHIPPED", "CANCELLED"};

    private final int maxOrders;
    private final long meanMillis;
    private final long stdDevMillis;
    private final long maxMillis;
    private final double failureThresholdMillis;
    private final DemoMetrics metrics;

    public SimulatedOrderSource(
            @Value("${demo.max-orders:25}") int maxOrders,
            @Value("${demo.latency.mean-millis:300}") long meanMillis,
            @Value("${demo.latency.std-dev-millis:80}") long stdDevMillis,
            @Value("${demo.latency.max-millis:2000}") long maxMillis,
            DemoMetrics metrics) {
        this.maxOrders = maxOrders;
        this.meanMillis = meanMillis;
        this.stdDevMillis = stdDevMillis;
        this.maxMillis = maxMillis;
        this.failureThresholdMillis = meanMillis + Z_95 * stdDevMillis;
        this.metrics = metrics;
    }

    /**
     * One actual unit of downstream work. Every invocation of this method is work the
     * coalescing layer failed to save, which is exactly what the counters measure.
     */
    public Mono<List<OrderDto>> fetch(int bucket, Mode mode) {
        return Mono.fromCallable(() -> {
            metrics.execution(mode);

            double draw = meanMillis + stdDevMillis * ThreadLocalRandom.current().nextGaussian();
            long sleepMillis = (long) Math.max(0, Math.min(draw, maxMillis));
            Thread.sleep(sleepMillis);

            if (draw > failureThresholdMillis) {
                metrics.failure(mode);
                log.debug("bucket {} landed in the slow tail at {}ms, failing", bucket, sleepMillis);
                throw new DownstreamUnavailableException(
                        "downstream exceeded its latency budget (" + sleepMillis + "ms)");
            }
            return generate(bucket);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * {@code bucket} sets the order count so each bucket has a distinct payload, but the
     * count is capped independently of the bucket range.
     *
     * <p>Without that cap, widening the bucket range to raise key cardinality also inflates
     * every response: bucket 964,900 meant 964,900 orders, ~127MB of JSON per entry, which
     * exhausted Netty's direct buffer arena on the way into Redis.
     */
    private List<OrderDto> generate(int bucket) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int count = Math.min(bucket, maxOrders);
        List<OrderDto> orders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            orders.add(new OrderDto(
                    "ORD-%d-%04d".formatted(bucket, random.nextInt(10_000)),
                    CUSTOMERS[random.nextInt(CUSTOMERS.length)],
                    STATUSES[random.nextInt(STATUSES.length)],
                    random.nextLong(500, 250_000),
                    Instant.now().minus(random.nextInt(72), ChronoUnit.HOURS)));
        }
        return orders;
    }
}
