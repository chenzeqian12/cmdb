package com.cloudwise.cmdb.config;

import com.cloudwise.cmdb.config.remote.CmdbRemoteConfigBinder;
import com.cloudwise.cmdb.config.remote.CmdbRemoteConfigInitializer;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动状态自检：每次启动（含重启）打一组**纯英文**、固定前缀的日志。
 *
 * <p>日志里把「这次跑的哪个包、配置从哪来、监听哪个端口、自动同步开没开、写不写库」一次说清，
 * 现场一条命令即可确认：</p>
 *
 * <pre>
 * grep CMDB-STARTUP-CHECK logs/hkex-cmdb-sync.log
 * </pre>
 *
 * <p>为什么要单独做这件事：配置全部来自 Nacos 并以最高优先级注入，出问题时
 * 「Nacos 没改对」「改了没重启」「重启了没生效」三种情况在日志上无法区分；而且交付文档里让现场
 * grep 的「远程配置加载成功」这行此前并不存在，照着文档查只会得到空结果。</p>
 *
 * <p>用英文是为了避开现场终端/编辑器的 GBK 乱码，也保证 grep 关键字全 ASCII。
 * 只打印开关、地址和「凭据是否已加载」，<b>不打印任何密码、Token、Cookie 或密文</b>。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Component
public class StartupStatusReporter {

    /** 统一前缀，现场用它一条 grep 拿到全部状态；启动失败时也用它打标记（见启动类）。 */
    public static final String TAG = "CMDB-STARTUP-CHECK";

    /** 必填项为空时的占位，便于和「配了个空字符串」区分。 */
    private static final String MISSING = "MISSING";

    /** 启动脚本注入的日志根目录（OMP 的 log_dir），log4j2.xml 里优先级最高。 */
    private static final String CW_LOG_ROOT_PROPERTY = "cwLogRootPath";

    /** Spring Boot 由配置项 logging.file.path 生成的系统属性，只在缺少上面那个时兜底。 */
    private static final String BOOT_LOG_PATH_PROPERTY = "LOG_PATH";

    /** 业务日志文件名，log4j2.xml 里写死的名字。 */
    private static final String LOG_FILE_NAME = "/hkex-cmdb-sync.log";

    private final ApplicationContext applicationContext;

    private final ConfigurableEnvironment environment;

    private final ServiceNowProperties serviceNow;

    private final DoucProperties douc;

    public StartupStatusReporter(
            ApplicationContext applicationContext,
            ConfigurableEnvironment environment,
            ServiceNowProperties serviceNow,
            DoucProperties douc) {
        this.applicationContext = applicationContext;
        this.environment = environment;
        this.serviceNow = serviceNow;
        this.douc = douc;
    }

    /**
     * 服务就绪后打印自检。排在最高优先级，先于 {@code GroupSyncTrigger} 的启动同步日志，
     * 保证现场看到的顺序是「先说状态，再说要干什么」。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void report() {
        for (String line : buildLines()) {
            log.info(line);
        }
    }

    /**
     * 生成全部自检行。抽成独立方法便于单元测试逐行断言，不必启动 Spring 上下文。
     *
     * @return 待输出的日志行，顺序即输出顺序
     */
    List<String> buildLines() {
        boolean dryRun = douc.isDryRun();
        boolean scheduledEnabled = booleanValue("hkex.sync.scheduled-enabled", true);
        boolean onStartup = booleanValue("hkex.sync.on-startup", true);
        long startupDelay = longValue("hkex.sync.startup-delay-seconds", 30);

        List<String> lines = new ArrayList<>();
        lines.add(TAG + " artifact=" + artifactName());
        lines.add(TAG + " profile=" + String.join(",", environment.getActiveProfiles())
                + " config-source=" + configSource()
                + " http-port=" + actualHttpPort());
        lines.add(TAG + " servicenow.base-url=" + orMissing(serviceNow.getBaseUrl()));
        lines.add(TAG + " douc.base-url=" + orMissing(douc.getBaseUrl())
                + " gateway-prefix=" + orMissing(douc.getGatewayPrefix())
                + " auth-mode=" + orMissing(douc.getAuthMode())
                + " sign-mode=" + orMissing(douc.getSignMode()));
        lines.add(TAG + " douc.scope account-id=" + orMissing(douc.getAccountId())
                + " top-account-id=" + orMissing(douc.getTopAccountId())
                + " user-id=" + orMissing(douc.getUserId())
                + " parent-group-id=\"" + orEmpty(douc.getParentGroupId()) + "\""
                + " page-size=" + douc.getUserPageSize());
        lines.add(TAG + " credentials servicenow-username=" + presence(serviceNow.getUsername())
                + " servicenow-password=" + presence(serviceNow.getPassword())
                + " douc-cookie=" + presence(douc.getCookie())
                + " douc-app-key=" + presence(douc.getAppKey())
                + " douc-app-secret=" + presence(douc.getAppSecret()));
        lines.add(TAG + " write-mode dry-run=" + dryRun
                + " delete-missing-groups=" + douc.isDeleteMissingGroups()
                + (dryRun ? " (plans only, DOUC is NOT modified)" : " (WILL MODIFY DOUC)"));
        lines.add(TAG + " auto-sync enabled=" + scheduledEnabled
                + " cron=\"" + textValue("hkex.sync.cron", "0 0 3 * * ?") + "\""
                + " on-startup=" + onStartup
                + " startup-delay-seconds=" + startupDelay
                + " member-sync=" + booleanValue("hkex.sync.member-sync", true));
        lines.add(TAG + " heartbeat enabled=" + booleanValue("hkex.heartbeat.enabled", true)
                + " file=" + textValue("hkex.heartbeat.file", MISSING)
                + " interval-seconds=" + longValue("hkex.heartbeat.interval-seconds", 30));
        lines.add(TAG + " log-file=" + effectiveLogDir() + LOG_FILE_NAME
                + " source=" + logDirSource());
        lines.addAll(notices(dryRun, scheduledEnabled, onStartup, startupDelay));
        lines.add(TAG + " verdict " + verdict(dryRun, scheduledEnabled, onStartup, startupDelay));
        return lines;
    }

    /**
     * 需要现场注意的组合。放在状态行之后、结论之前，现场只 grep 前缀就能一并看到。
     */
    private List<String> notices(
            boolean dryRun, boolean scheduledEnabled, boolean onStartup, long startupDelay) {
        List<String> notices = new ArrayList<>();
        if (!dryRun) {
            notices.add(TAG + " WARN dry-run=false: every sync WRITES to douc.base-url="
                    + orMissing(douc.getBaseUrl())
                    + "; make sure that address is the production DOUC");
        }
        if (!dryRun && scheduledEnabled && onStartup) {
            notices.add(TAG + " WARN dry-run=false with on-startup=true: a real write starts "
                    + startupDelay + "s after this line, and again on every restart");
        }
        if (!dryRun && scheduledEnabled) {
            notices.add(TAG + " WARN dry-run=false with scheduled-enabled=true:"
                    + " the daily cron job also writes to DOUC");
        }
        String leftoverXxl = leftoverXxlJobConfigNotice();
        if (leftoverXxl != null) {
            notices.add(leftoverXxl);
        }
        String logDirMismatch = logDirMismatchNotice();
        if (logDirMismatch != null) {
            notices.add(logDirMismatch);
        }
        if (scheduledEnabled && !onStartup) {
            notices.add(TAG + " NOTE on-startup=false: this restart does NOT run a sync;"
                    + " only the daily cron does");
        }
        if (!scheduledEnabled) {
            notices.add(TAG + " NOTE scheduled-enabled=false: no automatic sync at all;"
                    + " only manual HTTP triggers run");
        }
        return notices;
    }

    /**
     * 一句话结论：这次启动之后到底会不会自动同步、会不会写库。
     */
    private String verdict(boolean dryRun, boolean scheduledEnabled, boolean onStartup, long startupDelay) {
        String writeMode = dryRun
                ? "write-mode=DRY-RUN (DOUC is not modified)"
                : "write-mode=LIVE (DOUC IS modified)";
        if (!scheduledEnabled) {
            return "auto-sync=DISABLED (manual HTTP trigger only); " + writeMode;
        }
        String triggers = onStartup ? "daily-cron+on-startup" : "daily-cron";
        String nextRun = onStartup ? "; next-run=~" + startupDelay + "s after startup" : "";
        return "auto-sync=ENABLED triggers=" + triggers + nextRun + "; " + writeMode;
    }

    /**
     * 判断配置是否来自 Nacos，并带上 Group / Data ID —— 「改错 Data ID」是现场最常见的原因之一。
     */
    private String configSource() {
        boolean remote = environment.getPropertySources()
                .contains(CmdbRemoteConfigInitializer.PROPERTY_SOURCE_NAME);
        if (!remote) {
            return "local-only(NOT from Nacos)";
        }
        return "nacos group=" + textValue("cmdb.remote-config.group", MISSING)
                + " data-id=" + textValue("cmdb.remote-config.data-id", MISSING);
    }

    /**
     * 实际监听端口。优先取已经绑定的端口，而不是配置里的值 —— 两者不一致时以这里为准。
     */
    private int actualHttpPort() {
        if (applicationContext instanceof WebServerApplicationContext webContext) {
            WebServer webServer = webContext.getWebServer();
            if (webServer != null) {
                return webServer.getPort();
            }
        }
        Integer configured = environment.getProperty("server.port", Integer.class);
        return configured == null ? -1 : configured;
    }

    /**
     * 当前运行的制品文件名，形如 hkex-cmdb-sync-server-1.0.11-20261016100941-0abacc7d.jar。
     * 版本、构建时间、提交号都在文件名里，现场核对「跑的是哪个包」不用再翻目录。
     */
    private String artifactName() {
        CodeSource codeSource = getClass().getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return "unknown";
        }
        String path = codeSource.getLocation().getPath();
        int separator = path.lastIndexOf('/');
        String name = separator < 0 ? path : path.substring(separator + 1);
        // 类目录（IDE / 单元测试）下路径以 "/" 结尾，截出来是空串，这里明确写成 unknown。
        return StringUtils.hasText(name) ? name : "unknown";
    }

    /**
     * 实际生效的日志根目录。取值顺序与 log4j2.xml 的 LOG_HOME 严格一致，
     * 否则自检说的路径和日志真正落盘的位置会不一致 —— 那正是最误导人的情况。
     *
     * @return 生效的目录；两个属性都没有时返回相对路径 logs
     */
    private String effectiveLogDir() {
        String ompLogDir = System.getProperty(CW_LOG_ROOT_PROPERTY);
        if (StringUtils.hasText(ompLogDir)) {
            return ompLogDir;
        }
        String bootLogPath = System.getProperty(BOOT_LOG_PATH_PROPERTY);
        return StringUtils.hasText(bootLogPath) ? bootLogPath : "logs";
    }

    /**
     * 生效目录是从哪来的，便于一眼看出是 OMP 的 log_dir 还是 Nacos 配置。
     */
    private String logDirSource() {
        if (StringUtils.hasText(System.getProperty(CW_LOG_ROOT_PROPERTY))) {
            return "cwLogRootPath";
        }
        if (StringUtils.hasText(System.getProperty(BOOT_LOG_PATH_PROPERTY))) {
            return BOOT_LOG_PATH_PROPERTY + "(Nacos logging.file.path, relative to the working directory)";
        }
        return "default(relative to the working directory)";
    }

    /**
     * 日志目录的两个来源都被设置、取值却不一致时的提醒。
     * 现场真出现过：Nacos 配了 {@code logging.file.path: "./logs"}，日志全写进安装目录，
     * 而交付文档让人去 OMP 的 log_dir 里找，表现为「服务在跑但没有日志」。
     */
    private String logDirMismatchNotice() {
        String ompLogDir = System.getProperty(CW_LOG_ROOT_PROPERTY);
        String bootLogPath = System.getProperty(BOOT_LOG_PATH_PROPERTY);
        if (!StringUtils.hasText(ompLogDir) || !StringUtils.hasText(bootLogPath)
                || ompLogDir.equals(bootLogPath)) {
            return null;
        }
        return TAG + " WARN log-dir mismatch: Nacos logging.file.path=\"" + bootLogPath
                + "\" differs from cwLogRootPath=" + ompLogDir + "; logs go to " + effectiveLogDir()
                + " (cwLogRootPath wins). Set logging.file.path to the same absolute path,"
                + " or leave it blank.";
    }

    /**
     * Nacos 里还留着 xxl.* 配置时的提醒。
     *
     * <p>XXL-Job 已在 1.0.11.5 移除，改由进程内定时器（见 {@code GroupSyncTrigger}）负责每日同步。
     * 为了不让「Nacos 没删干净」直接把服务卡在启动阶段，配置校验对残留的 xxl 段是放行的、
     * 只是不生效；放行就必须说清楚，否则现场会以为调度中心还在起作用。</p>
     */
    private String leftoverXxlJobConfigNotice() {
        for (String key : CmdbRemoteConfigBinder.DEPRECATED_XXL_KEYS) {
            if (StringUtils.hasText(environment.getProperty(key))) {
                return TAG + " WARN xxl.* is still set in the Nacos config but XXL-Job has been removed;"
                        + " it is ignored. Delete the xxl section — the daily sync now runs on the"
                        + " in-process scheduler controlled by hkex.sync.cron";
            }
        }
        return null;
    }

    private boolean booleanValue(String key, boolean defaultValue) {
        return Boolean.TRUE.equals(environment.getProperty(key, Boolean.class, defaultValue));
    }

    private long longValue(String key, long defaultValue) {
        Long value = environment.getProperty(key, Long.class, defaultValue);
        return value == null ? defaultValue : value;
    }

    private String textValue(String key, String defaultValue) {
        String value = environment.getProperty(key);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String orMissing(String value) {
        return StringUtils.hasText(value) ? value : MISSING;
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private String presence(String value) {
        return StringUtils.hasText(value) ? "present" : "absent";
    }
}
