package com.cloudwise.cmdb.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动自检：必需配置缺失时立即失败，不把问题拖到第一次调用接口。
 *
 * <p>凭据外置到 {@code conf/application-{profile}.properties} 之后，jar 本身是「配置无关」的，
 * 漏带配置文件时程序仍能启动，但要等到调用 ServiceNow 或 DOUC 才报 401 / 认证失败，
 * 排查成本高。这里在启动阶段把必填项一次列全，直接中断启动。</p>
 *
 * <p>校验范围按 {@code douc.auth-mode} 分支：只有当前模式真正会用到的凭据才算必填。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigValidator {

    /** 统一日志关键字，便于在日志平台按项目检索。 */
    private static final String TAG = "【HKEX-CMDB同步】";

    private static final String AUTH_MODE_DIRECT = "direct";

    private static final String AUTH_MODE_APIM = "apim";

    private static final String AUTH_MODE_COOKIE = "cookie";

    private final ServiceNowProperties serviceNow;

    private final DoucProperties douc;

    /**
     * 启动自检入口。缺配置时抛异常中断启动。
     */
    @PostConstruct
    public void validate() {
        List<String> missing = new ArrayList<>();

        require(missing, "servicenow.base-url", serviceNow.getBaseUrl());
        require(missing, "servicenow.client-id", serviceNow.getClientId());
        require(missing, "servicenow.client-secret", serviceNow.getClientSecret());
        require(missing, "servicenow.username", serviceNow.getUsername());
        require(missing, "servicenow.password", serviceNow.getPassword());

        require(missing, "douc.base-url", douc.getBaseUrl());
        require(missing, "douc.account-id", douc.getAccountId());
        require(missing, "douc.user-id", douc.getUserId());

        String authMode = douc.getAuthMode();
        if (AUTH_MODE_COOKIE.equals(authMode)) {
            require(missing, "douc.cookie", douc.getCookie());
        } else if (AUTH_MODE_APIM.equals(authMode)) {
            require(missing, "douc.app-key", douc.getAppKey());
            require(missing, "douc.app-secret", douc.getAppSecret());
        } else if (!AUTH_MODE_DIRECT.equals(authMode)) {
            missing.add("douc.auth-mode（当前值 " + authMode + "，只能是 direct / apim / cookie）");
        }

        if (!missing.isEmpty()) {
            log.error("{}启动自检失败，缺少 {} 项必需配置：{}。"
                            + "请检查服务目录下的 conf/application-<profile>.properties"
                            + "（development 用 application-dev.properties，生产用 application-prod.properties，"
                            + "profile 由启动脚本的 SPRING_PROFILE 决定；"
                            + "文件不存在时可从 conf/ 下的 .example 复制后填写）",
                    TAG, missing.size(), missing);
            throw new IllegalStateException("缺少必需配置：" + missing);
        }

        // userId 单独打出来：它决定 /user/list 的可见范围，填错不报错只会让匹配率归零，
        // 出问题时第一个要核对的就是这个值。
        log.info("{}启动自检通过。DOUC={}{}，认证方式={}，accountId={}，userId={}，dryRun={}，"
                        + "ServiceNow={}",
                TAG,
                douc.getBaseUrl(),
                douc.getGatewayPrefix(),
                authMode,
                douc.getAccountId(),
                douc.getUserId(),
                douc.isDryRun(),
                serviceNow.getBaseUrl());
        if (douc.isDryRun()) {
            log.info("{}当前为试运行模式，只计算差异不写入 DOUC。确认计划无误后再设 douc.dry-run=false", TAG);
        }
    }

    private void require(List<String> missing, String key, String value) {
        if (!StringUtils.hasText(value)) {
            missing.add(key);
        }
    }
}
