# Key Resolution

## The principle

Nothing makes a key "globally unique" for you — there is one Redis behind every pod,
so any string key any pod computes automatically refers to the same shared entry.
The actual engineering problem is narrower: **make every pod compute the
byte-identical string for the same logical call.** That is entirely on the key
derivation code, not on Redis or Redisson.

## Getting headers into the key — two options

WebFlux has no thread-local request access (execution hops event-loop threads), so
`RequestContextHolder`-style access does not work here.

**Option A — simplest.** Have the annotated method accept `ServerWebExchange` or
`ServerHttpRequest` as a parameter and reference it directly in SpEL:

```java
@Coalesce(key = "#exchange.request.headers.getFirst('X-Idempotency-Key')")
public Mono<OrderDto> placeOrder(ServerWebExchange exchange, OrderRequest req) { ... }
```

No context plumbing needed — it's just another method parameter as far as the
aspect's SpEL evaluation is concerned.

**Option B — for methods buried in a service layer**, with no direct access to the
exchange. A `WebFilter` stashes headers into the Reactor `Context` once, at the edge:

```java
package net.bitsar.coalesce;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
public class HeaderCaptureFilter implements WebFilter {
    public static final String CTX_KEY = "coalesce.headers";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange)
            .contextWrite(Context.of(CTX_KEY, exchange.getRequest().getHeaders()));
    }
}
```

This is why the aspect resolves the key **inside** `Mono.deferContextual` /
`Flux.deferContextual` rather than eagerly before returning — the Reactor Context is
only visible once you're inside the reactive chain.

## Determinism hazards — things that quietly break "same call → same key" across pods

- **Never derive a key from `Object#toString()`/`hashCode()`** of a whole DTO —
  default `toString()` is identity-based and differs per instance, even per pod.
  Reference explicit fields via SpEL instead.
- **If hashing a whole payload** (e.g. body-based idempotency), serialize it
  canonically first (sorted map keys / stable property ordering) before hashing.
  Two JSON encodings of the same logical object with different key order hash
  differently.
- **Sort multi-value inputs** before joining — `headerKeys` is sorted internally
  for exactly this reason; don't rely on array/map iteration order elsewhere.
- **Header value hygiene** — trim whitespace; header *names* are already
  case-insensitive via `HttpHeaders.getFirst`.

## Redis Cluster requirement: hash tags

The key format the aspect produces is:

```
coalesce:{namespace:base:headerPart}
```

The `{}` around everything after `coalesce:` is a **hash tag** — Redis Cluster hashes
only the content inside `{}` when computing which shard a key belongs to. Without it,
the lock key and the `notify:` topic key (which is a different string) can land on
different shards, and any cross-object atomicity assumption breaks, along with
`CROSSSLOT` errors from any Lua-based operation touching both. See
`05-cluster-considerations.md` for the full explanation.

**Apply the hash tag from the very first implementation**, even before deploying to a
real cluster — retrofitting it later invalidates every key already in flight.
