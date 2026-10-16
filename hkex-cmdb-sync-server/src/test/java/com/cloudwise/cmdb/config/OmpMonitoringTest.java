package com.cloudwise.cmdb.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

class OmpMonitoringTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static ConfigurableApplicationContext context;

    private static String baseUrl;

    @TempDir
    static Path directory;

    @BeforeAll
    static void startServer() {
        SpringApplication application = new SpringApplication(MonitoringApplication.class);
        application.setRegisterShutdownHook(false);
        context = application.run("--cmdb.remote-config.enabled=false", "--dubbo.enabled=false",
                "--server.port=0", "--server.address=127.0.0.1",
                "--logging.file.path=" + directory,
                "--servicenow.password=DO_NOT_EXPOSE_SN_CREDENTIAL");
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterAll
    static void stopServer() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void healthIsAvailableAtOmpRootPathWithoutDetails() throws Exception {
        HttpResponse<String> response = request("GET", "/health");

        Assertions.assertThat(response.statusCode()).isEqualTo(200);
        Assertions.assertThat(new ObjectMapper().readTree(response.body()))
                .isEqualTo(new ObjectMapper().readTree("{\"status\":\"UP\"}"));
    }

    @Test
    void unhealthyContributorReturnsServiceUnavailableWithoutDetails() throws Exception {
        var registry = context.getBean(
                org.springframework.boot.actuate.health.HealthContributorRegistry.class);
        registry.registerContributor("failedDependency", (org.springframework.boot.actuate.health.HealthIndicator)
                () -> org.springframework.boot.actuate.health.Health.down()
                        .withDetail("credential", "DO_NOT_EXPOSE_SN_CREDENTIAL").build());
        try {
            HttpResponse<String> response = request("GET", "/health");

            Assertions.assertThat(response.statusCode()).isEqualTo(503);
            Assertions.assertThat(new ObjectMapper().readTree(response.body()))
                    .isEqualTo(new ObjectMapper().readTree("{\"status\":\"DOWN\"}"));
        } finally {
            registry.unregisterContributor("failedDependency");
        }
    }

    @Test
    void metricsExposesPrometheusJvmAndProcessSamples() throws Exception {
        HttpResponse<String> response = request("GET", "/metrics");

        Assertions.assertThat(response.statusCode()).isEqualTo(200);
        Assertions.assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("text/plain");
        Assertions.assertThat(response.body()).contains("jvm_memory_used_bytes", "process_uptime_seconds")
                .doesNotContain("DO_NOT_EXPOSE_SN_CREDENTIAL", "servicenow.password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/env", "/configprops", "/heapdump", "/loggers", "/beans",
            "/mappings", "/info", "/actuator", "/actuator/health", "/health/diskSpace"})
    void nonWhitelistedManagementPathsAreUnavailable(String path) throws Exception {
        HttpResponse<String> response = request("GET", path);

        Assertions.assertThat(response.statusCode()).isEqualTo(404);
        Assertions.assertThat(response.body()).doesNotContain("DO_NOT_EXPOSE_SN_CREDENTIAL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/shutdown", "/restart", "/env", "/loggers/root"})
    void managementMutationPathsAreUnavailable(String path) throws Exception {
        Assertions.assertThat(request("POST", path).statusCode()).isEqualTo(404);
    }

    private static HttpResponse<String> request(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class MonitoringApplication {
    }
}
