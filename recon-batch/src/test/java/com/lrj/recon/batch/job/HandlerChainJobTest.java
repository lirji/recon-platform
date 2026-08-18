package com.lrj.recon.batch.job;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 处理链接入 matchEvaluate (设计 §4/§6/§7 / ADR-10) 端到端验证:
 * <ul>
 *   <li>差异产生后, TRANSACTIONAL handler 在 chunk 事务内执行: LedgerHandler 落 {@code discrepancy_action(LEDGER)},
 *       ReversalSuggestionHandler 对金额型差异 {@code insertIfAbsent} 生成 {@code reversal_suggestion}(SUGGESTED, 无资金动作);</li>
 *   <li>EXTERNAL handler (AlertHandler) <b>只写 alert_outbox</b> (PENDING), 批后 alertRelayStep 中继投递 → SENT
 *       (证明外部告警不在 chunk 事务内直发, 走 outbox);</li>
 *   <li>幂等: 同 runId 重跑, 冲正建议 / 告警 / 审计各仍只 1 条 (chunk 重试 / 重跑不重复生成)。</li>
 * </ul>
 */
class HandlerChainJobTest extends AbstractReconJobIT {

    private static final String RUN = "run-handler-chain";

    @Test
    void handlersFireInChunkTxAndAlertRelayedAfterCommitIdempotently() throws Exception {
        // AMOUNT_MISMATCH: 营销 1000 vs 账务 900 (delta=100) + 一条干净匹配
        marketing("m-clean", "I-CLEAN", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-clean", "I-CLEAN", "USD", 1000, "ISSUE", "PAID", BIZ);
        marketing("m-amt", "I-AMT", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-amt", "I-AMT", "USD", 900, "ISSUE", "PAID", BIZ);

        JobExecution first = launch(RUN, 1);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        String fp = jdbc.queryForObject(
                "SELECT fingerprint FROM discrepancy WHERE run_id=? AND type='AMOUNT_MISMATCH'", String.class, RUN);

        // 冲正建议: 只对金额型差异生成, SUGGESTED, delta=100, 无资金动作, 幂等键 = reversal-suggestion:fp
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reversal_suggestion WHERE fingerprint=?", Long.class, fp)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT suggested_amount_minor FROM reversal_suggestion WHERE fingerprint=?", Long.class, fp))
                .isEqualTo(100L);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reversal_suggestion WHERE fingerprint=?", String.class, fp)).isEqualTo("SUGGESTED");
        assertThat(jdbc.queryForObject(
                "SELECT idempotency_key FROM reversal_suggestion WHERE fingerprint=?", String.class, fp))
                .isEqualTo("reversal-suggestion:" + fp);

        // 台账审计: LEDGER + REVERSAL_SUGGESTION 两类 (同 fingerprint)
        assertThat(jdbc.queryForList(
                "SELECT action_type FROM discrepancy_action WHERE fingerprint=? ORDER BY action_type",
                String.class, fp)).containsExactly("LEDGER", "REVERSAL_SUGGESTION");

        // 告警: 只写 outbox, 批后 alertRelayStep 投递 → SENT (LoggingAlertDispatcher 默认成功)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_outbox WHERE fingerprint=?", Long.class, fp)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM alert_outbox WHERE fingerprint=?", String.class, fp)).isEqualTo("SENT");

        // ---- 重跑幂等: 各侧仍只 1 条 (handler 幂等键 = handlerId+fingerprint, 跨重跑稳定) ----
        JobExecution second = launch(RUN, 2);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reversal_suggestion WHERE fingerprint=?", Long.class, fp)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_outbox WHERE fingerprint=?", Long.class, fp)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_action WHERE fingerprint=?", Long.class, fp)).isEqualTo(2L);
    }

    @Test
    void cleanRunProducesNoHandlerSideEffects() throws Exception {
        marketing("m-1", "I-1", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-1", "I-1", "USD", 1000, "ISSUE", "PAID", BIZ);

        JobExecution exec = launch("run-clean-handlers", 1);
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(count("discrepancy", "run-clean-handlers")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reversal_suggestion", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_outbox", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM discrepancy_action", Long.class)).isZero();
    }
}
