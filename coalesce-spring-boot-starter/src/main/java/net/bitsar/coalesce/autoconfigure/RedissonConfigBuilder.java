package net.bitsar.coalesce.autoconfigure;

import java.io.IOException;
import java.util.List;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.BaseConfig;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

/**
 * Turns {@code coalesce.redis.*} into a Redisson {@link Config}.
 *
 * <p>Separate from the auto-configuration so that cluster and TLS wiring can be asserted in
 * a unit test — building a {@code Config} touches no network, while creating the client from
 * it does.
 */
public final class RedissonConfigBuilder {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfigBuilder.class);

    private static final String PLAIN_SCHEME = "redis://";
    private static final String TLS_SCHEME = "rediss://";

    private RedissonConfigBuilder() {
    }

    /**
     * @param redis the {@code coalesce.redis.*} settings
     * @return a Redisson configuration; no connection is attempted here
     * @throws IllegalStateException if the settings cannot describe a usable topology
     */
    public static Config build(CoalesceProperties.Redis redis) {
        Config config = new Config();
        // Readable in redis-cli; only applies to objects that don't pick their own codec.
        // Everything this library touches picks one explicitly (ByteArrayCodec for the
        // envelope bucket, StringCodec for the topic).
        config.setCodec(new JsonJacksonCodec());

        BaseConfig<?> server = switch (redis.getMode()) {
            case SINGLE -> single(config, redis);
            case CLUSTER -> cluster(config, redis);
        };

        applyAuth(server, redis);
        applyTimeouts(server, redis);
        applySsl(server, redis.getSsl());
        return config;
    }

    // ---------- topologies ----------

    private static SingleServerConfig single(Config config, CoalesceProperties.Redis redis) {
        if (!redis.getNodes().isEmpty()) {
            log.warn("coalesce.redis.nodes is set but mode is SINGLE, so it is ignored; "
                    + "set coalesce.redis.mode=cluster to use them");
        }
        return config.useSingleServer()
                .setAddress(applyScheme(redis.getAddress(), redis.getSsl().isEnabled()))
                .setDatabase(redis.getDatabase())
                .setConnectionPoolSize(redis.getConnectionPoolSize())
                .setConnectionMinimumIdleSize(redis.getConnectionMinimumIdleSize());
    }

    private static ClusterServersConfig cluster(Config config, CoalesceProperties.Redis redis) {
        if (redis.getNodes().isEmpty()) {
            throw new IllegalStateException(
                    "coalesce.redis.mode=cluster requires at least one address in coalesce.redis.nodes");
        }
        if (redis.getDatabase() != 0) {
            log.warn("coalesce.redis.database={} is ignored: Redis Cluster only has database 0",
                    redis.getDatabase());
        }
        warnIfReadsCanBeStale(redis.getReadMode());

        String[] nodes = redis.getNodes().stream()
                .map(node -> applyScheme(node, redis.getSsl().isEnabled()))
                .toArray(String[]::new);

        return config.useClusterServers()
                .addNodeAddress(nodes)
                .setReadMode(redis.getReadMode())
                .setScanInterval((int) redis.getScanInterval().toMillis())
                .setMasterConnectionPoolSize(redis.getMasterConnectionPoolSize())
                .setMasterConnectionMinimumIdleSize(redis.getMasterConnectionMinimumIdleSize())
                .setSlaveConnectionPoolSize(redis.getSlaveConnectionPoolSize())
                .setSlaveConnectionMinimumIdleSize(redis.getSlaveConnectionMinimumIdleSize());
    }

    /**
     * Not an error — a deployment may genuinely want replica reads — but it changes what the
     * library guarantees, so it must not happen silently.
     */
    private static void warnIfReadsCanBeStale(ReadMode readMode) {
        if (readMode != ReadMode.MASTER) {
            log.warn("coalesce.redis.read-mode={} lets reads land on a replica. Replication is "
                            + "asynchronous, so a follower can be shown a stale outcome for work that "
                            + "has already completed and will re-execute it. Coalescing still functions, "
                            + "but the duplicate-suppression guarantee is weakened; MASTER avoids this.",
                    readMode);
        }
    }

    // ---------- settings shared by both topologies ----------

    private static void applyAuth(BaseConfig<?> server, CoalesceProperties.Redis redis) {
        // Redisson treats "" as a real credential and fails the handshake, so blanks have to
        // become nulls rather than being passed through.
        if (!redis.getPassword().isBlank()) {
            server.setPassword(redis.getPassword());
        }
        if (!redis.getUsername().isBlank()) {
            server.setUsername(redis.getUsername());
        }
    }

    private static void applyTimeouts(BaseConfig<?> server, CoalesceProperties.Redis redis) {
        server.setTimeout((int) redis.getTimeout().toMillis());
        server.setConnectTimeout((int) redis.getConnectTimeout().toMillis());
    }

    private static void applySsl(BaseConfig<?> server, CoalesceProperties.Ssl ssl) {
        server.setSslVerificationMode(ssl.getVerificationMode());
        server.setSslProvider(ssl.getProvider());

        if (ssl.getTruststore() != null) {
            server.setSslTruststore(urlOf(ssl.getTruststore(), "truststore"));
            if (!ssl.getTruststorePassword().isBlank()) {
                server.setSslTruststorePassword(ssl.getTruststorePassword());
            }
        }
        if (ssl.getKeystore() != null) {
            server.setSslKeystore(urlOf(ssl.getKeystore(), "keystore"));
            if (!ssl.getKeystorePassword().isBlank()) {
                server.setSslKeystorePassword(ssl.getKeystorePassword());
            }
        }
        if (ssl.getKeystoreType() != null && !ssl.getKeystoreType().isBlank()) {
            server.setSslKeystoreType(ssl.getKeystoreType());
        }
        if (!ssl.getProtocols().isEmpty()) {
            server.setSslProtocols(toArray(ssl.getProtocols()));
        }
        if (!ssl.getCiphers().isEmpty()) {
            server.setSslCiphers(toArray(ssl.getCiphers()));
        }
    }

    // ---------- helpers ----------

    /**
     * Redisson decides whether a connection is encrypted from the scheme alone. Upgrading
     * here means {@code ssl.enabled=true} cannot silently coexist with a plaintext address —
     * a combination that otherwise connects unencrypted while looking configured for TLS.
     */
    private static String applyScheme(String address, boolean sslEnabled) {
        if (!sslEnabled || address.startsWith(TLS_SCHEME)) {
            return address;
        }
        if (address.startsWith(PLAIN_SCHEME)) {
            String upgraded = TLS_SCHEME + address.substring(PLAIN_SCHEME.length());
            log.info("coalesce.redis.ssl.enabled=true: connecting to {} instead of {}", upgraded, address);
            return upgraded;
        }
        // No scheme at all; Redisson requires one.
        return TLS_SCHEME + address;
    }

    private static java.net.URL urlOf(Resource resource, String what) {
        try {
            return resource.getURL();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "coalesce.redis.ssl." + what + " could not be resolved: " + resource, e);
        }
    }

    private static String[] toArray(List<String> values) {
        return values.toArray(String[]::new);
    }
}
