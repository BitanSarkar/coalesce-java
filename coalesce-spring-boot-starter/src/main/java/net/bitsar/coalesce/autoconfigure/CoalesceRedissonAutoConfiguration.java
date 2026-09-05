package net.bitsar.coalesce.autoconfigure;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Supplies a Redisson client only if the application has not.
 *
 * <p>Deliberately not the Redisson Spring Boot starter: this library owns nothing about how
 * you talk to Redis, so an application that already has a {@code RedissonClient} — from that
 * starter, or hand-built for cluster or sentinel — keeps it, and this configuration
 * contributes only the reactive view of it.
 *
 * <p><b>Redis Cluster.</b> The single-server client built here cannot reach a cluster.
 * Declare your own bean with {@code config.useClusterServers()} and, importantly,
 * {@code setReadMode(ReadMode.MASTER)}: a stale read off a replica can show a follower a
 * {@code FAILED} state for work that has already succeeded, and it will re-execute it.
 */
@AutoConfiguration
@ConditionalOnClass({RedissonClient.class, RedissonReactiveClient.class})
@ConditionalOnProperty(prefix = "coalesce", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CoalesceProperties.class)
public class CoalesceRedissonAutoConfiguration {

    /**
     * Backs off entirely if either client bean already exists, so an application-supplied
     * topology always wins.
     *
     * @param properties the {@code coalesce.redis.*} settings
     * @return a single-server Redisson client owned by this context
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean({RedissonClient.class, RedissonReactiveClient.class})
    @ConditionalOnProperty(prefix = "coalesce.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedissonClient coalesceRedissonClient(CoalesceProperties properties) {
        CoalesceProperties.Redis redis = properties.getRedis();

        Config config = new Config();
        // Readable in redis-cli; only applies to objects that don't pick their own codec.
        // Everything this library touches picks one explicitly (ByteArrayCodec for the
        // envelope bucket, StringCodec for the topic).
        config.setCodec(new JsonJacksonCodec());

        SingleServerConfig server = config.useSingleServer()
                .setAddress(redis.getAddress())
                .setDatabase(redis.getDatabase())
                .setTimeout((int) redis.getTimeout().toMillis())
                .setConnectionPoolSize(redis.getConnectionPoolSize())
                .setConnectionMinimumIdleSize(redis.getConnectionMinimumIdleSize());

        // Redisson treats "" as a real credential and fails the handshake, so blanks have to
        // become nulls rather than being passed through.
        if (!redis.getPassword().isBlank()) {
            server.setPassword(redis.getPassword());
        }
        if (!redis.getUsername().isBlank()) {
            server.setUsername(redis.getUsername());
        }

        return Redisson.create(config);
    }

    /**
     * The reactive view of whichever {@code RedissonClient} is in play — this library's or
     * the application's.
     *
     * <p>No {@code destroyMethod}: the reactive client is a view, not a second connection
     * pool, and shutting it down would close a client the application may still be using.
     *
     * @param redissonClient the blocking client to adapt
     * @return the reactive client the coordinator runs on
     */
    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean(RedissonReactiveClient.class)
    public RedissonReactiveClient coalesceRedissonReactiveClient(RedissonClient redissonClient) {
        return redissonClient.reactive();
    }
}
