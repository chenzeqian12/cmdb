package com.cloudwise.cmdb.config.remote;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.ConfigType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

/** 从固定 Nacos 位置读取或发布 CMDB 密文信封。 */
public class NacosCmdbConfigRepository {

    @FunctionalInterface
    interface ConfigServiceFactory {
        ConfigService create(Properties properties) throws Exception;
    }

    private final CmdbRemoteConfigSettings settings;

    private final ObjectMapper objectMapper;

    private final ConfigServiceFactory configServiceFactory;

    public NacosCmdbConfigRepository(CmdbRemoteConfigSettings settings, ObjectMapper objectMapper) {
        this(settings, objectMapper, NacosFactory::createConfigService);
    }

    NacosCmdbConfigRepository(
            CmdbRemoteConfigSettings settings,
            ObjectMapper objectMapper,
            ConfigServiceFactory configServiceFactory) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.configServiceFactory = configServiceFactory;
    }

    public CmdbConfigEnvelope loadEnvelope() {
        settings.validate();
        ConfigService service = null;
        try {
            service = configServiceFactory.create(connectionProperties());
            String document = service.getConfig(
                    settings.dataId(), settings.group(), settings.timeoutMs());
            if (document == null || document.isBlank()) {
                throw new IllegalStateException("CMDB encrypted configuration does not exist in Nacos");
            }
            JsonNode root = objectMapper.readTree(document);
            validateEnvelopeShape(root);
            CmdbConfigEnvelope envelope = objectMapper.treeToValue(root, CmdbConfigEnvelope.class);
            envelope.validate();
            return envelope;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to read CMDB encrypted configuration from Nacos", exception);
        } finally {
            closeQuietly(service);
        }
    }

    public void publishEnvelope(CmdbConfigEnvelope envelope) {
        settings.validate();
        envelope.validate();
        ConfigService service = null;
        try {
            service = configServiceFactory.create(connectionProperties());
            String document = objectMapper.writeValueAsString(envelope);
            boolean published = service.publishConfig(
                    settings.dataId(), settings.group(), document, ConfigType.JSON.getType());
            if (!published) {
                throw new IllegalStateException("Nacos rejected the encrypted CMDB configuration");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to publish CMDB encrypted configuration to Nacos", exception);
        } finally {
            closeQuietly(service);
        }
    }

    private void validateEnvelopeShape(JsonNode root) {
        if (!root.isObject()) {
            throw new IllegalStateException("CMDB config envelope must be an object");
        }
        Set<String> allowed = Set.of("schemaVersion", "cipherType", "ciphertext");
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                throw new IllegalStateException("CMDB config envelope contains unsupported field");
            }
        }
    }

    private Properties connectionProperties() {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, requiredSystemProperty("cwNacosServer"));
        setIfPresent(properties, PropertyKeyConst.NAMESPACE, System.getProperty("cwNacosNamespace"));
        setIfPresent(properties, PropertyKeyConst.USERNAME, System.getProperty("cwNacosUserName"));
        setIfPresent(properties, PropertyKeyConst.PASSWORD, System.getProperty("cwNacosPassword"));
        return properties;
    }

    private String requiredSystemProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing Nacos bootstrap property: " + name);
        }
        return value;
    }

    private void setIfPresent(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.setProperty(key, value);
        }
    }

    private void closeQuietly(ConfigService service) {
        if (service != null) {
            try {
                service.shutDown();
            } catch (Exception ignored) {
                // 关闭失败不能覆盖真正的读取或发布结果。
            }
        }
    }
}
