package com.cloudwise.cmdb.config;

import com.cloudwise.cmdb.common.exception.IntegrationException;
import com.cloudwise.cmdb.integration.servicenow.ServiceNowClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertPathBuilderException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 「跳过 ServiceNow 证书校验」开关的行为验证。
 *
 * <p>本地起一个**自签名** HTTPS 服务，等价于现场那个不被 JDK 信任的证书链：
 * 开关关闭时必须失败（现状），开启时必须能正常换到 Token；
 * 同时确认同一台服务上的 DOUC 客户端仍然要求证书有效，开关没有变成全局设置。</p>
 */
class InsecureTlsHttpRequestFactoryTest {

    @TempDir
    Path directory;

    @Test
    void defaultConfigurationStillRejectsTheUntrustedCertificate() throws Exception {
        withSelfSignedServer((server, port) -> {
            ServiceNowProperties properties = properties(port, false);
            ServiceNowClient client = new ServiceNowClient(
                    config().serviceNowRestClient(properties), properties);
            Assertions.assertThatThrownBy(client::fetchGroupMembers)
                    .isInstanceOf(IntegrationException.class)
                    .hasRootCauseInstanceOf(CertPathBuilderException.class)
                    .hasStackTraceContaining("PKIX path building failed");
        });
    }

    @Test
    void enabledSwitchCompletesTokenExchangeAndMemberQuery() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        withSelfSignedServer((server, port) -> {
            ServiceNowProperties properties = properties(port, true);
            ServiceNowClient client = new ServiceNowClient(
                    config().serviceNowRestClient(properties), properties);

            Assertions.assertThat(client.fetchGroupMembers()).hasSize(1);
            Assertions.assertThat(tokenCalls.get()).isEqualTo(1);
        }, tokenCalls);
    }

    @Test
    void doucClientKeepsVerifyingCertificates() throws Exception {
        withSelfSignedServer((server, port) -> {
            DoucProperties douc = new DoucProperties();
            douc.setBaseUrl("https://127.0.0.1:" + port);
            Assertions.assertThatThrownBy(() -> config().doucRestClient(douc)
                            .get().uri("/probe").retrieve().toBodilessEntity())
                    .isInstanceOf(org.springframework.web.client.ResourceAccessException.class)
                    .hasRootCauseInstanceOf(CertPathBuilderException.class)
                    .hasStackTraceContaining("PKIX path building failed");
        });
    }

    private HttpClientConfig config() {
        return new HttpClientConfig();
    }

    private ServiceNowProperties properties(int port, boolean allowInsecureTls) {
        var properties = new ServiceNowProperties();
        properties.setBaseUrl("https://127.0.0.1:" + port);
        properties.setClientId("test-client");
        properties.setClientSecret("test-secret");
        properties.setUsername("test-user");
        properties.setPassword("test-password");
        properties.setConnectTimeout(2000);
        properties.setReadTimeout(5000);
        properties.setAllowInsecureTls(allowInsecureTls);
        return properties;
    }

    private void withSelfSignedServer(ServerScenario scenario) throws Exception {
        withSelfSignedServer(scenario, new AtomicInteger());
    }

    private void withSelfSignedServer(ServerScenario scenario, AtomicInteger tokenCalls) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext(createKeystore())));
        server.createContext("/oauth_token.do", exchange -> {
            tokenCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, "{\"access_token\":\"test-token\",\"expires_in\":3600}");
        });
        server.createContext("/api/now/table/sys_user_grmember", exchange -> respond(exchange,
                "{\"result\":[{\"group\":\"SelfSigned_SUP\",\"user.email\":\"test@example.invalid\"}]}"));
        server.start();
        try {
            scenario.run(server, server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    /** 用 JDK 自带 keytool 生成一次性自签名证书，避免往仓库里放二进制密钥库。 */
    private Path createKeystore() throws Exception {
        Path keystore = directory.resolve("self-signed.p12");
        if (Files.exists(keystore)) {
            return keystore;
        }
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(List.of(keytool, "-genkeypair", "-alias", "test",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit"))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assertions.assertThat(process.waitFor()).as("keytool output: %s", output).isZero();
        return keystore;
    }

    private SSLContext serverContext(Path keystore) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keystore)) {
            store.load(input, "changeit".toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private void respond(HttpExchange exchange, String body) throws java.io.IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    /** 让每个用例自己决定怎么用这台自签名服务器。 */
    @FunctionalInterface
    private interface ServerScenario {
        void run(HttpsServer server, int port) throws Exception;
    }
}
