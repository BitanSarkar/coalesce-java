package com.example.coalesce.web;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Stashes request headers into the Reactor Context so {@code headerKeys} works for methods
 * buried in a service layer with no access to the exchange.
 *
 * <p>WebFlux hops event-loop threads, so there is no thread-local request to read —
 * {@code RequestContextHolder}-style access does not work here.
 */
@Component
public class HeaderCaptureFilter implements WebFilter {

    public static final String CTX_KEY = "coalesce.headers";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange)
                .contextWrite(Context.of(CTX_KEY, exchange.getRequest().getHeaders()));
    }
}
