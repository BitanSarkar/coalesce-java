package net.bitsar.coalesce.autoconfigure;

import net.bitsar.coalesce.web.HeaderCaptureFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.server.WebFilter;

/**
 * Registers the filter that makes {@code headerKeys} work.
 *
 * <p>Reactive web applications only: WebFlux hops event-loop threads, so headers reach a
 * service-layer method through the Reactor Context rather than a thread local. In a
 * non-web application {@code headerKeys} has nothing to read and folds an empty value into
 * the key, which is correct — every caller agrees on it.
 */
@AutoConfiguration
@ConditionalOnClass(WebFilter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "coalesce", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CoalesceWebFluxAutoConfiguration {

    /**
     * @return a filter stashing the request's headers into the Reactor Context
     */
    @Bean
    @ConditionalOnMissingBean
    public HeaderCaptureFilter coalesceHeaderCaptureFilter() {
        return new HeaderCaptureFilter();
    }
}
