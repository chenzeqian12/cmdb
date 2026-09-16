package com.cloudwise.cmdb.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * XXL-Job 执行器配置，与 docc 的 {@code XxlJobConfig} 保持同一套约定。
 *
 * <p>本类只负责把执行器注册到调度中心；<b>任务的 cron 不在代码里配</b>，
 * 而是在 XXL-Job 管理后台新建任务时填写，见 {@link com.cloudwise.cmdb.job.GroupSyncJob}。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Getter
@Setter
@Configuration
public class XxlJobConfig {

    /** 调度中心地址，多个用逗号分隔。 */
    @Value("${xxl.job.admin.address:}")
    private String adminAddresses;

    /** 调度中心通信 token，需与管理端一致。 */
    @Value("${xxl.job.accessToken:}")
    private String accessToken;

    /** 执行器名，管理后台按它分组，需与新建任务时选的执行器一致。 */
    @Value("${xxl.job.executor.app.name:hkex-cmdb-sync}")
    private String appName;

    /** 执行器监听端口。 */
    @Value("${xxl.job.executor.port:18599}")
    private int port;

    /** 任务日志落盘路径。 */
    @Value("${xxl.job.executor.log.path:./logs/jobhandler}")
    private String logPath;

    /** 任务日志保留天数。 */
    @Value("${xxl.job.executor.log.retention.days:7}")
    private int logRetentionDays;

    /** 手工指定执行器注册 IP，多网卡时用得上；留空则自动探测。 */
    @Value("${xxl.job.executor.ip:}")
    private String ip;

    /**
     * 注册 XXL-Job 执行器。
     *
     * <p>地址为空时仍会注册 Bean，但执行器连不上调度中心——启动时打日志说明，
     * 避免"任务没跑"这种问题排查半天。</p>
     *
     * @return 执行器
     */
    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        if (!StringUtils.hasText(adminAddresses)) {
            log.warn("未配置 xxl.job.admin.address，XXL-Job 执行器不会连上调度中心，定时任务不会触发");
        } else {
            log.info("XXL-Job 执行器初始化，appName={}，admin={}，port={}", appName, adminAddresses, port);
        }

        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appName);
        executor.setAccessToken(accessToken);
        executor.setLogPath(logPath);
        executor.setPort(port);
        executor.setLogRetentionDays(logRetentionDays);
        // 与 docc 一致：任务执行超过该秒数按超时处理
        executor.setTimeout(10);
        if (StringUtils.hasText(ip)) {
            executor.setIp(ip);
        }
        return executor;
    }
}
