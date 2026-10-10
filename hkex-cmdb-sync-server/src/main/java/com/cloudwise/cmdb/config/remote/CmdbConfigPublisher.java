package com.cloudwise.cmdb.config.remote;

import com.cloudwise.utils.GlobalPropertiesUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 受控发布端：校验明文文件，经 DOUC 加密后只向 Nacos 发布密文信封。 */
public final class CmdbConfigPublisher {

    private CmdbConfigPublisher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: publish-cmdb-config <plaintext-json-file>");
            System.exit(2);
        }
        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("plaintext configuration file does not exist");
        }

        String plaintext = Files.readString(source, StandardCharsets.UTF_8);
        ObjectMapper objectMapper = new ObjectMapper();
        new CmdbRemoteConfigBinder(objectMapper).bind(plaintext);

        requireSystemProperty("cwNacosServer");
        GlobalPropertiesUtils.loadNacosProperties();
        String registryAddress = requireSystemProperty("dubbo.registry.address");
        String ciphertext;
        try (DoucCryptoClient client = DoucCryptoClient.connect(
                registryAddress, "hkex-cmdb-sync-config-publisher")) {
            ciphertext = client.encrypt(plaintext);
        }

        CmdbConfigEnvelope envelope = new CmdbConfigEnvelope(
                CmdbConfigEnvelope.SUPPORTED_SCHEMA_VERSION,
                CmdbConfigEnvelope.SUPPORTED_CIPHER_TYPE,
                ciphertext);
        NacosCmdbConfigRepository repository = new NacosCmdbConfigRepository(
                CmdbRemoteConfigSettings.defaults(), objectMapper);
        repository.publishEnvelope(envelope);

        byte[] digestBytes = MessageDigest.getInstance("SHA-256")
                .digest(ciphertext.getBytes(StandardCharsets.UTF_8));
        String digest = HexFormat.of().formatHex(digestBytes);
        System.out.printf("published schemaVersion=1 ciphertextLength=%d ciphertextSha256=%s%n",
                ciphertext.length(), digest.substring(0, 12));
    }

    private static String requireSystemProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required system property: " + name);
        }
        return value;
    }
}
