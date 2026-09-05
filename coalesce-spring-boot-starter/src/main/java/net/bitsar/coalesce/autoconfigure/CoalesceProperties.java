package net.bitsar.coalesce.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything under the {@code coalesce.*} prefix.
 *
 * <p>Per-call behaviour — TTLs, timeouts, the key expression — lives on the
 * {@link net.bitsar.coalesce.annotation.Coalesce} annotation instead, because those are
 * properties of the method being coalesced rather than of the application.
 */
@ConfigurationProperties(prefix = "coalesce")
public class CoalesceProperties {

    /** Whether call coalescing is wired at all. Turn off to run without touching Redis. */
    private boolean enabled = true;

    /**
     * Results larger than this are still returned to the caller but never written to Redis.
     * Redisson buffers every command in Netty's direct arena before the write, so a handful
     * of oversized entries in flight can exhaust MaxDirectMemorySize and kill the process —
     * the payload never reaches Redis, it dies in the encoder.
     */
    private int maxPayloadBytes = 1_048_576;

    private final Redis redis = new Redis();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public Redis getRedis() {
        return redis;
    }

    /**
     * Connection settings for the {@code RedissonClient} this library creates when the
     * application has not defined one. Ignored entirely if it has: declare your own
     * {@code RedissonClient} or {@code RedissonReactiveClient} bean for cluster, sentinel,
     * replicated or any other topology, and it is used as-is.
     */
    public static class Redis {

        /** Set to false to require the application to supply its own Redisson client. */
        private boolean enabled = true;

        /** Single-server address, including the scheme: {@code redis://} or {@code rediss://}. */
        private String address = "redis://localhost:6379";

        /** Redis password, or empty for an unauthenticated server. */
        private String password = "";

        /** Redis username, for ACL-enabled servers. Empty means "default user". */
        private String username = "";

        /** Redis database index. Ignored by Redis Cluster, which only has database 0. */
        private int database = 0;

        private int connectionPoolSize = 64;

        private int connectionMinimumIdleSize = 10;

        /** How long a command may wait for a response before Redisson fails it. */
        private Duration timeout = Duration.ofSeconds(3);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }

        public int getConnectionPoolSize() {
            return connectionPoolSize;
        }

        public void setConnectionPoolSize(int connectionPoolSize) {
            this.connectionPoolSize = connectionPoolSize;
        }

        public int getConnectionMinimumIdleSize() {
            return connectionMinimumIdleSize;
        }

        public void setConnectionMinimumIdleSize(int connectionMinimumIdleSize) {
            this.connectionMinimumIdleSize = connectionMinimumIdleSize;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
