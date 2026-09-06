# Coordinator, codec, and Spring Boot wiring

## `CoalesceState`

```java
package net.bitsar.coalesce;

public record CoalesceState(Status status, byte[] payload, String errorMessage, long computedAt) {

    public enum Status { DONE, FAILED, PENDING, ABSENT }

    public static CoalesceState done(byte[] payload) {
        return new CoalesceState(Status.DONE, payload, null, System.currentTimeMillis());
    }

    public static CoalesceState failed(String message) {
        return new CoalesceState(Status.FAILED, null, message, System.currentTimeMillis());
    }

    public static CoalesceState absent() {
        return new CoalesceState(Status.ABSENT, null, null, 0);
    }
}
```

## `CoalesceCodec`: pluggable wire format

```java
package net.bitsar.coalesce;

import com.fasterxml.jackson.databind.JavaType;

public interface CoalesceCodec {
    byte[] encode(Object value);
    Object decode(byte[] bytes, JavaType type);
}
```

```java
package net.bitsar.coalesce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JsonCoalesceCodec implements CoalesceCodec {

    private final ObjectMapper mapper;

    public JsonCoalesceCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public byte[] encode(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new CoalesceCodecException("encode failed", e);
        }
    }

    @Override
    public Object decode(byte[] bytes, JavaType type) {
        try {
            return mapper.readValue(bytes, type);
        } catch (IOException e) {
            throw new CoalesceCodecException("decode failed", e);
        }
    }
}
```

Do not use Java native serialization (`ObjectOutputStream`, Redisson's default
`SerializationCodec`) here. It requires every cached DTO to implement `Serializable`,
breaks across rolling deploys when a class shape changes while old and new pods share
one Redis, and deserializing untrusted bytes is a known RCE vector.

## `RedissonCoalesceCoordinator`

```java
package net.bitsar.coalesce;

import org.redisson.api.RBucketReactive;
import org.redisson.api.RLockReactive;
import org.redisson.api.RTopicReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class RedissonCoalesceCoordinator {

    private final RedissonReactiveClient redisson;

    public RedissonCoalesceCoordinator(RedissonReactiveClient redisson) {
        this.redisson = redisson;
    }

    public Mono<Boolean> tryAcquire(String key, long lockId, Duration leaseTime) {
        RLockReactive lock = redisson.getLock(key);
        return lock.tryLock(0, leaseTime.toSeconds(), TimeUnit.SECONDS, lockId);
    }

    public Mono<Void> release(String key, long lockId) {
        return redisson.getLock(key).unlock(lockId)
            .onErrorResume(e -> Mono.empty()); // already expired / already released, not fatal
    }

    public Mono<Void> markDone(String key, byte[] payload, Duration ttl) {
        RBucketReactive<byte[]> bucket = bucket(key);
        return bucket.set(payload, ttl)
            .then(topic(key).publish("DONE"))
            .then();
    }

    public Mono<Void> markFailed(String key, Throwable err, Duration ttl) {
        RBucketReactive<byte[]> bucket = bucket(key);
        byte[] marker = ("FAILED:" + err.getMessage()).getBytes();
        return bucket.set(marker, ttl)
            .then(topic(key).publish("FAILED"))
            .then();
        // NOTE: real implementation should encode status separately from payload
        // (e.g. a one-byte status prefix) rather than string-sniffing. Simplified
        // here for POC readability. See "open decisions" in 06-implementation-checklist.md.
    }

    public Mono<CoalesceState> fetchState(String key) {
        return bucket(key).get()
            .map(this::decodeState)
            .defaultIfEmpty(CoalesceState.absent());
    }

    public Flux<String> listen(String key) {
        RTopicReactive topic = topic(key);
        return Flux.<String>create(sink ->
                topic.addListener(String.class, (channel, msg) -> sink.next(msg)).subscribe())
            .take(1);
    }

    private RBucketReactive<byte[]> bucket(String key) {
        return redisson.getBucket(key, ByteArrayCodec.INSTANCE);
    }

    private RTopicReactive topic(String key) {
        // use getShardedTopic(key, ...) instead on Redis 7+ Cluster, see 05-cluster-considerations.md
        return redisson.getTopic("notify:" + key);
    }

    private CoalesceState decodeState(byte[] raw) {
        // placeholder decode matching the simplified markFailed above. Replace it with
        // a real envelope format before anything beyond the POC stage.
        String asString = new String(raw);
        if (asString.startsWith("FAILED:")) {
            return CoalesceState.failed(asString.substring(7));
        }
        return CoalesceState.done(raw);
    }
}
```

**Flag for the POC**: the `markFailed`/`decodeState` pairing above is a placeholder.
String-sniffing bytes to detect FAILED vs DONE is fragile the moment a real payload
could itself start with `"FAILED:"`. Before this goes past a POC, replace it with a
proper envelope: a fixed-size status prefix (e.g. one byte: `0=DONE, 1=FAILED`)
followed by the actual payload bytes, decoded explicitly rather than sniffed.

## Spring Boot wiring

```java
package net.bitsar.coalesce.autoconfigure;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        config.setCodec(new JsonJacksonCodec()); // readable in redis-cli; only used for non-bucket objects

        String scheme = redisProperties.getSsl().isEnabled() ? "rediss://" : "redis://";
        config.useSingleServer()
            .setAddress(scheme + redisProperties.getHost() + ":" + redisProperties.getPort())
            .setPassword(redisProperties.getPassword())
            .setConnectionPoolSize(64)
            .setConnectionMinimumIdleSize(10);

        // For Redis Cluster instead of single-server:
        // config.useClusterServers()
        //     .setReadMode(ReadMode.MASTER) // required, see 05-cluster-considerations.md
        //     .addNodeAddress(scheme + "node1:6379", scheme + "node2:6379", ...);

        return Redisson.create(config);
    }

    @Bean
    public RedissonReactiveClient redissonReactiveClient(RedissonClient redissonClient) {
        return redissonClient.reactive();
    }
}
```

This is a separate client from any existing `ReactiveRedisTemplate`/Lettuce setup.
They coexist fine, just size both connection pools with your Redis instance's
connection cap in mind, and keep the `coalesce:` key prefix exclusive to this
framework so the two clients never write conflicting formats to the same key.
