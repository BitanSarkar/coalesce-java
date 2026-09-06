package net.bitsar.coalesce.autoconfigure;

import net.bitsar.coalesce.actuate.CoalesceEndpoint;
import net.bitsar.coalesce.metrics.CoalesceMetrics;
import net.bitsar.coalesce.toggle.CoalesceToggle;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link CoalesceEndpoint} when Actuator is present and the endpoint has been
 * exposed. Actuator is an optional dependency of this starter: applications without it on
 * the classpath are unaffected, and applications with it still have to opt the endpoint in
 * through {@code management.endpoints.web.exposure.include}, which is Spring Boot's own
 * gate rather than one invented here.
 */
@AutoConfiguration(after = CoalesceAutoConfiguration.class)
@ConditionalOnClass({Endpoint.class, ConditionalOnAvailableEndpoint.class})
@ConditionalOnProperty(prefix = "coalesce", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CoalesceActuatorAutoConfiguration {

    @Bean
    @ConditionalOnAvailableEndpoint(endpoint = CoalesceEndpoint.class)
    @ConditionalOnMissingBean
    public CoalesceEndpoint coalesceEndpoint(CoalesceToggle toggle, CoalesceMetrics metrics) {
        return new CoalesceEndpoint(toggle, metrics);
    }
}
