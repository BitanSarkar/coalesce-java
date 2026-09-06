package net.bitsar.coalesce.autoconfigure;

import java.lang.reflect.Type;
import net.bitsar.coalesce.aspect.CoalesceAspect;
import net.bitsar.coalesce.aspect.CoalesceKeyResolver;
import net.bitsar.coalesce.codec.CoalesceCodec;
import net.bitsar.coalesce.codec.JsonCoalesceCodec;
import net.bitsar.coalesce.coordinator.CoalesceCoordinator;
import net.bitsar.coalesce.coordinator.RedissonCoalesceCoordinator;
import net.bitsar.coalesce.actuate.CoalesceEndpoint;
import net.bitsar.coalesce.metrics.CoalesceMetrics;
import net.bitsar.coalesce.toggle.CoalesceToggle;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The library contributes no {@code @Component}, so a consumer that does not scan
 * {@code net.bitsar.coalesce} gets its beans purely from these auto-configurations. If they
 * stop firing, {@code @Coalesce} silently becomes a no-op — the annotated method still runs,
 * just without any coalescing — which is exactly the failure a unit test has to catch.
 *
 * <p>Redisson is mocked throughout: none of this needs a reachable Redis, and creating a
 * real client here would make the test depend on one.
 */
class CoalesceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    CoalesceRedissonAutoConfiguration.class,
                    CoalesceAutoConfiguration.class))
            .withUserConfiguration(MockRedissonConfig.class);

    @Test
    void wiresTheWholeStackWithoutComponentScanning() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(CoalesceAspect.class)
                .hasSingleBean(CoalesceCoordinator.class)
                .hasSingleBean(CoalesceKeyResolver.class)
                .hasSingleBean(CoalesceMetrics.class)
                .hasSingleBean(CoalesceToggle.class)
                .hasSingleBean(CoalesceCodec.class));
    }

    // ---------- the runtime kill switch ----------

    @Test
    void theToggleStartsActive() {
        runner.run(context -> assertThat(context.getBean(CoalesceToggle.class).isActive()).isTrue());
    }

    /** Starting bypassed still wires everything, so it can be switched on without a restart. */
    @Test
    void coalesceActiveFalseStartsBypassedButFullyWired() {
        runner.withPropertyValues("coalesce.active=false").run(context -> {
            assertThat(context).hasSingleBean(CoalesceAspect.class);
            assertThat(context.getBean(CoalesceToggle.class).isActive()).isFalse();
        });
    }

    /** coalesce.enabled=false removes the beans, so there is nothing left to toggle. */
    @Test
    void disabledEntirelyLeavesNoToggle() {
        runner.withPropertyValues("coalesce.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CoalesceToggle.class));
    }

    @Test
    void anApplicationCanSupplyItsOwnToggle() {
        runner.withUserConfiguration(CustomToggleConfig.class).run(context -> {
            assertThat(context).hasSingleBean(CoalesceToggle.class);
            assertThat(context.getBean(CoalesceToggle.class).isActive()).isFalse();
        });
    }

    // ---------- the Actuator endpoint ----------

    /**
     * A reactive web context, because that is the only kind this library runs in and
     * because web exposure is what @ConditionalOnAvailableEndpoint is deciding against.
     * Actuator is on the test classpath here, so Boot's exposure gate is all that is left.
     */
    private final ReactiveWebApplicationContextRunner actuatorRunner =
            new ReactiveWebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            CoalesceRedissonAutoConfiguration.class,
                            CoalesceAutoConfiguration.class,
                            CoalesceActuatorAutoConfiguration.class,
                            EndpointAutoConfiguration.class,
                            WebEndpointAutoConfiguration.class))
                    .withUserConfiguration(MockRedissonConfig.class);

    /** Actuator on the classpath is not enough: nothing is exposed by default. */
    @Test
    void theEndpointStaysOffUntilItIsExposed() {
        actuatorRunner.run(context -> assertThat(context).doesNotHaveBean(CoalesceEndpoint.class));
    }

    @Test
    void theEndpointAppearsOnceExposed() {
        actuatorRunner.withPropertyValues("management.endpoints.web.exposure.include=coalesce")
                .run(context -> assertThat(context).hasSingleBean(CoalesceEndpoint.class));
    }

    /** The endpoint reads and writes the same switch the aspect consults. */
    @Test
    void theEndpointFlipsTheToggleTheAspectUses() {
        actuatorRunner.withPropertyValues("management.endpoints.web.exposure.include=coalesce")
                .run(context -> {
                    CoalesceEndpoint endpoint = context.getBean(CoalesceEndpoint.class);
                    CoalesceToggle toggle = context.getBean(CoalesceToggle.class);

                    assertThat(endpoint.status()).containsEntry("active", true);

                    assertThat(endpoint.setActive(false, null))
                            .containsEntry("active", false)
                            .containsEntry("previouslyActive", true);
                    assertThat(toggle.isActive()).isFalse();

                    assertThat(endpoint.status())
                            .containsEntry("active", false)
                            .containsKey("cacheHits")
                            .containsKey("bypassed");

                    // Back on globally, then scoped to a single namespace.
                    endpoint.setActive(true, null);
                    assertThat(endpoint.setActive(false, "OrderService.getOrder"))
                            .containsEntry("active", true)
                            .containsEntry("namespace", "OrderService.getOrder");
                    assertThat(toggle.isActive("OrderService.getOrder")).isFalse();
                    assertThat(toggle.isActive("PriceService.quote")).isTrue();

                    // A null active with a namespace drops the override.
                    assertThat(endpoint.setActive(null, "OrderService.getOrder"))
                            .containsEntry("overrideCleared", true);
                    assertThat(toggle.isActive("OrderService.getOrder")).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomToggleConfig {
        @Bean
        CoalesceToggle coalesceToggle() {
            return new CoalesceToggle(false);
        }
    }

    @Test
    void backsOffEntirelyWhenDisabled() {
        runner.withPropertyValues("coalesce.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CoalesceAspect.class)
                        .doesNotHaveBean(CoalesceCoordinator.class));
    }

    @Test
    void maxPayloadBytesIsBoundFromProperties() {
        runner.withPropertyValues("coalesce.max-payload-bytes=2048")
                .run(context -> assertThat(context.getBean(CoalesceProperties.class).getMaxPayloadBytes())
                        .isEqualTo(2048));
    }

    @Test
    void anApplicationSuppliedRedissonClientIsUsedAsIs() {
        RedissonClient own = Mockito.mock(RedissonClient.class);
        RedissonReactiveClient ownReactive = Mockito.mock(RedissonReactiveClient.class);
        Mockito.when(own.reactive()).thenReturn(ownReactive);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        CoalesceRedissonAutoConfiguration.class,
                        CoalesceAutoConfiguration.class))
                .withBean(RedissonClient.class, () -> own)
                .run(context -> {
                    // No second client was built from coalesce.redis.* — the application's wins.
                    assertThat(context.getBean(RedissonClient.class)).isSameAs(own);
                    assertThat(context.getBean(RedissonReactiveClient.class)).isSameAs(ownReactive);
                    assertThat(context).hasSingleBean(CoalesceAspect.class);
                });
    }

    @Test
    void anApplicationSuppliedCodecReplacesTheJsonOne() {
        runner.withUserConfiguration(CustomCodecConfig.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(CoalesceCodec.class)
                        .doesNotHaveBean(JsonCoalesceCodec.class));
    }

    @Test
    void anApplicationSuppliedCoordinatorReplacesTheRedissonOne() {
        runner.withUserConfiguration(CustomCoordinatorConfig.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(CoalesceCoordinator.class)
                        .doesNotHaveBean(RedissonCoalesceCoordinator.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class MockRedissonConfig {

        @Bean
        RedissonReactiveClient redissonReactiveClient() {
            return Mockito.mock(RedissonReactiveClient.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomCodecConfig {

        @Bean
        CoalesceCodec customCodec() {
            return new CoalesceCodec() {
                @Override
                public byte[] encode(Object value) {
                    return new byte[0];
                }

                @Override
                public Object decode(byte[] bytes, Type type) {
                    return null;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomCoordinatorConfig {

        @Bean
        CoalesceCoordinator customCoordinator() {
            return Mockito.mock(CoalesceCoordinator.class);
        }
    }
}
