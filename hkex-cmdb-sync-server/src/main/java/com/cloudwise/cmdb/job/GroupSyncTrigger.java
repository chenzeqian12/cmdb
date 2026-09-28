package com.cloudwise.cmdb.job;

import com.cloudwise.cmdb.pojo.dto.GroupSyncResult;
import com.cloudwise.cmdb.service.GroupSyncService;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 用户组同步的**自动触发点**（进程内，不依赖 XXL-Job）。两个触发时机：
 *
 * <ol>
 *   <li><b>服务每次启动后</b>跑一次 —— 由 {@code hkex.sync.on-startup} 控制；</li>
 *   <li><b>每日定时</b>跑一次 —— 由 {@code hkex.sync.cron} 控制，默认每天凌晨 3 点。</li>
 * </ol>
 *
 * <p>与 {@link GroupSyncJob} 的区别：那个走 XXL-Job，cron 在管理后台配；这个的触发条件
 * <b>就写在 conf/application.yml 里</b>，现场不接调度中心也能自己跑。</p>
 *
 * <pre>
 * hkex:
 *   sync:
 *     scheduled-enabled: true        # 总开关；false = 下面两个自动触发都不生效
 *     on-startup: true               # 服务每次启动后跑一次
 *     startup-delay-seconds: 30      # 启动后等几秒再跑
 *     cron: "0 0 3 * * ?"            # 每日定时
 *     member-sync: true              # 是否同时按组配人（需求2）
 * </pre>
 *
 * <p>写不写数据由 {@code douc.dry-run} 决定：true 时只出计划不落库。</p>
 *
 * @author hkex-cmdb-sync
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "hkex.sync",
        name = "scheduled-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class GroupSyncTrigger {

    /** 统一日志关键字，便于在日志平台按项目检索。 */
    private static final String TAG = "【HKEX-CMDB同步】";

    private final GroupSyncService groupSyncService;

    private final TaskScheduler taskScheduler;

    private final boolean memberSync;

    private final boolean onStartup;

    private final long startupDelaySeconds;

    public GroupSyncTrigger(
            GroupSyncService groupSyncService,
            TaskScheduler taskScheduler,
            @Value("${hkex.sync.member-sync:true}") boolean memberSync,
            @Value("${hkex.sync.on-startup:true}") boolean onStartup,
            @Value("${hkex.sync.startup-delay-seconds:30}") long startupDelaySeconds) {
        this.groupSyncService = groupSyncService;
        this.taskScheduler = taskScheduler;
        this.memberSync = memberSync;
        this.onStartup = onStartup;
        this.startupDelaySeconds = startupDelaySeconds;
    }

    /**
     * 服务启动完成后触发一次同步。
     *
     * <p><b>刻意延迟执行</b>，不是立刻跑，原因有二：</p>
     * <ol>
     *   <li>刚起来的这几秒 Web 端口、连接池都还没热，立刻发 2 万条记录的全量请求容易失败；</li>
     *   <li>万一服务起来就崩、被平台反复重启，延迟能天然挡掉大部分"崩溃循环 → 同步风暴"。</li>
     * </ol>
     *
     * <p>用 {@link TaskScheduler} 而不是 {@code @Scheduled(initialDelay=...)}：后者要配一个
     * 永远不触发的 {@code fixedDelay} 才能当一次性任务用，很别扭。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (!onStartup) {
            log.info("{}服务已就绪；启动同步已关闭（hkex.sync.on-startup=false）", TAG);
            return;
        }
        log.info("{}服务已就绪，{} 秒后执行一次启动同步。"
                        + "不想每次重启都同步就设 hkex.sync.on-startup=false",
                TAG, startupDelaySeconds);
        taskScheduler.schedule(this::runOnce, Instant.now().plusSeconds(startupDelaySeconds));
    }

    /**
     * 按 {@code hkex.sync.cron} 定频执行一次全量同步，默认每天凌晨 3 点。
     */
    @Scheduled(cron = "${hkex.sync.cron:0 0 3 * * ?}")
    public void syncByCron() {
        runOnce();
    }

    /**
     * 真正干活的方法。启动触发和定时触发共用，保证两条路径的日志、异常处理完全一致。
     *
     * <p>这里刻意不让异常往外抛：定时任务抛异常只会留下一行堆栈、下一轮照跑；
     * 捕获后打一条带 TAG 的 ERROR，便于日志平台按关键字告警。</p>
     */
    private void runOnce() {
        long startedAt = System.currentTimeMillis();
        log.info("{}自动同步开始（memberSync={}）", TAG, memberSync);
        try {
            GroupSyncResult result = groupSyncService.sync(null, memberSync, true);
            log.info("{}自动同步结束，{}，耗时 {} ms",
                    TAG, summarize(result), System.currentTimeMillis() - startedAt);
            if (!result.getFailedGroups().isEmpty()) {
                log.error("{}自动同步存在失败的用户组 {}，请检查 douc 接口与网络",
                        TAG, result.getFailedGroups());
            }
        } catch (Exception e) {
            log.error("{}自动同步失败。DOUC 或 ServiceNow 可能不可达，本次未完成，下次触发时重试。", TAG, e);
        }
    }

    /** 一行汇总，格式与手工触发时的日志保持一致，便于同一套检索规则覆盖所有情况。 */
    private String summarize(GroupSyncResult result) {
        return String.format(
                "{源记录=%d, 脏记录=%d, 源组=%d, 新建组=%d, 已存在组=%d, 失败组=%d, "
                        + "匹配用户=%d, 未匹配=%d, dryRun=%s}",
                result.getSourceRecordCount(), result.getInvalidRecordCount(),
                result.getSourceGroupCount(), result.getCreatedGroups().size(),
                result.getExistingGroups().size(), result.getFailedGroups().size(),
                result.getMatchedUserCount(), result.getUnmatchedUserCount(),
                result.isDryRun());
    }
}
