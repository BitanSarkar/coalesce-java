package net.bitsar.coalesce.autoconfigure;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.config.SingleServerConfig;
import org.redisson.config.SslProvider;
import org.redisson.config.SslVerificationMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Building a Redisson {@link Config} touches no network, so every topology and TLS
 * combination can be asserted here — including the cluster and mutual-TLS paths that would
 * otherwise only be exercised in someone's production environment.
 */
class RedissonConfigBuilderTest {

    private static CoalesceProperties.Redis redis() {
        return new CoalesceProperties().getRedis();
    }

    private static SingleServerConfig single(Config config) {
        return (SingleServerConfig) ReflectionTestUtils.getField(config, "singleServerConfig");
    }

    private static ClusterServersConfig cluster(Config config) {
        return (ClusterServersConfig) ReflectionTestUtils.getField(config, "clusterServersConfig");
    }

    // ---------- SINGLE ----------

    @Test
    void singleModeIsTheDefaultAndUsesTheAddress() {
        Config config = RedissonConfigBuilder.build(redis());

        assertThat(config.isClusterConfig()).isFalse();
        assertThat(single(config).getAddress()).isEqualTo("redis://localhost:6379");
        assertThat(single(config).getConnectionPoolSize()).isEqualTo(64);
    }

    @Test
    void singleModeCarriesDatabaseAndPoolSettings() {
        CoalesceProperties.Redis redis = redis();
        redis.setAddress("redis://cache.internal:6380");
        redis.setDatabase(3);
        redis.setConnectionPoolSize(128);
        redis.setConnectionMinimumIdleSize(20);

        SingleServerConfig server = single(RedissonConfigBuilder.build(redis));

        assertThat(server.getAddress()).isEqualTo("redis://cache.internal:6380");
        assertThat(server.getDatabase()).isEqualTo(3);
        assertThat(server.getConnectionPoolSize()).isEqualTo(128);
        assertThat(server.getConnectionMinimumIdleSize()).isEqualTo(20);
    }

    // ---------- auth ----------

    @Test
    void usernameAndPasswordAreApplied() {
        CoalesceProperties.Redis redis = redis();
        redis.setUsername("app");
        redis.setPassword("s3cret");

        SingleServerConfig server = single(RedissonConfigBuilder.build(redis));

        assertThat(server.getUsername()).isEqualTo("app");
        assertThat(server.getPassword()).isEqualTo("s3cret");
    }

    @Test
    void blankCredentialsStayNullRatherThanEmptyStrings() {
        // Redisson treats "" as a real credential and fails the handshake with it.
        SingleServerConfig server = single(RedissonConfigBuilder.build(redis()));

        assertThat(server.getUsername()).isNull();
        assertThat(server.getPassword()).isNull();
    }

    @Test
    void timeoutsAreConvertedToMillis() {
        CoalesceProperties.Redis redis = redis();
        redis.setTimeout(Duration.ofSeconds(2));
        redis.setConnectTimeout(Duration.ofSeconds(7));

        SingleServerConfig server = single(RedissonConfigBuilder.build(redis));

        assertThat(server.getTimeout()).isEqualTo(2_000);
        assertThat(server.getConnectTimeout()).isEqualTo(7_000);
    }

    // ---------- CLUSTER ----------

    @Test
    void clusterModeUsesEverySeedNode() {
        CoalesceProperties.Redis redis = redis();
        redis.setMode(CoalesceProperties.Mode.CLUSTER);
        redis.setNodes(List.of("redis://n1:6379", "redis://n2:6379", "redis://n3:6379"));

        Config config = RedissonConfigBuilder.build(redis);

        assertThat(config.isClusterConfig()).isTrue();
        assertThat(cluster(config).getNodeAddresses())
                .containsExactly("redis://n1:6379", "redis://n2:6379", "redis://n3:6379");
    }

    @Test
    void clusterDefaultsToMasterReadsSoFollowersNeverSeeAStaleOutcome() {
        CoalesceProperties.Redis redis = redis();
        redis.setMode(CoalesceProperties.Mode.CLUSTER);
        redis.setNodes(List.of("redis://n1:6379"));

        assertThat(cluster(RedissonConfigBuilder.build(redis)).getReadMode()).isEqualTo(ReadMode.MASTER);
    }

    @Test
    void aDeliberateReplicaReadModeIsHonouredNotOverridden() {
        CoalesceProperties.Redis redis = redis();
        redis.setMode(CoalesceProperties.Mode.CLUSTER);
        redis.setNodes(List.of("redis://n1:6379"));
        redis.setReadMode(ReadMode.SLAVE);

        // Warned about loudly, but the operator's choice stands.
        assertThat(cluster(RedissonConfigBuilder.build(redis)).getReadMode()).isEqualTo(ReadMode.SLAVE);
    }

    @Test
    void clusterCarriesScanIntervalAndPerRoledPoolSizes() {
        CoalesceProperties.Redis redis = redis();
        redis.setMode(CoalesceProperties.Mode.CLUSTER);
        redis.setNodes(List.of("redis://n1:6379"));
        redis.setScanInterval(Duration.ofSeconds(30));
        redis.setMasterConnectionPoolSize(100);
        redis.setSlaveConnectionPoolSize(50);

        ClusterServersConfig server = cluster(RedissonConfigBuilder.build(redis));

        assertThat(server.getScanInterval()).isEqualTo(30_000);
        assertThat(server.getMasterConnectionPoolSize()).isEqualTo(100);
        assertThat(server.getSlaveConnectionPoolSize()).isEqualTo(50);
    }

    @Test
    void clusterWithoutNodesFailsWithAnActionableMessage() {
        CoalesceProperties.Redis redis = redis();
        redis.setMode(CoalesceProperties.Mode.CLUSTER);

        assertThatThrownBy(() -> RedissonConfigBuilder.build(redis))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("coalesce.redis.nodes");
    }

    // ---------- TLS ----------

    @Test
    void sslEnabledUpgradesThePlaintextScheme() {
        // Redisson decides encryption from the scheme alone, so a truststore plus
        // redis:// would otherwise connect in the clear while looking configured for TLS.
        CoalesceProperties.Redis redis = redis();
        redis.getSsl().setEnabled(true);

        assertThat(single(RedissonConfigBuilder.build(redis)).getAddress())
                .isEqualTo("rediss://localhost:6379");
    }

    @Test
    void anExplicitTlsSchemeIsLeftAlone() {
        CoalesceProperties.Redis redis = redis();
        redis.setAddress("rediss://secure.internal:6379");

        assertThat(single(RedissonConfigBuilder.build(redis)).getAddress())
                .isEqualTo("rediss://secure.internal:6379");
    }

    @Test
    void sslEnabledUpgradesEveryClusterNode() {
        CoalesceProperties.Redis redis = redis();
        redis.setMode(CoalesceProperties.Mode.CLUSTER);
        redis.setNodes(List.of("redis://n1:6379", "rediss://n2:6379"));
        redis.getSsl().setEnabled(true);

        assertThat(cluster(RedissonConfigBuilder.build(redis)).getNodeAddresses())
                .containsExactly("rediss://n1:6379", "rediss://n2:6379");
    }

    @Test
    void truststoreAndKeystoreAreResolvedToUrls() {
        CoalesceProperties.Redis redis = redis();
        redis.getSsl().setEnabled(true);
        redis.getSsl().setTruststore(new ClassPathResource("ssl/truststore.jks"));
        redis.getSsl().setTruststorePassword("trustpw");
        redis.getSsl().setKeystore(new ClassPathResource("ssl/keystore.p12"));
        redis.getSsl().setKeystorePassword("keypw");
        redis.getSsl().setKeystoreType("PKCS12");

        SingleServerConfig server = single(RedissonConfigBuilder.build(redis));

        assertThat(server.getSslTruststore()).isNotNull();
        assertThat(server.getSslTruststore().toString()).endsWith("ssl/truststore.jks");
        assertThat(server.getSslTruststorePassword()).isEqualTo("trustpw");
        assertThat(server.getSslKeystore().toString()).endsWith("ssl/keystore.p12");
        assertThat(server.getSslKeystorePassword()).isEqualTo("keypw");
        assertThat(server.getSslKeystoreType()).isEqualTo("PKCS12");
    }

    @Test
    void verificationDefaultsToStrictAndIsOverridable() {
        assertThat(single(RedissonConfigBuilder.build(redis())).getSslVerificationMode())
                .isEqualTo(SslVerificationMode.STRICT);

        CoalesceProperties.Redis relaxed = redis();
        relaxed.getSsl().setVerificationMode(SslVerificationMode.CA_ONLY);
        assertThat(single(RedissonConfigBuilder.build(relaxed)).getSslVerificationMode())
                .isEqualTo(SslVerificationMode.CA_ONLY);
    }

    @Test
    void protocolsCiphersAndProviderAreApplied() {
        CoalesceProperties.Redis redis = redis();
        redis.getSsl().setProtocols(List.of("TLSv1.3"));
        redis.getSsl().setCiphers(List.of("TLS_AES_256_GCM_SHA384"));
        redis.getSsl().setProvider(SslProvider.OPENSSL);

        SingleServerConfig server = single(RedissonConfigBuilder.build(redis));

        assertThat(server.getSslProtocols()).containsExactly("TLSv1.3");
        assertThat(server.getSslCiphers()).containsExactly("TLS_AES_256_GCM_SHA384");
        assertThat(server.getSslProvider()).isEqualTo(SslProvider.OPENSSL);
    }

    @Test
    void unsetTlsMaterialLeavesTheJvmDefaultsInPlace() {
        SingleServerConfig server = single(RedissonConfigBuilder.build(redis()));

        assertThat(server.getSslTruststore()).isNull();
        assertThat(server.getSslKeystore()).isNull();
        assertThat(server.getSslProtocols()).isNull();
    }
}
