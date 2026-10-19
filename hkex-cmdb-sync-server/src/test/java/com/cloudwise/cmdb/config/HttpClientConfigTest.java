package com.cloudwise.cmdb.config;

import com.cloudwise.cmdb.integration.servicenow.ServiceNowClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class HttpClientConfigTest {

    private final HttpClientConfig config = new HttpClientConfig();

    @Test
    void tokenAndMemberQueryUseProxyButDoucStaysDirect() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger directCalls = new AtomicInteger();
        HttpServer proxy = localServer();
        HttpServer direct = localServer();
        proxy.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            if (exchange.getRequestURI().getPath().equals("/oauth_token.do")) {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Assertions.assertThat(form).contains("grant_type=password", "client_id=test-client");
                respond(exchange, "{\"access_token\":\"test-token\",\"expires_in\":3600}");
            } else {
                Assertions.assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                        .isEqualTo("Bearer test-token");
                respond(exchange, "{\"result\":[{\"group\":\"Proxy_SUP\",\"user.email\":\"test@example.invalid\"}]}");
            }
        });
        direct.createContext("/probe", exchange -> {
            directCalls.incrementAndGet();
            respond(exchange, "{}");
        });
        proxy.start();
        direct.start();
        try {
            var properties = proxiedProperties(proxy.getAddress().getPort());
            ServiceNowClient client = new ServiceNowClient(config.serviceNowRestClient(properties), properties);
            Assertions.assertThat(client.fetchGroupMembers()).hasSize(1);
            Assertions.assertThat(requests).hasSize(2);
            Assertions.assertThat(requests.get(0)).startsWith("POST http://sn.example.invalid/oauth_token.do");
            Assertions.assertThat(requests.get(1))
                    .startsWith("GET http://sn.example.invalid/api/now/table/sys_user_grmember?");

            DoucProperties douc = new DoucProperties();
            douc.setBaseUrl("http://127.0.0.1:" + direct.getAddress().getPort());
            config.doucRestClient(douc).get().uri("/probe").retrieve().toBodilessEntity();
            Assertions.assertThat(directCalls.get()).isEqualTo(1);
            Assertions.assertThat(requests).hasSize(2);
        } finally {
            proxy.stop(0);
            direct.stop(0);
        }
    }

    @Test
    void disabledProxyKeepsExistingDirectClientBehavior() throws Exception {
        HttpServer direct = localServer();
        direct.createContext("/probe", exchange -> respond(exchange, "{}"));
        direct.start();
        try {
            ServiceNowProperties properties = proxiedProperties(1);
            properties.setProxyEnabled(false);
            properties.setProxyHost("");
            properties.setBaseUrl("http://127.0.0.1:" + direct.getAddress().getPort());
            Assertions.assertThat(config.serviceNowRestClient(properties).get().uri("/probe")
                    .retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful()).isTrue();
        } finally {
            direct.stop(0);
        }
    }

    @Test
    void httpsUsesConnectTunnelWithoutChangingGlobalProxy() throws Exception {
        String previousHost = System.getProperty("https.proxyHost");
        String previousPort = System.getProperty("https.proxyPort");
        final var selector = java.net.ProxySelector.getDefault();
        try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            proxy.setSoTimeout(5000);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var request = executor.submit(() -> {
                    try (var socket = proxy.accept()) {
                        socket.setSoTimeout(5000);
                        var reader = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.US_ASCII));
                        String firstLine = reader.readLine();
                        String header;
                        while ((header = reader.readLine()) != null && !header.isEmpty()) {
                            // 只消费隧道请求头，不记录凭据。
                        }
                        socket.getOutputStream().write(("HTTP/1.1 502 Bad Gateway\r\n"
                                + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        return firstLine;
                    }
                });
                ServiceNowProperties properties = proxiedProperties(proxy.getLocalPort());
                properties.setBaseUrl("https://sn.example.invalid");
                Assertions.assertThatThrownBy(() -> config.serviceNowRestClient(properties)
                                .post().uri("/oauth_token.do").retrieve().toBodilessEntity())
                        .isInstanceOf(ResourceAccessException.class);
                Assertions.assertThat(request.get(5, TimeUnit.SECONDS))
                        .isEqualTo("CONNECT sn.example.invalid:443 HTTP/1.1");
            } finally {
                executor.shutdownNow();
            }
        }
        Assertions.assertThat(System.getProperty("https.proxyHost")).isEqualTo(previousHost);
        Assertions.assertThat(System.getProperty("https.proxyPort")).isEqualTo(previousPort);
        Assertions.assertThat(java.net.ProxySelector.getDefault()).isSameAs(selector);
    }

    @Test
    void proxyFailureDoesNotFallBackToDirectConnection() throws Exception {
        AtomicInteger directCalls = new AtomicInteger();
        HttpServer direct = localServer();
        direct.createContext("/probe", exchange -> {
            directCalls.incrementAndGet();
            respond(exchange, "{}");
        });
        direct.start();
        try (ServerSocket unavailable = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            ServiceNowProperties properties = proxiedProperties(unavailable.getLocalPort());
            properties.setReadTimeout(200);
            properties.setBaseUrl("http://127.0.0.1:" + direct.getAddress().getPort());
            Assertions.assertThatThrownBy(() -> config.serviceNowRestClient(properties)
                            .get().uri("/probe").retrieve().toBodilessEntity())
                    .isInstanceOf(ResourceAccessException.class);
            Assertions.assertThat(directCalls.get()).isZero();
        } finally {
            direct.stop(0);
        }
    }

    @Test
    void proxyAddressIsNotResolvedDuringClientConstruction() {
        var properties = proxiedProperties(8080);
        properties.setProxyHost("localhost");
        try (var factories = org.mockito.Mockito.mockConstruction(
                org.springframework.http.client.SimpleClientHttpRequestFactory.class)) {
            config.serviceNowRestClient(properties);
            var proxy = org.mockito.ArgumentCaptor.forClass(java.net.Proxy.class);
            org.mockito.Mockito.verify(factories.constructed().get(0)).setProxy(proxy.capture());
            Assertions.assertThat(((InetSocketAddress) proxy.getValue().address()).isUnresolved()).isTrue();
        }
    }

    @Test
    void invalidProxyIsRejectedBeforeClientIsCreated() {
        var properties = proxiedProperties(8080);
        properties.setProxyHost("http://user:PRIVATE_PROXY_SENTINEL@proxy.invalid");
        Assertions.assertThatThrownBy(() -> config.serviceNowRestClient(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("servicenow.proxy-host")
                .hasMessageNotContaining("PRIVATE_PROXY_SENTINEL");
    }

    private HttpServer localServer() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private ServiceNowProperties proxiedProperties(int port) {
        var properties = new ServiceNowProperties();
        properties.setBaseUrl("http://sn.example.invalid");
        properties.setClientId("test-client");
        properties.setClientSecret("test-secret");
        properties.setUsername("test-user");
        properties.setPassword("test-password");
        properties.setConnectTimeout(1000);
        properties.setReadTimeout(2000);
        properties.setProxyEnabled(true);
        properties.setProxyHost("127.0.0.1");
        properties.setProxyPort(port);
        return properties;
    }

    private void respond(HttpExchange exchange, String body) throws java.io.IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
