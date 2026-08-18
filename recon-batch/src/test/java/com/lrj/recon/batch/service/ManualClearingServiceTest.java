package com.lrj.recon.batch.service;

import com.lrj.recon.core.domain.model.ConflictException;
import com.lrj.recon.core.domain.model.DiscrepancyDisposition;
import com.lrj.recon.core.domain.model.DispositionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M5 人工核销服务 (设计 §4/§7/§11): 状态机流转 + version 乐观锁 + 409 冲突 + 审计 + 幂等。
 * 独立在线事务, 只写人工表 (不碰机器 discrepancy)。种子直接 seed recon_run + discrepancy (免跑 Job)。
 */
@SpringBootTest
class ManualClearingServiceTest {

    @Autowired ManualClearingService service;
    @Autowired JdbcTemplate jdbc;

    private static final String RUN = "run-mc";
    private static final String SCENARIO = "MARKETING_3WAY";
    private static final String PERIOD = "2026-08-17";
    private static final String SEG = "SEG1_MKT_ACCT";
    private static final String DID = "disc-mc-1";
    private static final String FP = "M".repeat(64);

    @BeforeEach
    void seed() {
        for (String t : java.util.List.of("discrepancy_action", "discrepancy_disposition", "discrepancy",
                "recon_run", "recon_run_seq")) {
            jdbc.update("DELETE FROM " + t);
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO recon_run(run_id, scenario_code, accounting_period, sequence_no, cutoff_time,
                    match_window_from, match_window_to, bucket_count, status, revision, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, RUN, SCENARIO, PERIOD, 1, now, now, now, 8, "COMPLETED", 3, now, now);
        jdbc.update("""
                INSERT INTO discrepancy(discrepancy_id, run_id, segment_id, type, fingerprint,
                    expected_amount_minor, actual_amount_minor, delta_amount_minor, machine_result, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,1,?,?)
                """, DID, RUN, SEG, "AMOUNT_MISMATCH", FP, 1000, 900, 100, now, now);
    }

    @Test
    void resolveCreatesDispositionWithAuditAtVersionZero() {
        DiscrepancyDisposition d = service.resolve(DID, "ops", "checked", null);

        assertThat(d.status()).isEqualTo(DispositionStatus.RESOLVED);
        assertThat(d.version()).isZero();
        assertThat(d.scenarioCode()).isEqualTo(SCENARIO);
        assertThat(d.accountingPeriod()).isEqualTo(PERIOD);
        assertThat(d.segmentId()).isEqualTo(SEG);
        assertThat(d.lastSeenRunId()).isEqualTo(RUN);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_action WHERE fingerprint=? AND action_type='MANUAL_RESOLVE'",
                Long.class, FP)).isEqualTo(1L);
    }

    @Test
    void resolveThenCloseAdvancesVersionAndStatus() {
        service.resolve(DID, "ops", null, null);
        DiscrepancyDisposition closed = service.close(DID, "ops2", "settled", 0);

        assertThat(closed.status()).isEqualTo(DispositionStatus.CLOSED);
        assertThat(closed.version()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_action WHERE fingerprint=? AND action_type='MANUAL_CLOSE'",
                Long.class, FP)).isEqualTo(1L);
    }

    @Test
    void staleExpectedVersionRaises409Conflict() {
        service.resolve(DID, "ops", null, null);           // v0
        service.close(DID, "ops", null, 0);                // v0 -> v1
        // 再用陈旧 expectedVersion=0 → 与当前 v1 冲突 → ConflictException (REST 409)
        assertThatThrownBy(() -> service.close(DID, "ops", null, 0))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void repeatedResolveIsIdempotentNoExtraVersionOrAudit() {
        service.resolve(DID, "ops", null, null);
        DiscrepancyDisposition again = service.resolve(DID, "ops", null, null);

        assertThat(again.status()).isEqualTo(DispositionStatus.RESOLVED);
        assertThat(again.version()).isZero();              // 幂等短路, 不 bump version
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_action WHERE fingerprint=?", Long.class, FP)).isEqualTo(1L);
    }

    @Test
    void illegalTransitionThrowsIllegalState() {
        service.resolve(DID, "ops", null, null);           // OPEN -> RESOLVED (v0)
        service.close(DID, "ops", null, 0);                // RESOLVED -> CLOSED (v1)
        // CLOSED 只能 REOPEN; RESOLVE 非法 (version 校验先过, 状态机拒绝)
        assertThatThrownBy(() -> service.resolve(DID, "ops", null, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownDiscrepancyIsNotFound() {
        assertThatThrownBy(() -> service.resolve("no-such-id", "ops", null, null))
                .isInstanceOf(NotFoundException.class);
    }
}
