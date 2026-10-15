package com.cloudwise.cmdb.config.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class CmdbConfigPublisherTest {

    @TempDir
    Path directory;

    @Test
    void encryptOnlyWritesReadableDocumentWithTwoEncryptedFields() throws Exception {
        DoucCryptoClient crypto = Mockito.mock(DoucCryptoClient.class);
        NacosCmdbConfigRepository repository = Mockito.mock(NacosCmdbConfigRepository.class);
        String plaintext = validDocument();
        mockRoundTrip(crypto);
        Path output = directory.resolve("encrypted.json");

        CmdbConfigPublisher.process(plaintext, output, crypto, repository, new ObjectMapper());

        var document = new ObjectMapper().readTree(Files.readString(output));
        Assertions.assertThat(document.at("/servicenow/username-encrypted").asText())
                .isEqualTo("encrypted-sn-user");
        Assertions.assertThat(document.at("/servicenow/password-encrypted").asText())
                .isEqualTo("encrypted-sn-password");
        Assertions.assertThat(document.at("/servicenow/username").isMissingNode()).isTrue();
        Assertions.assertThat(document.at("/servicenow/password").isMissingNode()).isTrue();
        Assertions.assertThat(document.at("/servicenow/client-secret").asText()).isEqualTo("client-secret");
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void publishModeSendsDocumentWithOnlyCredentialsEncrypted() throws Exception {
        DoucCryptoClient crypto = Mockito.mock(DoucCryptoClient.class);
        NacosCmdbConfigRepository repository = Mockito.mock(NacosCmdbConfigRepository.class);
        String plaintext = validDocument();
        mockRoundTrip(crypto);

        CmdbConfigPublisher.process(plaintext, null, crypto, repository, new ObjectMapper());

        var document = org.mockito.ArgumentCaptor.forClass(String.class);
        Mockito.verify(repository).publishDocument(document.capture());
        Assertions.assertThat(document.getValue())
                .contains("\"username-encrypted\"", "\"password-encrypted\"", "\"client-secret\"")
                .doesNotContain("\"username\" : \"sn-user\"", "\"password\" : \"sn-password\"");
    }

    @Test
    void encryptOnlyRefusesToOverwriteExistingFile() throws Exception {
        Path output = directory.resolve("source.json");
        Files.writeString(output, "existing-source");
        DoucCryptoClient crypto = Mockito.mock(DoucCryptoClient.class);
        String plaintext = validDocument();
        mockRoundTrip(crypto);

        Assertions.assertThatThrownBy(() -> CmdbConfigPublisher.process(
                plaintext, output, crypto, Mockito.mock(NacosCmdbConfigRepository.class), new ObjectMapper()))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        Assertions.assertThat(Files.readString(output)).isEqualTo("existing-source");
    }

    @Test
    void commandEncryptOnlyUsesBootstrapButDoesNotPublish() throws Exception {
        Path source = directory.resolve("source.json");
        Path output = directory.resolve("encrypted.json");
        Files.writeString(source, validDocument());
        DoucCryptoClient client = Mockito.mock(DoucCryptoClient.class);
        mockRoundTrip(client);
        String previousServer = System.getProperty("cwNacosServer");
        String previousRegistry = System.getProperty("dubbo.registry.address");
        System.setProperty("cwNacosServer", "dummy-nacos");
        System.setProperty("dubbo.registry.address", "dummy-registry");
        try (var globals = Mockito.mockStatic(com.cloudwise.utils.GlobalPropertiesUtils.class);
                var crypto = Mockito.mockStatic(DoucCryptoClient.class);
                var repositories = Mockito.mockConstruction(NacosCmdbConfigRepository.class)) {
            crypto.when(() -> DoucCryptoClient.connect("dummy-registry", "hkex-cmdb-sync-config-publisher"))
                    .thenReturn(client);
            CmdbConfigPublisher.run(new String[]{"--encrypt-only", source.toString(), output.toString()});
            Assertions.assertThat(Files.getPosixFilePermissions(output))
                    .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Mockito.verifyNoInteractions(repositories.constructed().get(0));
            Mockito.verify(client).close();
        } finally {
            restore("cwNacosServer", previousServer);
            restore("dubbo.registry.address", previousRegistry);
        }
    }

    @Test
    void invalidCommandAndMissingSourceFailBeforeBootstrap() {
        Assertions.assertThatThrownBy(() -> CmdbConfigPublisher.run(new String[]{}))
                .hasMessageContaining("usage");
        Assertions.assertThatThrownBy(() -> CmdbConfigPublisher.run(
                new String[]{directory.resolve("missing.json").toString()}))
                .hasMessageContaining("does not exist");
    }

    @Test
    void commandRefusesExistingOutputBeforeBootstrap() throws Exception {
        Path source = directory.resolve("source.json");
        Files.writeString(source, validDocument());
        Assertions.assertThatThrownBy(() -> CmdbConfigPublisher.run(
                new String[]{"--encrypt-only", source.toString(), source.toString()}))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }

    @Test
    void invalidConfigurationFailsBeforeCallingDouc() throws Exception {
        DoucCryptoClient crypto = Mockito.mock(DoucCryptoClient.class);
        String invalid = validDocument().replace("\"server\":", "\"unsupported\": {}, \"server\":");

        Assertions.assertThatThrownBy(() -> CmdbConfigPublisher.process(
                invalid, directory.resolve("unused.json"), crypto,
                Mockito.mock(NacosCmdbConfigRepository.class), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported");
        Mockito.verifyNoInteractions(crypto);
    }

    @Test
    void roundTripMismatchDoesNotPublishOrWriteOutput() throws Exception {
        DoucCryptoClient crypto = Mockito.mock(DoucCryptoClient.class);
        Mockito.when(crypto.encrypt(Mockito.anyString())).thenReturn("ciphertext");
        Mockito.when(crypto.decrypt("ciphertext")).thenReturn("different-plaintext");
        NacosCmdbConfigRepository repository = Mockito.mock(NacosCmdbConfigRepository.class);
        Path output = directory.resolve("unused.json");

        Assertions.assertThatThrownBy(() -> CmdbConfigPublisher.process(
                validDocument(), output, crypto, repository, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("round trip mismatch");
        Assertions.assertThat(output).doesNotExist();
        Mockito.verifyNoInteractions(repository);
    }

    private void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private void mockRoundTrip(DoucCryptoClient crypto) {
        Mockito.when(crypto.encrypt(Mockito.anyString()))
                .thenAnswer(invocation -> "encrypted-" + invocation.getArgument(0, String.class));
        Mockito.when(crypto.decrypt(Mockito.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).substring("encrypted-".length()));
    }

    private String validDocument() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.readTree(Files.readString(Path.of("conf/cmdb-app.full.json.example")));
        var serviceNow = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("servicenow");
        serviceNow.put("base-url", "https://example.service-now.com");
        serviceNow.put("client-id", "client-id");
        serviceNow.put("client-secret", "client-secret");
        serviceNow.put("username", "sn-user");
        serviceNow.put("password", "sn-password");
        var douc = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("douc");
        douc.put("base-url", "https://douc.example.com");
        for (String key : java.util.List.of("app-key", "app-secret", "account-id", "top-account-id", "user-id")) {
            douc.put(key, "dummy-value");
        }
        return mapper.writeValueAsString(root);
    }
}
