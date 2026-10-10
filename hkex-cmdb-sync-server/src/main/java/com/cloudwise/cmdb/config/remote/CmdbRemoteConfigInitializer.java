package com.cloudwise.cmdb.config.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 在任何业务 Bean 创建前装载 CMDB 远程密文配置。 */
public class CmdbRemoteConfigInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger LOG = LoggerFactory.getLogger(CmdbRemoteConfigInitializer.class);

    private static final String PROPERTY_SOURCE_NAME = "cmdbNacosDoucRemoteConfig";

    private final SnapshotLoader snapshotLoader;

    public CmdbRemoteConfigInitializer() {
        this(CmdbRemoteConfigInitializer::loadFromRemote);
    }

    CmdbRemoteConfigInitializer(SnapshotLoader snapshotLoader) {
        this.snapshotLoader = snapshotLoader;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        CmdbRemoteConfigSettings settings = CmdbRemoteConfigSettings.from(
                applicationContext.getEnvironment());
        if (!settings.enabled()) {
            LOG.warn("CMDB Nacos DOUC remote configuration is disabled");
            return;
        }

        CmdbRemoteConfigSnapshot snapshot = snapshotLoader.load(settings);
        applicationContext.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, new LinkedHashMap<>(snapshot.properties())));
        LOG.info("CMDB integration configuration loaded from Nacos through DOUC");
    }

    private static CmdbRemoteConfigSnapshot loadFromRemote(CmdbRemoteConfigSettings settings) {
        ObjectMapper objectMapper = new ObjectMapper();
        NacosCmdbConfigRepository repository = new NacosCmdbConfigRepository(settings, objectMapper);
        CmdbConfigEnvelope envelope = repository.loadEnvelope();
        String registryAddress = requiredSystemProperty("dubbo.registry.address");
        try (DoucCryptoClient client = DoucCryptoClient.connect(
                registryAddress, "hkex-cmdb-sync-config-loader")) {
            String plaintext = client.decrypt(envelope.ciphertext());
            return new CmdbRemoteConfigBinder(objectMapper).bind(plaintext);
        }
    }

    private static String requiredSystemProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required system property: " + name);
        }
        return value;
    }

    @FunctionalInterface
    interface SnapshotLoader {
        CmdbRemoteConfigSnapshot load(CmdbRemoteConfigSettings settings);
    }
}
