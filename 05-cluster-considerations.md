# Redis Cluster Considerations

## Sharding alone does not cause duplicate locks

In Redis Cluster, a key maps to a hash slot via `CRC16(key) mod 16384`, and each slot
lives on exactly one shard's primary. The same key **always** resolves to the same
shard. Two pods computing the same coalescing key hit the same node.

Reads for the lock itself never hit a replica by accident either — Redisson's
`tryLock` is a Lua script executed via `EVAL`, which Redis classifies as a write and
routes to the primary regardless of read settings.

## Where duplicate leaders actually come from: async replication + failover

1. Pod A's `tryLock` succeeds on the primary of some shard.
2. The primary has not yet replicated that write to its replica(s) — replication is
   asynchronous by default.
3. The primary dies. A replica is promoted with no record of the lock.
4. Pod B's `tryLock` hits the new primary, sees nothing, succeeds.
5. Two leaders, both executing.

This is inherent to Redis's replication model (the well-known critique of
Redis-based distributed locking); no configuration eliminates it, only narrows the
window. `WAIT` narrows it further at a latency cost on every acquire — generally not
worth it for this use case.

**Why this is acceptable here**: the annotated methods are expected to be
idempotent, best-effort deduplication. A rare double execution during a failover
means occasionally doing the work twice — the same outcome as running with no
framework at all. It is a **performance optimization degrading**, not a correctness
violation, provided the underlying operation is safe to run more than once.

**Do not use this framework to protect non-idempotent operations** (charging a
card, sending a payment, any operation without its own idempotency key at the data
layer) without an additional real guarantee downstream — Redis locking cannot supply
distributed-transaction correctness.

## Cross-slot failures — the one that fails loudly, not silently

If the coalescing key and the notify-topic key are different strings, they can hash
to **different shards**. Consequences:

- Any Lua script touching both is rejected with `CROSSSLOT`.
- Even without Lua, the lock/bucket/topic have no atomicity guarantee between them
  and can fail independently.

**Fix**: hash tags. Redis Cluster only hashes the content inside `{}` when computing
a key's slot:

```java
String stateKey  = "coalesce:{" + rawKey + "}";
String notifyKey = "notify:{"   + rawKey + "}";
```

This is already baked into `resolveKey()` in `02-aspect.md` — do not remove it.

## Pub/sub fan-out

Classic Redis Cluster `PUBLISH` broadcasts to **every node** in the cluster so any
subscriber anywhere receives it, regardless of which shard the channel name would
hash to. Each `publish(DONE)` therefore costs O(shard count) internal traffic even
though exactly one pod is actually listening for that specific key.

**Fix**: sharded pub/sub (Redis 7+). `SPUBLISH`/`SSUBSCRIBE` confine traffic to the
shard owning the channel's slot. In Redisson:

```java
private RTopicReactive topic(String key) {
    return redisson.getShardedTopic(key); // instead of getTopic(...)
}
```

This requires the publisher and subscriber to agree on the slot — which the hash
tag from the key resolution step already guarantees, since the publish/listen calls
use the same tagged key string.

## Read mode

If Redisson is configured with `readMode = SLAVE` or `MASTER_SLAVE`, `bucket.get()`
reads can be served by a replica and return stale data due to the same async
replication lag described above. The dangerous case for this framework: a follower
reads a stale `FAILED` after a retry has already succeeded elsewhere, calls
`tryLock` (now free), wins, and re-executes work that's already done.

**Set `ReadMode.MASTER` explicitly** for this framework's usage. The values being
read are tiny; correctness matters far more than read throughput here.

```java
config.useClusterServers().setReadMode(ReadMode.MASTER);
```

## Uneven key distribution

With hash tags applied, everything for one coalescing key lands on one shard. A very
hot key (a single popular item during a spike) concentrates all of its lock traffic,
bucket reads, and pub/sub on a single node — sharding provides no relief for a
single hot key, only for spreading load across *many distinct* keys. If a specific
key is expected to be extremely hot, that is a signal to revisit whether a local
per-pod tier (explicitly dropped for this POC — see `06-implementation-checklist.md`)
is worth reintroducing for that key's traffic pattern specifically.

## Summary checklist before pointing this at a real cluster

- [ ] Hash tags applied to every key derived by `resolveKey()`
- [ ] `ReadMode.MASTER` set on the Redisson cluster config
- [ ] Sharded topics used if on Redis 7+ (`getShardedTopic`, not `getTopic`)
- [ ] Annotated methods reviewed for idempotency — no non-idempotent side effects
      relying on this framework's lock for correctness
- [ ] `pendingTtlSeconds` set against p99 latency, not p50 or average
