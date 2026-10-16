package com.cloudwise.cmdb;

import com.cloudwise.cmdb.config.StartupStatusReporter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HKEX CMDB 同步服务启动类。
 *
 * <p>负责把 ServiceNow CMDB 的配置项与用户组数据同步到 Cloudwise DOUC / EMP。</p>
 *
 * <p>{@code @EnableScheduling} 是给 OmniAgent 插件心跳用的（见 {@link com.cloudwise.cmdb.config.PluginHeartbeatWriter}），
 * 底座按版本根目录下的 heartbeat 文件判断插件健康状态。</p>
 *
 * @author hkex-cmdb-sync
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CmdbSyncApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(CmdbSyncApplication.class, args);
        } catch (RuntimeException | Error failure) {
            /*
             * 配置在日志系统初始化之前加载：读取或校验 Nacos 配置失败时 log4j2 还没起来，
             * 异常只会走 stderr（启动脚本重定向到 start.log），业务日志文件里一行都不会有。
             * 这里补一条英文标记，保证「重启了但没日志」这种情况在 start.log 里也能 grep 到。
             */
            System.err.println(StartupStatusReporter.TAG + " FAILED: startup aborted, no status report was"
                    + " written. Check the stack trace above, the Nacos group/data-id, the JSON schema and"
                    + " the two servicenow *-encrypted fields.");
            throw failure;
        }
    }
}
