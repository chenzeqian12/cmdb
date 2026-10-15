package com.cloudwise.cmdb.config.remote;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.ConfigType;
import java.util.Properties;

/** 从固定 Nacos 位置读取或发布 CMDB JSON 配置。 */
public class NacosCmdbConfigRepository {

    @FunctionalInterface
    interface ConfigServiceFactory {
        ConfigService create(Properties properties) throws Exception;
    }

    private final CmdbRemoteConfigSettings settings;

    private final ConfigServiceFactory configServiceFactory;

    public NacosCmdbConfigRepository(CmdbRemoteConfigSettings settings) {
        this(settings, NacosFactory::createConfigService);
    }

    NacosCmdbConfigRepository(
            CmdbRemoteConfigSettings settings,
            ConfigServiceFactory configServiceFactory) {
        this.settings = settings;
        this.configServiceFactory = configServiceFactory;
    }

    public String loadDocument() {
        settings.validate();
        Properties properties = connectionProperties();
        ConfigService service = null;
        String document;
        try {
            service = configServiceFactory.create(properties);
            document = service.getConfig(settings.dataId(), settings.group(), settings.timeoutMs());
        } catch (Exception exception) {
            throw new IllegalStateException("failed to read CMDB configuration from Nacos");
        } finally {
            closeQuietly(service);
        }
        if (document == null || document.isBlank()) {
            throw new IllegalStateException("CMDB configuration does not exist in Nacos");
        }
        return document;
    }

    public void publishDocument(String document) {
        settings.validate();
        if (document == null || document.isBlank()) {
            throw new IllegalArgumentException("CMDB configuration document must not be empty");
        }
        Properties properties = connectionProperties();
        ConfigService service = null;
        boolean published;
        try {
            service = configServiceFactory.create(properties);
            published = service.publishConfig(
                    settings.dataId(), settings.group(), document, ConfigType.JSON.getType());
        } catch (Exception exception) {
            throw new IllegalStateException("failed to publish CMDB configuration to Nacos");
        } finally {
            closeQuietly(service);
        }
        if (!published) {
            throw new IllegalStateException("Nacos rejected the CMDB configuration");
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
