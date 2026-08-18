package com.lrj.recon.batch.job;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 重跑幂等 (设计验收 §12.5, ADR-7): 同 runId 重跑先 {@code cleanBounded} 清机器结果再重算, 结果一致;
 * 预置的 {@code discrepancy_disposition} / {@code reversal_suggestion} <b>幸存未被删</b>; 陈旧机器差异被清除。
 */
class ReconJobRerunIdempotencyTest extends AbstractReconJobIT {

    private static final String RUN = "run-rerun";
    private static final String FP_HUMAN = "H".repeat(64);
    private static final String FP_STALE = "S".repeat(64);

    @Test
    void rerunClearsMachineResultsRecomputesAndPreservesHumanArtifacts() throws Exception {
        // ---- 首跑 (attempt 1) ----
        marketing("m-clean", "I-CLEAN", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-clean", "I-CLEAN", "USD", 1000, "ISSUE", "PAID", BIZ);
        marketing("m-amt", "I-AMT", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-amt", "I-AMT", "USD", 900, "ISSUE", "PAID", BIZ);

        JobExecution first = launch(RUN, 1);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("recon_record", RUN)).isEqualTo(4L);   // 2 mkt + 2 acct
        assertThat(discrepancyTypes(RUN)).containsExactly("AMOUNT_MISMATCH");

        // ---- 预置人工痕迹 (永不被重跑删) + 陈旧机器差异 (应被 cleanBounded 清) ----
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO discrepancy_disposition(id, fingerprint, scenario_code, accounting_period, segment_id,
                    status, operator, version, created_at, updated_at)
                VALUES ('disp-keep', ?, ?, ?, 'SEG1_MKT_ACCT', 'RESOLVED', 'ops', 0, ?, ?)
                """, FP_HUMAN, SCENARIO, PERIOD, now, now);
        jdbc.update("""
                INSERT INTO reversal_suggestion(id, fingerprint, run_id, group_key, suggested_amount_minor,
                    currency, status, idempotency_key, created_at)
                VALUES ('rev-keep', ?, ?, 'I-AMT', 100, 'USD', 'SUGGESTED', 'idem-keep', ?)
                """, FP_HUMAN, RUN, now);
        jdbc.update("""
                INSERT INTO discrepancy(discrepancy_id, run_id, segment_id, type, fingerprint,
                    expected_amount_minor, actual_amount_minor, delta_amount_minor, machine_result, created_at, updated_at)
                VALUES ('stale-1', ?, 'SEG1_MKT_ACCT', 'MISSING', ?, 999, 0, 999, 1, ?, ?)
                """, RUN, FP_STALE, now, now);
        assertThat(count("discrepancy", RUN)).isEqualTo(2L); // AMOUNT_MISMATCH + 陈旧 stale

        // ---- 重跑 (attempt 2, 同 runId): cleanBounded 清 staging + 机器差异, 再重算 ----
        JobExecution second = launch(RUN, 2);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // staging 未翻倍 → 证明 cleanBounded 清了 staging (否则 record_id PK 冲突 batchInsert 会崩)
        assertThat(count("recon_record", RUN)).isEqualTo(4L);

        // 机器差异一致: 陈旧 MISSING 已清, 只剩重算的 AMOUNT_MISMATCH
        assertThat(discrepancyTypes(RUN)).containsExactly("AMOUNT_MISMATCH");
        assertThat(jdbc.queryForList(
                "SELECT discrepancy_id FROM discrepancy WHERE run_id=?", String.class, RUN))
                .doesNotContain("stale-1");

        // 人工痕迹幸存
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_disposition WHERE fingerprint=?", Long.class, FP_HUMAN))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reversal_suggestion WHERE idempotency_key='idem-keep'", Long.class))
                .isEqualTo(1L);

        // 报表一致且守恒闭合
        assertThat(count("recon_report", RUN)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT balanced FROM recon_report WHERE run_id=? AND currency='USD'", Integer.class, RUN))
                .isEqualTo(1);
    }
}
