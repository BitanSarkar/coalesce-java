package net.bitsar.coalesce.demo;

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

                                One endpoint, two modes. GET /api/orders/{bucket} fetches a random \
                                list of orders from a deliberately slow downstream (normally \
                                distributed service time, with the slowest ~5% failing). The \
                                `coalesce` flag decides whether the call goes through @Coalesce or \
                                straight to the downstream.

                                Run load against both modes and read GET /api/stats: `direct` \
                                executes the downstream once per request by definition, so the gap \
                                between the two is exactly the work coalescing saved.
                                """)
                        .license(new License().name("POC — not for production use")))
                .tags(List.of(
                        new Tag().name("Orders")
                                .description("The single endpoint under test"),
                        new Tag().name("Metrics")
                                .description("Side-by-side comparison of the two modes")));
    }
}
