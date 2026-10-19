package com.cloudwise.cmdb.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServiceNowProxyTest {

    @ParameterizedTest
    @ValueSource(strings = {"webproxy.hkex", "127.0.0.1", "proxy", "proxy.hkex."})
    void enabledProxyAcceptsHostnamesAndIpv4(String host) {
        var properties = enabledProxy(host);
        Assertions.assertThatCode(properties::validateProxy).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65535})
    void enabledProxyAcceptsPortBoundaries(int port) {
        var properties = enabledProxy("webproxy.hkex");
        properties.setProxyPort(port);
        Assertions.assertThatCode(properties::validateProxy).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a..b", "-proxy", "proxy-", "http://proxy", "proxy:8080",
            "proxy/path", "user:PRIVATE_PROXY_SENTINEL@proxy", "proxy\nname"})
    void malformedHostIsRejectedWithoutEcho(String host) {
        var properties = enabledProxy(host);
        Assertions.assertThatThrownBy(properties::validateProxy)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("servicenow.proxy-host")
                .hasMessageNotContaining("PRIVATE_PROXY_SENTINEL");
    }

    @Test
    void nullAndOverlongHostsAreRejected() {
        Assertions.assertThatThrownBy(enabledProxy(null)::validateProxy)
                .hasMessageContaining("servicenow.proxy-host");
        Assertions.assertThatThrownBy(enabledProxy("x".repeat(254))::validateProxy)
                .hasMessageContaining("servicenow.proxy-host");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65536})
    void invalidPortIsRejected(int port) {
        var properties = enabledProxy("webproxy.hkex");
        properties.setProxyPort(port);
        Assertions.assertThatThrownBy(properties::validateProxy)
                .hasMessageContaining("servicenow.proxy-port");
    }

    @Test
    void disabledProxyDoesNotRequireHostOrPort() {
        var properties = new ServiceNowProperties();
        properties.setProxyHost(null);
        properties.setProxyPort(0);
        Assertions.assertThatCode(properties::validateProxy).doesNotThrowAnyException();
    }

    private ServiceNowProperties enabledProxy(String host) {
        var properties = new ServiceNowProperties();
        properties.setProxyEnabled(true);
        properties.setProxyHost(host);
        return properties;
    }
}
