package com.cloudwise.cmdb.config.remote;

import com.cloudwise.utils.GlobalPropertiesUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class CmdbRemoteConfigInitializer implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "cmdbNacosDoucRemoteConfig";

    private final SnapshotLoader snapshotLoader;

    public CmdbRemoteConfigInitializer() {
        this(CmdbRemoteConfigInitializer::loadFromRemote);
    }

    CmdbRemoteConfigInitializer(SnapshotLoader snapshotLoader) {
        this.snapshotLoader = snapshotLoader;
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        CmdbRemoteConfigSettings settings = CmdbRemoteConfigSettings.from(environment);
        if (!settings.enabled()) {
            return;
        }
        CmdbRemoteConfigSnapshot snapshot = snapshotLoader.load(settings);
        environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, new LinkedHashMap<>(snapshot.properties())));
    }

    private static CmdbRemoteConfigSnapshot loadFromRemote(CmdbRemoteConfigSettings settings) {
        DoucCryptoClient.protectBootstrapLogging();
        GlobalPropertiesUtils.loadNacosProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        NacosCmdbConfigRepository repository = new NacosCmdbConfigRepository(settings);
        String document = repository.loadDocument();
        CmdbRemoteConfigBinder binder = new CmdbRemoteConfigBinder(objectMapper);
        CmdbEncryptedCredentials credentials = binder.encryptedCredentials(document);
        String registryAddress = requiredSystemProperty("dubbo.registry.address");
        try (DoucCryptoClient client = DoucCryptoClient.connect(
                registryAddress, "hkex-cmdb-sync-config-loader")) {
            String username = client.decrypt(credentials.username());
            String password = client.decrypt(credentials.password());
            return binder.bind(document, username, password);
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
