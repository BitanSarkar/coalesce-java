package net.bitsar.coalesce.autoconfigure;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
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
 * <p><b>Topologies.</b> {@code coalesce.redis.mode} covers {@code SINGLE} and
 * {@code CLUSTER}, each with authentication and TLS. Sentinel, replicated and master-slave
 * deployments are not expressed as properties — declare your own {@code RedissonClient}
 * bean for those and this configuration steps aside.
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
     * @return a Redisson client owned by this context
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean({RedissonClient.class, RedissonReactiveClient.class})
    @ConditionalOnProperty(prefix = "coalesce.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedissonClient coalesceRedissonClient(CoalesceProperties properties) {
        return Redisson.create(RedissonConfigBuilder.build(properties.getRedis()));
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
