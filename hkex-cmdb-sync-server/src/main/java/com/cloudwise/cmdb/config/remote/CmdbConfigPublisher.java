package com.cloudwise.cmdb.config.remote;

import com.cloudwise.utils.GlobalPropertiesUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

public final class CmdbConfigPublisher {

    private CmdbConfigPublisher() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception exception) {
            System.err.println("CMDB configuration operation failed (" + exception.getClass().getSimpleName()
                    + "); check input and bootstrap settings");
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        boolean encryptOnly = args.length == 3 && "--encrypt-only".equals(args[0]);
        if (!encryptOnly && args.length != 1) {
            throw new IllegalArgumentException(
                    "usage: publishCmdbConfig <plaintext-json> | --encrypt-only <plaintext-json> <encrypted-json>");
        }
        Path source = Path.of(args[encryptOnly ? 1 : 0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("plaintext configuration file does not exist");
        }
        Path output = encryptOnly ? Path.of(args[2]).toAbsolutePath().normalize() : null;
        if (output != null && Files.exists(output)) {
            throw new java.nio.file.FileAlreadyExistsException("encrypted output already exists");
        }
        String plaintext = Files.readString(source, StandardCharsets.UTF_8);
        ObjectMapper objectMapper = new ObjectMapper();
        DoucCryptoClient.protectBootstrapLogging();
        requireSystemProperty("cwNacosServer");
        GlobalPropertiesUtils.loadNacosProperties();
        String registryAddress = requireSystemProperty("dubbo.registry.address");
        try (DoucCryptoClient client = DoucCryptoClient.connect(
                registryAddress, "hkex-cmdb-sync-config-publisher")) {
            process(plaintext, output, client, new NacosCmdbConfigRepository(
                    CmdbRemoteConfigSettings.defaults()), objectMapper);
        }
    }

    static void process(String plaintext, Path output, DoucCryptoClient crypto,
                        NacosCmdbConfigRepository repository, ObjectMapper objectMapper) throws Exception {
        ObjectNode root = readObject(plaintext, objectMapper);
        JsonNode serviceNowNode = root.get("servicenow");
        if (!(serviceNowNode instanceof ObjectNode serviceNow)) {
            throw new IllegalStateException("servicenow configuration must be an object");
        }
        rejectExistingCiphertext(serviceNow);
        String username = removeRequiredText(serviceNow, "username");
        String password = removeRequiredText(serviceNow, "password");
        serviceNow.put("username-encrypted", "validation-placeholder");
        serviceNow.put("password-encrypted", "validation-placeholder");
        new CmdbRemoteConfigBinder(objectMapper).bind(
                objectMapper.writeValueAsString(root), username, password);
        serviceNow.put("username-encrypted", encryptAndVerify(crypto, username, "username"));
        serviceNow.put("password-encrypted", encryptAndVerify(crypto, password, "password"));
        String document = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        if (output == null) {
            repository.publishDocument(document);
        } else {
            try (var channel = Files.newByteChannel(output,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
                writer.write(document);
            }
        }
        byte[] digestBytes = MessageDigest.getInstance("SHA-256")
                .digest(document.getBytes(StandardCharsets.UTF_8));
        String digest = HexFormat.of().formatHex(digestBytes);
        System.out.printf("%s encryptedFields=2 documentLength=%d documentSha256=%s%n",
                output == null ? "published" : "encrypted", document.length(), digest.substring(0, 12));
    }

    private static ObjectNode readObject(String document, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(document);
            if (root instanceof ObjectNode object) {
                return object;
            }
            throw new IllegalStateException("CMDB configuration must be a JSON object");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse CMDB configuration");
        }
    }

    private static void rejectExistingCiphertext(ObjectNode serviceNow) {
        if (serviceNow.has("username-encrypted") || serviceNow.has("password-encrypted")) {
            throw new IllegalStateException("source configuration already contains encrypted ServiceNow credentials");
        }
    }

    private static String removeRequiredText(ObjectNode object, String field) {
        JsonNode value = object.remove(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("servicenow." + field + " must be a non-empty string");
        }
        return value.textValue();
    }

    private static String encryptAndVerify(DoucCryptoClient crypto, String plaintext, String field) {
        String ciphertext = crypto.encrypt(plaintext);
        String roundTripped = crypto.decrypt(ciphertext);
        if (!plaintext.equals(roundTripped)) {
            throw new IllegalStateException("DOUC round trip mismatch for servicenow." + field);
        }
        return ciphertext;
    }

    private static String requireSystemProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required system property: " + name);
        }
        return value;
    }
}
