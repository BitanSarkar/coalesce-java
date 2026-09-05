package com.example.coalesce.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI coalesceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("@Coalesce POC")
                        .version("0.0.1")
                        .description("""
                                Distributed reactive call coalescing with stale-while-revalidate, \
                                backed by Redis via Redisson.

                                Concurrent callers of an annotated method share a single execution \
                                cluster-wide: one caller wins a Redis lock and executes, the rest wait \
                                on a pub/sub wake-up and read the leader's cached result. Once the \
                                result passes `freshTtlSeconds` it is still served instantly while a \
                                single background refresh runs.

                                The endpoints below drive a deliberately slow (~400ms) fake downstream \
                                so the effect is visible: fire many concurrent requests at the same \
                                order id and watch `downstreamExecutions` in /coalesce/stats stay at 1.
                                """)
                        .license(new License().name("POC — not for production use")))
                .tags(List.of(
                        new Tag().name("Orders")
                                .description("Coalesced reads against a slow fake downstream"),
                        new Tag().name("Coalesce control")
                                .description("Counters and fault injection for exercising the framework"),
                        new Tag().name("Echo")
                                .description("Plain WebFlux endpoints, no coalescing")));
    }
}
