package com.example.coalesce.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.ssl.enabled:false}") boolean ssl) {

        Config config = new Config();
        // Readable in redis-cli; only applies to objects that don't pick their own codec.
        // Everything this framework touches picks one explicitly (ByteArrayCodec for the
        // envelope bucket, StringCodec for the topic).
        config.setCodec(new JsonJacksonCodec());

        String scheme = ssl ? "rediss://" : "redis://";
        config.useSingleServer()
                .setAddress(scheme + host + ":" + port)
                .setPassword(password.isBlank() ? null : password)
                .setConnectionPoolSize(64)
                .setConnectionMinimumIdleSize(10);

        // For Redis Cluster instead of single-server:
        //   config.useClusterServers()
        //       .setReadMode(ReadMode.MASTER) // required: a stale FAILED read off a replica
        //                                     // makes a follower re-execute completed work
        //       .addNodeAddress(scheme + "node1:6379", scheme + "node2:6379");

        return Redisson.create(config);
    }

    @Bean
    public RedissonReactiveClient redissonReactiveClient(RedissonClient redissonClient) {
        return redissonClient.reactive();
    }
}
