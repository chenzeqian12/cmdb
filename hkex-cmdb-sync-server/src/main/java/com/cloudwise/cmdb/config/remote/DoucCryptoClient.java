package com.cloudwise.cmdb.config.remote;

import com.cloudwise.douc.dto.req.AsymmetricIn;
import com.cloudwise.douc.dto.resp.AsymmetricOut;
import com.cloudwise.douc.facadev3.AsymmetricSecurityFacade;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;

/** 调用 DOUC 业务密钥分支完成 CMDB 配置加解密。 */
public class DoucCryptoClient implements AutoCloseable {

    private static final int TIMEOUT_MILLIS = 15000;

    private final AsymmetricSecurityFacade facade;

    private final Runnable closer;

    DoucCryptoClient(AsymmetricSecurityFacade facade, Runnable closer) {
        this.facade = facade;
        this.closer = closer;
    }

    static void protectBootstrapLogging() {
        System.setProperty("nacos.logging.default.config.enabled", "false");
        System.clearProperty("nacos.logging.config");
        for (String name : java.util.List.of("org.apache.dubbo", "com.alibaba.nacos", "com.cloudwise.utils")) {
            org.apache.logging.log4j.core.config.Configurator.setLevel(name, org.apache.logging.log4j.Level.OFF);
        }
    }

    @SuppressWarnings("deprecation")
    public static DoucCryptoClient connect(String registryAddress, String applicationName) {
        if (registryAddress == null || registryAddress.isBlank()) {
            throw new IllegalStateException("missing required system property: dubbo.registry.address");
        }
        ApplicationConfig application = new ApplicationConfig(applicationName);
        application.setQosEnable(false);
        application.setSerializeCheckStatus("WARN");
        RegistryConfig registry = new RegistryConfig(registryAddress);
        ReferenceConfig<AsymmetricSecurityFacade> reference = new ReferenceConfig<>();
        reference.setApplication(application);
        reference.setRegistry(registry);
        reference.setInterface(AsymmetricSecurityFacade.class);
        reference.setGroup("DOUC_RPC_DUBBO");
        reference.setVersion("1.0.0");
        reference.setCheck(true);
        reference.setRetries(0);
        reference.setTimeout(TIMEOUT_MILLIS);
        try {
            return new DoucCryptoClient(reference.get(), reference::destroy);
        } catch (RuntimeException exception) {
            reference.destroy();
            throw new IllegalStateException("failed to connect to DOUC crypto service");
        }
    }

    public String encrypt(String plaintext) {
        return invoke(plaintext, true);
    }

    public String decrypt(String ciphertext) {
        return invoke(ciphertext, false);
    }

    private String invoke(String content, boolean encrypt) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException((encrypt ? "plaintext" : "ciphertext") + " must not be empty");
        }
        AsymmetricOut output;
        try {
            AsymmetricIn input = new AsymmetricIn();
            input.setContent(content);
            output = encrypt ? facade.encrypt(input) : facade.decrypt(input);
        } catch (Exception exception) {
            throw new IllegalStateException("DOUC " + (encrypt ? "encryption" : "decryption") + " failed");
        }
        if (output == null || output.getResult() == null || output.getResult().isBlank()) {
            throw new IllegalStateException("DOUC " + (encrypt ? "encrypt" : "decrypt")
                    + " returned an empty result");
        }
        return output.getResult();
    }

    @Override
    public void close() {
        closer.run();
    }
}
