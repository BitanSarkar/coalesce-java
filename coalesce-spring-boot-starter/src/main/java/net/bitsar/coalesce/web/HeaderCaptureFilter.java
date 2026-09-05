package net.bitsar.coalesce.web;

import org.springframework.core.Ordered;
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
public class HeaderCaptureFilter implements WebFilter, Ordered {

    /** Reactor Context key the captured {@code HttpHeaders} are stored under. */
    public static final String CTX_KEY = "coalesce.headers";

    /**
     * Ahead of application filters, so a {@code @Coalesce} method reached from anywhere in
     * the chain sees the headers. It writes to the Reactor Context and does nothing else,
     * so running early costs nothing.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange)
                .contextWrite(Context.of(CTX_KEY, exchange.getRequest().getHeaders()));
    }
}
