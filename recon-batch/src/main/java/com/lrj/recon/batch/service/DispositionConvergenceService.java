package com.lrj.recon.batch.service;

import com.lrj.recon.core.application.port.out.DiscrepancyActionRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyDispositionRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyRepository;
import com.lrj.recon.core.application.port.out.ReconRunRepository;
import com.lrj.recon.core.domain.model.DiscrepancyAction;
import com.lrj.recon.core.domain.model.DiscrepancyActionType;
import com.lrj.recon.core.domain.model.DiscrepancyDisposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 重跑收敛服务 (设计 §11 M5 / §14 A1): 一次 Run 重算出机器差异后, 把<b>历史人工处置</b>与<b>本次机器差异集</b>
 * 按 fingerprint 对齐, 落三条 A1 决议 (绝不删 disposition/reversal, 结构性保证):
 *
 * <ul>
 *   <li><b>A1①</b> 人工已处置 (如 RESOLVED) 的差异重算后<b>仍出现</b> (fingerprint 在本次差异集) →
 *       {@code relink}: 刷新 last_seen_run_id、<b>保持原状态不重开</b>;</li>
 *   <li><b>A1②</b> 处置过但重算后<b>消失</b> (fingerprint 不在本次差异集) → {@code markStale} 自动关闭 + 审计;</li>
 *   <li><b>A1③</b> 差异 type 变更 (MISSING→AMOUNT_MISMATCH) 致 fingerprint 变: 旧 fingerprint 悬空 →
 *       同 A1② 标 STALE; 新 fingerprint 无处置 → 天然 OPEN (无需动作)。</li>
 * </ul>
 *
 * <p><b>作用域</b>: 按 {@code (scenarioCode, accountingPeriod)} 取历史存活处置, 逐条走
 * {@code uk_disc(run_id,fingerprint)} 判断本 Run 是否仍存在，避免全量加载机器差异。MVP 假设一个
 * (scenario, period) 由同一场景/Job 完整重算 (A5 日账期), 故本 Run 差异集即该 (scenario,period) 的最新机器视图;
 * 不在集内的历史处置即"差异已消失/漂移"。收敛更新带 expected version，遇并发人工操作会跳过而不覆盖人工结果。
 */
@Service
public class DispositionConvergenceService {

    private static final Logger log = LoggerFactory.getLogger(DispositionConvergenceService.class);

    private final DiscrepancyRepository discrepancies;
    private final DiscrepancyDispositionRepository dispositions;
    private final DiscrepancyActionRepository actions;
    private final ReconRunRepository runs;

    public DispositionConvergenceService(DiscrepancyRepository discrepancies,
                                         DiscrepancyDispositionRepository dispositions,
                                         DiscrepancyActionRepository actions,
                                         ReconRunRepository runs) {
        this.discrepancies = discrepancies;
        this.dispositions = dispositions;
        this.actions = actions;
        this.runs = runs;
    }

    /** 对某 Run 重算结果做 A1 收敛。首跑 (无历史处置) 为廉价 no-op。 */
    @Transactional
    public void converge(String runId, String scenarioCode, String accountingPeriod) {
        // 同账期多个 Run 的收敛先在既有 run 行上串行化，再判最新序号。若新 Run 已进入执行，旧 Run 即使先完成
        // 也必须跳过，防止把新视图仍存在的处置误标 STALE；锁还保证两个完成态 Run 不会交叉覆盖 last_seen_run_id。
        runs.lockScenarioPeriod(scenarioCode, accountingPeriod);
        if (!runs.isLatestRun(runId, scenarioCode, accountingPeriod)) {
            log.info("[rerun-converge] skip non-latest run={} scenario={} period={}",
                    runId, scenarioCode, accountingPeriod);
            return;
        }
        List<DiscrepancyDisposition> live = dispositions.findLiveByScenarioPeriod(scenarioCode, accountingPeriod);
        if (live.isEmpty()) {
            return;
        }
        int relinked = 0;
        int staled = 0;
        int concurrentSkips = 0;
        for (DiscrepancyDisposition disp : live) {
            // 逐条走 uk_disc(run_id,fingerprint) exists，避免把本 Run 全部机器差异加载进内存。
            if (discrepancies.existsByRunAndFingerprint(runId, disp.fingerprint())) {
                if (dispositions.relink(disp.fingerprint(), runId, disp.version())) {
                    relinked++;
                } else {
                    concurrentSkips++; // 人工/另一收敛事务已推进 version，保护其结果，留待下一轮收敛。
                }
            } else {
                if (dispositions.markStale(disp.fingerprint(), runId, disp.version())) {
                    auditStale(disp, runId);
                    staled++;
                } else {
                    concurrentSkips++;
                }
            }
        }
        if (relinked + staled + concurrentSkips > 0) {
            log.info("[rerun-converge] run={} scenario={} period={} relinked={} staled={} concurrentSkips={}",
                    runId, scenarioCode, accountingPeriod, relinked, staled, concurrentSkips);
        }
    }

    private void auditStale(DiscrepancyDisposition disp, String runId) {
        // 幂等键嵌 version (每次 STALE 流转版本必递增, 每个 STALE 事件唯一), 不嵌变长 runId ——
        // 避免键长超 discrepancy_action.idempotency_key VARCHAR(128) 致 INSERT 回滚整个 converge; runId 仍留 payload。
        String idem = "stale:" + disp.fingerprint() + ":" + disp.version();
        actions.insertIfAbsent(DiscrepancyAction.builder()
                .id(UUID.randomUUID().toString())
                .fingerprint(disp.fingerprint())
                .actionType(DiscrepancyActionType.STALE_CLOSE)
                .idempotencyKey(idem)
                .payload("priorStatus=" + disp.status() + ",runId=" + runId)
                .operator("system")
                .createdAt(Instant.now())
                .build());
    }
}
