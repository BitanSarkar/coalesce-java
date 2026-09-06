# Implementation checklist for the POC

## Build order

1. Dependencies: `org.redisson:redisson` (reactive API ships in the same
   artifact), existing Spring WebFlux + Jackson.
2. Config: `RedissonConfig` from `03-coordinator-and-codec.md`. Point it at a
   local single-node Redis first. Cluster-specific config only matters once you're
   testing against an actual cluster.
3. `CoalesceState`, `CoalesceCodec` + `JsonCoalesceCodec`: small,
   self-contained, no dependencies on the rest.
4. `RedissonCoalesceCoordinator`: implement `decodeState`/`markFailed` for real
   (see the "flag" note in `03-coordinator-and-codec.md`) rather than the
   string-sniffing placeholder before doing anything else with it.
5. The `@Coalesce` annotation from `01-annotation.md`.
6. `CoalesceAspect` from `02-aspect.md`. Build `resolveKey` and test it in
   isolation first (unit test: same args + headers in different order → identical
   key string) before wiring in the lock/bucket logic.
7. `HeaderCaptureFilter`, only if you need `headerKeys`. Skip it entirely for the
   first pass if your POC method doesn't need header-based keys.
8. Wire one real annotated method end to end, single pod, single Redis, before
   attempting multi-pod tests.

## What to actually test

- Concurrent same-pod calls: fire N concurrent requests for the same key from
  one pod, assert the annotated method body ran exactly once (increment a counter
  inside it) and all N callers got the same result.
- Concurrent cross-pod calls: same, but split across two local instances
  (different ports) pointed at the same Redis. This is where `lockId` bugs show up.
  If you see `IllegalMonitorStateException` in logs, the release path is using the
  wrong id.
- FAILED retry race: force the annotated method to throw, with 3+ concurrent
  followers waiting; assert exactly one of them re-executes and the rest get its
  result (success or failure) rather than each re-executing independently.
- Crash recovery: kill a "leader" mid-execution without letting it call
  `markDone`/`markFailed` (e.g. throw inside a test double that never reaches the
  Redisson calls); confirm a new caller can acquire the lock after
  `pendingTtlSeconds` elapses, not before.
- Missed pub/sub message: temporarily disable the topic listener in a test and
  confirm the poll fallback still resolves the wait within a bounded time.
- Stale-while-revalidate: call once, wait past `freshTtlSeconds` but within
  `staleTtlSeconds`, then call again. Assert the response is instant (no wait for a
  new execution) and that exactly one background refresh occurs even under
  concurrent calls in that window.
- Empty `Mono`: an annotated method returning `Mono.empty()`. Assert a
  follower reading that cached state gets `Mono.empty()` back, not treated as
  `ABSENT` and left waiting to timeout.

## Metrics worth adding even in the POC

- Counter: leader executions vs follower cache hits vs follower waits. This ratio
  is the entire point of the framework, and if it's not visible you can't tell if
  it's working.
- Counter: `FAILED` retries.
- Histogram: follower wait duration (should cluster near-zero with occasional
  spikes near the leader's execution time, not near `waitTimeoutSeconds`).
- Counter: `CoalesceTimeoutException` occurrences; should be rare. Frequent
  timeouts mean `pendingTtlSeconds`/`waitTimeoutSeconds` are miscalibrated or the
  downstream is genuinely too slow for this pattern.

## Open decisions to make explicitly (don't let these default by accident)

- Envelope format for DONE vs FAILED in the bucket. The POC coordinator
  string-sniffs a `"FAILED:"` prefix. Replace it with an explicit status byte before
  trusting this with real payloads that could start with arbitrary bytes.
- What a follower does on `CoalesceTimeoutException`. Propagate as an error to
  the caller (simplest), or fall back to calling the origin directly bypassing
  coalescing entirely (protects the caller from a stuck coalescing layer, at the
  cost of potentially adding load exactly when the origin is already struggling).
- ~~**Whether `FAILED` should have a short cooldown before becoming contestable
  again.**~~ **DECIDED: no cooldown.** A `FAILED` leader immediately frees the lock
  for exactly one retry. Under sustained origin trouble that is a steady retry
  trickle with no backoff, and that is correct for this layer: backoff, retry
  budgets and circuit breaking are a separate concern, and belong in a retry/circuit
  breaker around the annotated method rather than inside the coalescing framework.
  `@Coalesce` promises deduplication and a TPS shield, nothing about retry policy.
- Local per-pod tier: intentionally not included. Revisit only if profiling
  shows one hot key generating heavy same-pod follower traffic. At that point it's
  a pure addition (a `ConcurrentHashMap<key, Mono<Object>>` funneling many local
  subscribers into one `coalesce()` call) and doesn't require changing anything
  above the entry point.
