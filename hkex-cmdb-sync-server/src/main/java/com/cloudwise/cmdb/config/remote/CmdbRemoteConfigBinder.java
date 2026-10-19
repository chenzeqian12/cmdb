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

/** 严格校验 Nacos 配置，并将解密后的 SN 凭据绑定为运行时属性。 */
public class CmdbRemoteConfigBinder {

    private static final Set<String> APPLICATION_FIELDS = Set.of(
            "spring.application.name", "spring.jackson.default-property-inclusion",
            "server.port", "server.shutdown", "logging.file.path",
            "hkex.sync.scheduled-enabled", "hkex.sync.on-startup",
            "hkex.sync.startup-delay-seconds", "hkex.sync.cron", "hkex.sync.member-sync",
            "hkex.heartbeat.enabled", "hkex.heartbeat.file", "hkex.heartbeat.interval-seconds");

    /**
     * 已移除的 XXL-Job 配置项。1.0.11.5 起每日同步改由进程内定时器负责（见
     * {@code GroupSyncTrigger}），这些字段一律不生效。
     *
     * <p>仍然放行而不是判为「不支持」：现场 Nacos 配置里通常还留着这一段，
     * 若直接拒绝，服务会卡在配置加载阶段起不来（日志系统尚未初始化），
     * 现场只能看到一条 stderr，很难定位。放行后由启动自检打 WARN 提示删除。</p>
     */
    public static final Set<String> DEPRECATED_XXL_KEYS = Set.of(
            "xxl.job.admin.address", "xxl.job.accessToken", "xxl.job.executor.app.name",
            "xxl.job.executor.port", "xxl.job.executor.log.path",
            "xxl.job.executor.log.retention.days", "xxl.job.executor.ip");

    private static final Set<String> SERVICENOW_FIELDS = Set.of(
            "base-url", "token-path", "client-id", "client-secret",
            "username-encrypted", "password-encrypted",
            "group-query", "page-size", "connect-timeout", "read-timeout",
            "token-refresh-ahead-seconds", "proxy-enabled", "proxy-host", "proxy-port",
            "allow-insecure-tls");

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

    public CmdbEncryptedCredentials encryptedCredentials(String documentJson) {
        Map<String, Object> flattened = parseAndValidate(documentJson);
        return new CmdbEncryptedCredentials(
                requiredText(flattened, "servicenow.username-encrypted"),
                requiredText(flattened, "servicenow.password-encrypted"));
    }

    public CmdbRemoteConfigSnapshot bind(String documentJson, String username, String password) {
        Map<String, Object> flattened = parseAndValidate(documentJson);
        flattened.remove("servicenow.username-encrypted");
        flattened.remove("servicenow.password-encrypted");
        flattened.put("servicenow.username", requireDecrypted("servicenow.username", username));
        flattened.put("servicenow.password", requireDecrypted("servicenow.password", password));
        try {
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
            throw new IllegalStateException("failed to bind CMDB configuration");
        }
    }

    private Map<String, Object> parseAndValidate(String documentJson) {
        if (documentJson == null || documentJson.isBlank()) {
            throw new IllegalStateException("CMDB configuration is empty");
        }
        try {
            Map<String, Object> document = objectMapper.readValue(
                    documentJson, new TypeReference<Map<String, Object>>() { });
            validateDocumentShape(document);
            Map<String, Object> flattened = new LinkedHashMap<>();
            flattenInto(flattened, "", document);
            validateApplicationFields(flattened);
            if (!flattened.keySet().containsAll(APPLICATION_FIELDS)) {
                throw new IllegalStateException("application configuration is incomplete; use the full JSON template");
            }
            validateApplicationValues(flattened);
            validateServiceNowOptionals(flattened);
            requiredText(flattened, "servicenow.username-encrypted");
            requiredText(flattened, "servicenow.password-encrypted");
            return flattened;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse or validate CMDB configuration");
        }
    }

    private void validateServiceNowOptionals(Map<String, Object> properties) {
        for (String field : List.of("proxy-enabled", "proxy-host", "proxy-port", "allow-insecure-tls")) {
            String key = "servicenow." + field;
            if (properties.containsKey(key)) {
                Object value = properties.get(key);
                boolean validType = switch (field) {
                    case "proxy-host" -> value instanceof String;
                    case "proxy-port" -> value instanceof Integer;
                    // 布尔开关不接受字符串：写成 "true" 时按类型拒绝，避免现场以为关掉了其实开着。
                    default -> value instanceof Boolean;
                };
                if (!validType) {
                    throw new IllegalStateException("invalid ServiceNow field type: " + key);
                }
            }
        }
        Binder binder = new Binder(List.of(new MapConfigurationPropertySource(properties)));
        ServiceNowProperties serviceNow = binder.bind("servicenow", Bindable.of(ServiceNowProperties.class))
                .orElseThrow(() -> new IllegalStateException("servicenow configuration is empty"));
        serviceNow.validateProxy();
    }

    private String requiredText(Map<String, Object> properties, String key) {
        Object value = properties.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("empty encrypted credential field: " + key);
        }
        return String.valueOf(value);
    }

    private String requireDecrypted(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("decrypted credential is empty: " + key);
        }
        return value;
    }

    private void validateDocumentShape(Map<String, Object> document) {
        rejectUnknown(document, Set.of("servicenow", "douc", "spring", "server", "logging", "xxl", "hkex"),
                "CMDB config root");
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

    private void validateApplicationFields(Map<String, Object> properties) {
        properties.forEach((key, value) -> {
            if (key.startsWith("servicenow.") || key.startsWith("douc.")) {
                return;
            }
            boolean isLoggingLevel = key.startsWith("logging.level.")
                    && key.length() > "logging.level.".length();
            boolean isDeprecated = DEPRECATED_XXL_KEYS.contains(key);
            if (!APPLICATION_FIELDS.contains(key) && !isLoggingLevel && !isDeprecated) {
                throw new IllegalStateException("CMDB configuration contains unsupported field: " + key);
            }
            if (value == null || value instanceof List<?>) {
                throw new IllegalStateException("CMDB configuration field must be scalar: " + key);
            }
            // 已废弃字段只做形状检查，不校验取值：现场留着的旧值不该再挡住启动
            if (isLoggingLevel) {
                validateLoggingLevel(key, value);
            }
        });
    }

    private void validateLoggingLevel(String key, Object value) {
        String logger = key.substring("logging.level.".length());
        if (!logger.matches("[A-Za-z][A-Za-z0-9]*(\\.[A-Za-z][A-Za-z0-9]*)*")) {
            throw new IllegalStateException("unsupported logging logger name");
        }
        String level = String.valueOf(value).toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "OFF").contains(level)) {
            throw new IllegalStateException("invalid logging level");
        }
        String normalizedLogger = logger.toLowerCase(java.util.Locale.ROOT);
        if (List.of("org.apache.dubbo", "com.alibaba.nacos", "com.cloudwise.utils").stream()
                .anyMatch(name -> normalizedLogger.equals(name) || normalizedLogger.startsWith(name + "."))
                && !"OFF".equals(level)) {
            throw new IllegalStateException("credential-bearing logger must remain OFF");
        }
        boolean isBusinessLogger = logger.equals("com.cloudwise.cmdb") || logger.startsWith("com.cloudwise.cmdb.");
        if (!isBusinessLogger && Set.of("DEBUG", "TRACE").contains(level)) {
            throw new IllegalStateException("DEBUG and TRACE are restricted to CMDB business loggers");
        }
    }

    private void validateApplicationValues(Map<String, Object> properties) {
        Binder binder = new Binder(List.of(new MapConfigurationPropertySource(properties)));
        int port = requiredValue(binder, "server.port", Integer.class);
        if (port < 0 || port > 65535) {
            throw new IllegalStateException("port must be between 0 and 65535: server.port");
        }
        for (String key : List.of("hkex.sync.scheduled-enabled", "hkex.sync.on-startup",
                "hkex.sync.member-sync", "hkex.heartbeat.enabled")) {
            requiredValue(binder, key, Boolean.class);
        }
        for (String key : List.of("hkex.sync.startup-delay-seconds", "hkex.heartbeat.interval-seconds")) {
            long value = requiredValue(binder, key, Long.class);
            if (value < 0 || (key.equals("hkex.heartbeat.interval-seconds") && value == 0)) {
                throw new IllegalStateException("invalid time interval: " + key);
            }
        }
        requiredValue(binder, "server.shutdown", org.springframework.boot.web.server.Shutdown.class);
        requiredValue(binder, "spring.jackson.default-property-inclusion", com.fasterxml.jackson.annotation.JsonInclude.Include.class);
        org.springframework.scheduling.support.CronExpression.parse(requiredValue(binder, "hkex.sync.cron", String.class));
        // logging.file.path 不校验、允许为空：真正决定日志目录的是启动脚本注入的 cwLogRootPath
        // （见 log4j2.xml），Nacos 这个值只在缺前者时兜底。强制非空只会逼现场填一个相对路径，
        // 万一 cwLogRootPath 缺失就把日志写到进程工作目录下，反而更难排查。
        // 两者同时存在且不一致时，启动自检会打 WARN，见 StartupStatusReporter。
    }

    private <T> T requiredValue(Binder binder, String key, Class<T> type) {
        return binder.bind(key, type).orElseThrow(() -> new IllegalStateException("empty application field: " + key));
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
