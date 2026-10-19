package com.cloudwise.cmdb.config;

import com.cloudwise.cmdb.config.remote.CmdbRemoteConfigInitializer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * {@link StartupStatusReporter} 单元测试。不加载 Spring 上下文：用 PropertySource 喂配置，
 * 直接断言生成的行，避免为了几条日志拉起整个应用。
 *
 * @author hkex-cmdb-sync
 */
class StartupStatusReporterTest {

    private Map<String, Object> properties;

    private ServiceNowProperties serviceNow;

    private DoucProperties douc;

    @BeforeEach
    void setUp() {
        properties = new LinkedHashMap<>();
        properties.put("server.port", "18098");
        properties.put("hkex.sync.scheduled-enabled", "true");
        properties.put("hkex.sync.on-startup", "true");
        properties.put("hkex.sync.startup-delay-seconds", "30");
        properties.put("hkex.sync.cron", "0 0 3 * * ?");
        properties.put("hkex.sync.member-sync", "true");
        properties.put("hkex.heartbeat.enabled", "true");
        properties.put("hkex.heartbeat.file", "./heartbeat");
        properties.put("hkex.heartbeat.interval-seconds", "30");
        properties.put("cmdb.remote-config.group", "hkex-cmdb-sync");
        properties.put("cmdb.remote-config.data-id", "hkex-cmdb-sync-config.json");

        serviceNow = new ServiceNowProperties();
        serviceNow.setBaseUrl("https://hkex.service-now.com");
        serviceNow.setUsername("sync-user");
        serviceNow.setPassword("sync-password");

        douc = new DoucProperties();
        douc.setBaseUrl("http://10.9.9.9:18241");
        douc.setAuthMode("direct");
        douc.setDryRun(true);
    }

    @Test
    @DisplayName("每一行都带 CMDB-STARTUP-CHECK 前缀，便于现场一条 grep 捞全")
    void reportLinesAllCarryThePrefix() {
        List<String> lines = report(true);

        Assertions.assertThat(lines)
                .isNotEmpty()
                .allSatisfy(line -> Assertions.assertThat(line).startsWith(StartupStatusReporter.TAG));
    }

    @Test
    @DisplayName("制品行在类目录下不抛异常，取值兜底为 unknown")
    void reportCarriesArtifactPlaceholder() {
        Assertions.assertThat(String.join("\n", report(true)))
                .contains(StartupStatusReporter.TAG + " artifact=unknown");
    }

    @Test
    @DisplayName("是否来自 Nacos 与 Group/Data ID 都打在状态行里")
    void reportShowsConfigSource() {
        Assertions.assertThat(String.join("\n", report(true)))
                .contains("config-source=nacos group=hkex-cmdb-sync data-id=hkex-cmdb-sync-config.json");

        Assertions.assertThat(String.join("\n", report(false)))
                .contains("config-source=local-only(NOT from Nacos)");
    }

    @Test
    @DisplayName("没有 Web 容器时端口回落到配置值")
    void reportFallsBackToConfiguredPort() {
        Assertions.assertThat(String.join("\n", report(true))).contains("http-port=18098");
    }

    @Test
    @DisplayName("自动同步全开且 dry-run 时，结论行说明会跑但不写库")
    void reportShowsEnabledAutoSyncInDryRun() {
        Assertions.assertThat(String.join("\n", report(true))).contains(
                "auto-sync=ENABLED triggers=daily-cron+on-startup; next-run=~30s after startup;"
                        + " write-mode=DRY-RUN (DOUC is not modified)");
    }

    @Test
    @DisplayName("总闸关闭时，结论行明确说明只剩手工触发")
    void reportShowsDisabledAutoSync() {
        properties.put("hkex.sync.scheduled-enabled", "false");
        String report = String.join("\n", report(true));

        Assertions.assertThat(report)
                .contains("auto-sync=DISABLED (manual HTTP trigger only)")
                .contains("NOTE scheduled-enabled=false");
    }

    @Test
    @DisplayName("on-startup=false 时提示本次重启不会同步")
    void reportNotesStartupSyncIsOff() {
        properties.put("hkex.sync.on-startup", "false");
        String report = String.join("\n", report(true));

        Assertions.assertThat(report)
                .contains("NOTE on-startup=false: this restart does NOT run a sync")
                .contains("triggers=daily-cron;");
    }

    @Test
    @DisplayName("dry-run 关闭时给真写告警，并点出会写向哪个 DOUC")
    void reportWarnsWhenDryRunIsOff() {
        douc.setDryRun(false);
        String report = String.join("\n", report(true));

        Assertions.assertThat(report)
                .contains("write-mode dry-run=false delete-missing-groups=false (WILL MODIFY DOUC)")
                .contains("WARN dry-run=false: every sync WRITES to douc.base-url=http://10.9.9.9:18241")
                .contains("WARN dry-run=false with on-startup=true: a real write starts 30s after this line")
                .contains("write-mode=LIVE (DOUC IS modified)");
    }

    @Test
    @DisplayName("Nacos 里残留 xxl 配置时给告警，删干净后不再出现 XXL 相关行")
    void reportWarnsWhenXxlJobConfigIsLeftBehind() {
        properties.put("xxl.job.admin.address", "http://xxl.example.com:8080/xxl-job-admin");

        Assertions.assertThat(String.join("\n", report(true)))
                .contains("WARN xxl.* is still set in the Nacos config but XXL-Job has been removed")
                .contains("Delete the xxl section");

        properties.remove("xxl.job.admin.address");
        Assertions.assertThat(String.join("\n", report(true))).doesNotContain("xxl");
    }

    @Test
    @DisplayName("自检明确区分 ServiceNow 专用代理启用或关闭")
    void reportShowsServiceNowProxyRoute() {
        Assertions.assertThat(String.join("\n", report(true)))
                .contains("servicenow.proxy enabled=false").doesNotContain("proxy-host=");
        serviceNow.setProxyEnabled(true);
        serviceNow.setProxyHost("webproxy.hkex");
        serviceNow.setProxyPort(8080);
        Assertions.assertThat(String.join("\n", report(true)))
                .contains("servicenow.proxy enabled=true host=webproxy.hkex port=8080");
    }

    @Test
    @DisplayName("跳过证书校验时自检必须打出状态与告警")
    void reportAnnouncesDisabledTlsVerification() {
        Assertions.assertThat(String.join("\n", report(true)))
                .contains("servicenow.tls-verification=enabled")
                .doesNotContain("allow-insecure-tls=true");
        serviceNow.setAllowInsecureTls(true);
        Assertions.assertThat(String.join("\n", report(true)))
                .contains("servicenow.tls-verification=DISABLED (allow-insecure-tls=true)")
                .contains("WARN servicenow.allow-insecure-tls=true")
                .contains("can read the integration credentials and tokens");
    }

    @Test
    @DisplayName("任何情况下都不打印凭据值，只打印是否存在")
    void reportNeverPrintsCredentialValues() {
        serviceNow.setUsername("sn-user-should-not-appear");
        serviceNow.setPassword("sn-password-should-not-appear");
        douc.setCookie("cookie-should-not-appear");
        douc.setAppKey("app-key-should-not-appear");
        douc.setAppSecret("app-secret-should-not-appear");

        String report = String.join("\n", report(true));

        Assertions.assertThat(report)
                .doesNotContain("sn-user-should-not-appear")
                .doesNotContain("sn-password-should-not-appear")
                .doesNotContain("cookie-should-not-appear")
                .doesNotContain("app-key-should-not-appear")
                .doesNotContain("app-secret-should-not-appear");
        Assertions.assertThat(report)
                .contains("credentials servicenow-username=present servicenow-password=present"
                        + " douc-cookie=present douc-app-key=present douc-app-secret=present");
    }

    @Test
    @DisplayName("自检报的日志路径与 log4j2 实际取值一致：cwLogRootPath 优先，并提示不一致")
    void reportShowsOmpLogDirWhenStartScriptInjectsIt() {
        System.setProperty("cwLogRootPath", "/data/logs/hkexCmdbSync");
        System.setProperty("LOG_PATH", "./logs");

        Assertions.assertThat(String.join("\n", report(true)))
                .contains("log-file=/data/logs/hkexCmdbSync/hkex-cmdb-sync.log source=cwLogRootPath")
                .contains("WARN log-dir mismatch: Nacos logging.file.path=\"./logs\""
                        + " differs from cwLogRootPath=/data/logs/hkexCmdbSync")
                .contains("logs go to /data/logs/hkexCmdbSync (cwLogRootPath wins)");
    }

    @Test
    @DisplayName("只有 Nacos 的 logging.file.path 时，如实说明日志落在工作目录下")
    void reportFallsBackToBootLogPath() {
        System.setProperty("LOG_PATH", "./logs");

        Assertions.assertThat(String.join("\n", report(true)))
                .contains("log-file=./logs/hkex-cmdb-sync.log source=LOG_PATH(Nacos logging.file.path,"
                        + " relative to the working directory)")
                .doesNotContain("WARN log-dir mismatch");
    }

    @Test
    @DisplayName("两个来源都没有时回落到工作目录下的相对 logs")
    void reportFallsBackToRelativeLogs() {
        Assertions.assertThat(String.join("\n", report(true)))
                .contains("log-file=logs/hkex-cmdb-sync.log"
                        + " source=default(relative to the working directory)");
    }

    @Test
    @DisplayName("两个来源取值一致时不告警")
    void reportDoesNotWarnWhenLogDirsAgree() {
        System.setProperty("cwLogRootPath", "/data/logs/hkexCmdbSync");
        System.setProperty("LOG_PATH", "/data/logs/hkexCmdbSync");

        Assertions.assertThat(String.join("\n", report(true))).doesNotContain("WARN log-dir mismatch");
    }

    /**
     * 日志目录来自系统属性，测试之间必须清理，避免相互影响。
     */
    @AfterEach
    void clearLogDirProperties() {
        System.clearProperty("cwLogRootPath");
        System.clearProperty("LOG_PATH");
    }

    /**
     * 生成自检行。
     *
     * @param remoteConfig 是否把 Nacos 属性源放进环境，用于验证「配置从哪来」那一行
     * @return 自检行
     */
    private List<String> report(boolean remoteConfig) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        if (remoteConfig) {
            environment.getPropertySources().addFirst(new MapPropertySource(
                    CmdbRemoteConfigInitializer.PROPERTY_SOURCE_NAME, Collections.emptyMap()));
        }
        return new StartupStatusReporter(
                new StaticApplicationContext(), environment, serviceNow, douc).buildLines();
    }
}
