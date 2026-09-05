package com.example.coalesce.integration;

import com.example.coalesce.coordinator.RedissonCoalesceCoordinator;
import com.example.coalesce.demo.OrderDto;
import com.example.coalesce.demo.OrderService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pub/sub is fire-and-forget: a wake-up can be dropped. With the topic listener stubbed out
 * entirely, the jittered poll in the follower wait loop has to resolve the wait on its own.
 */
@SpringBootTest
@EnabledIf("com.example.coalesce.integration.RedisAvailable#check")
class MissedNotificationTest {

    @Autowired
    OrderService orders;

    @Test
    void followersStillResolveWhenEveryWakeUpIsLost() {
        orders.reset();
        String id = "missed-" + UUID.randomUUID();

        long startedAt = System.currentTimeMillis();
        List<OrderDto> results = Flux.range(0, 8)
                .flatMap(i -> orders.getOrder(id))
                .collectList()
                .block(Duration.ofSeconds(30));
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(results).hasSize(8);
        assertThat(orders.executions()).isEqualTo(1);
        assertThat(results.stream().map(OrderDto::fetchedAt).distinct().toList()).hasSize(1);

        // The downstream takes ~400ms; polling adds at most a poll interval or two on top.
        // The point is that it resolves well inside waitTimeoutSeconds rather than hanging.
        assertThat(elapsed).isLessThan(3_000);
    }

    @TestConfiguration
    static class DeafCoordinatorConfig {

        @Bean
        @Primary
        RedissonCoalesceCoordinator deafCoordinator(RedissonReactiveClient redisson) {
            return new RedissonCoalesceCoordinator(redisson) {
                @Override
                public Flux<String> listen(String key) {
                    return Flux.never(); // every notification is lost
                }
            };
        }
    }
}
