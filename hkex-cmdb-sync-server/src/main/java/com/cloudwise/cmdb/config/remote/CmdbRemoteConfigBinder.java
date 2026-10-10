package com.cloudwise.cmdb.config.remote;

import com.cloudwise.cmdb.config.ConfigValidator;
import com.cloudwise.cmdb.config.DoucProperties;
import com.cloudwise.cmdb.config.ServiceNowProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 把 DOUC 解密结果严格绑定为 CMDB 可使用的两组业务配置。 */
public class CmdbRemoteConfigBinder {

    private static final Set<String> ROOT_FIELDS = Set.of("servicenow", "douc");

    private static final Set<String> SERVICENOW_FIELDS = Set.of(
            "base-url", "token-path", "client-id", "client-secret", "username", "password",
            "group-query", "page-size", "connect-timeout", "read-timeout",
            "token-refresh-ahead-seconds");

    private static final Set<String> DOUC_FIELDS = Set.of(
            "base-url", "gateway-prefix", "api-prefix", "auth-mode", "app-key", "app-secret",
            "sign-mode", "cookie", "account-id", "top-account-id", "user-id", "user-name",
            "language", "parent-group-id", "default-group-type", "user-page-size",
            "connect-timeout", "read-timeout", "dry-run", "delete-missing-groups",
            "group-sync-state-file");

    private final ObjectMapper objectMapper;

    public CmdbRemoteConfigBinder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CmdbRemoteConfigSnapshot bind(String plaintextJson) {
        if (plaintextJson == null || plaintextJson.isBlank()) {
            throw new IllegalStateException("decrypted CMDB configuration is empty");
        }
        try {
            Map<String, Object> document = objectMapper.readValue(
                    plaintextJson, new TypeReference<Map<String, Object>>() { });
            validateDocumentShape(document);
            Map<String, Object> flattened = new LinkedHashMap<>();
            flattenInto(flattened, "", document);
            Binder binder = new Binder(List.of(new MapConfigurationPropertySource(flattened)));
            ServiceNowProperties serviceNow = binder.bind(
                            "servicenow", Bindable.of(ServiceNowProperties.class))
                    .orElseThrow(() -> new IllegalStateException("servicenow configuration is empty"));
            DoucProperties douc = binder.bind("douc", Bindable.of(DoucProperties.class))
                    .orElseThrow(() -> new IllegalStateException("douc configuration is empty"));
            new ConfigValidator(serviceNow, douc).validate();
            return new CmdbRemoteConfigSnapshot(serviceNow, douc, Map.copyOf(flattened));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse decrypted CMDB configuration", exception);
        }
    }

    private void validateDocumentShape(Map<String, Object> document) {
        rejectUnknown(document, ROOT_FIELDS, "CMDB config root");
        validateSection(document, "servicenow", SERVICENOW_FIELDS);
        validateSection(document, "douc", DOUC_FIELDS);
    }

    private void validateSection(Map<String, Object> document, String name, Set<String> allowed) {
        Object raw = document.get(name);
        if (!(raw instanceof Map<?, ?> values)) {
            throw new IllegalStateException(name + " configuration must be an object");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        rejectUnknown(normalized, allowed, name + " configuration");
        String nested = normalized.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof List<?>)
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst()
                .orElse(null);
        if (nested != null) {
            throw new IllegalStateException(name + " configuration field must be scalar: " + nested);
        }
    }

    private void rejectUnknown(Map<String, Object> values, Set<String> allowed, String location) {
        String unknown = values.keySet().stream()
                .filter(key -> !allowed.contains(key))
                .sorted()
                .findFirst()
                .orElse(null);
        if (unknown != null) {
            throw new IllegalStateException(location + " contains unsupported field: " + unknown);
        }
    }

    private void flattenInto(Map<String, Object> target, String prefix, Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                String next = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
                flattenInto(target, next, nested);
            });
            return;
        }
        target.put(prefix, value);
    }
}
