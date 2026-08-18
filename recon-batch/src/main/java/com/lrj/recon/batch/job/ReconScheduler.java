package com.lrj.recon.batch.job;

import com.lrj.recon.batch.alert.AlertRelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 定时调度 (设计 §7/§11 M5 / ADR-12): 两类定时任务, 均<b>默认关</b> ({@code recon.scheduler.enabled=false},
 * 测试友好 —— 不加载即无任何后台触发), 生产按需开启并配置 cron/间隔。
 *
 * <ol>
 *   <li><b>告警中继补投</b> ({@code @Scheduled(fixedDelay)}): 周期性 {@link AlertRelayService#relayOnce} 补投
 *       outbox 中 FAILED/PENDING 告警 (与 alertRelayStep 同一逻辑, 兜底批后新失败);</li>
 *   <li><b>定时发起 Run</b> ({@code @Scheduled(cron)}, 默认 cron={@code "-"} 即禁用): 到点经 {@link ReconLaunchService}
 *       (与 REST <b>同一序号分配路径</b>, 无竞态) 为配置场景 + 当日账期发起对账。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "recon.scheduler.enabled", havingValue = "true")
public class ReconScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconScheduler.class);

    private final AlertRelayService alertRelayService;
    private final ReconLaunchService launchService;
    private final String scenarioCode;

    public ReconScheduler(AlertRelayService alertRelayService,
                          ReconLaunchService launchService,
                          @Value("${recon.scheduler.scenario-code:MARKETING_3WAY}") String scenarioCode) {
        this.alertRelayService = alertRelayService;
        this.launchService = launchService;
        this.scenarioCode = scenarioCode;
    }

    /** 告警中继补投: 默认每 60s 一轮 (可配)。至少一次投递 + 幂等键去重。 */
    @Scheduled(fixedDelayString = "${recon.scheduler.alert-relay-delay-ms:60000}",
            initialDelayString = "${recon.scheduler.alert-relay-initial-ms:60000}")
    public void relayAlerts() {
        int sent = alertRelayService.relayOnce();
        if (sent > 0) {
            log.info("[scheduler] alert relay sent {} entries", sent);
        }
    }

    /** 定时发起对账 (默认 cron="-" 即禁用; 配 {@code recon.scheduler.launch-cron} 开启)。 */
    @Scheduled(cron = "${recon.scheduler.launch-cron:-}", zone = "UTC")
    public void launchDaily() {
        String period = LocalDate.now(ZoneOffset.UTC).toString();
        ReconLaunchService.LaunchResult result = launchService.launch(new ReconLaunchService.LaunchCommand(
                scenarioCode, period, null, null, null, null, null));
        log.info("[scheduler] launched run {} (seq {}) status={}",
                result.runId(), result.sequenceNo(), result.status());
    }
}
