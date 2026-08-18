package com.lrj.recon.batch.web;

import com.lrj.recon.batch.service.ReconConsoleQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理台只读 API 的 H2/MockMvc 契约与跨表投影测试。 */
@SpringBootTest
@AutoConfigureMockMvc
class ReconConsoleControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static final String SCENARIO = "MARKETING_3WAY";
    private static final String PERIOD = "2026-08-18";
    private static final Instant BASE_TIME = Instant.parse("2026-08-18T10:00:00Z");

    @BeforeEach
    void reset() {
        for (String table : List.of(
                "alert_outbox", "discrepancy_action", "reversal_suggestion", "discrepancy_disposition",
                "discrepancy", "recon_report", "recon_report_partial", "recon_record_reject", "recon_record",
                "recon_run_seq", "recon_run")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void dashboardAggregatesHealthAndRecentRuns() throws Exception {
        seedRun("run-completed", 1, "COMPLETED", BASE_TIME);
        seedRun("run-failed", 2, "FAILED", BASE_TIME.plusSeconds(60));
        seedRun("run-imbalance", 3, "REPORT_IMBALANCE", BASE_TIME.plusSeconds(120));
        seedDiscrepancy("disc-open", "run-completed", "MISSING", "O".repeat(64), "ORDER-OPEN");
        seedDiscrepancy("disc-resolved", "run-completed", "AMOUNT_MISMATCH", "R".repeat(64), "ORDER-RESOLVED");
        seedDiscrepancy("disc-reopened", "run-completed", "EXTRA", "E".repeat(64), "ORDER-REOPENED");
        seedDiscrepancy("disc-stale", "run-completed", "TIMING", "S".repeat(64), "ORDER-STALE");
        seedDisposition("R".repeat(64), "RESOLVED", 0);
        seedDisposition("E".repeat(64), "REOPENED", 1);
        seedDisposition("S".repeat(64), "STALE", 1);
        seedReport("report-1", "run-completed", true);

        mvc.perform(get("/recon/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.totalRuns").value(3))
                .andExpect(jsonPath("$.metrics.completedRuns").value(1))
                .andExpect(jsonPath("$.metrics.failedRuns").value(1))
                .andExpect(jsonPath("$.metrics.imbalancedRuns").value(1))
                .andExpect(jsonPath("$.metrics.openDiscrepancies").value(2))
                .andExpect(jsonPath("$.metrics.resolvedDiscrepancies").value(1))
                .andExpect(jsonPath("$.discrepancyTypes.length()").value(4))
                .andExpect(jsonPath("$.recentRuns[0].runId").value("run-imbalance"))
                .andExpect(jsonPath("$.recentRuns[2].balanced").value(true));
    }

    @Test
    void runsSupportFiltersStablePaginationAndDetail() throws Exception {
        seedRun("run-1", 1, "COMPLETED", BASE_TIME);
        seedRun("run-2", 2, "FAILED", BASE_TIME.plusSeconds(60));
        seedDiscrepancy("disc-open", "run-1", "MISSING", "A".repeat(64), "ORDER-1");
        seedDiscrepancy("disc-reopened", "run-1", "EXTRA", "E".repeat(64), "ORDER-2");
        seedDiscrepancy("disc-stale", "run-1", "TIMING", "S".repeat(64), "ORDER-3");
        seedDisposition("E".repeat(64), "REOPENED", 1);
        seedDisposition("S".repeat(64), "STALE", 1);
        seedReport("report-1", "run-1", true);

        mvc.perform(get("/recon/runs").param("status", "completed").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].runId").value("run-1"))
                .andExpect(jsonPath("$.content[0].discrepancyCount").value(3))
                .andExpect(jsonPath("$.content[0].openDiscrepancyCount").value(2))
                .andExpect(jsonPath("$.content[0].balanced").value(true));

        mvc.perform(get("/recon/runs/run-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("FAILED"));
        mvc.perform(get("/recon/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[0].expectedTotalMinor").value("1000"));
        mvc.perform(get("/recon/runs/no-such"))
                .andExpect(status().isNotFound());
    }

    @Test
    void discrepanciesSupportFiltersAndReturnCompleteDetail() throws Exception {
        seedRun("run-1", 1, "COMPLETED", BASE_TIME);
        String fingerprint = "D".repeat(64);
        seedDiscrepancy("disc-detail", "run-1", "AMOUNT_MISMATCH", fingerprint, "ORDER-42");
        seedDiscrepancy("disc-other", "run-1", "MISSING", "M".repeat(64), "ORDER-OTHER");
        seedDisposition(fingerprint, "RESOLVED", 2);
        Timestamp now = Timestamp.from(BASE_TIME.plusSeconds(180));
        jdbc.update("""
                INSERT INTO discrepancy_action(id, fingerprint, action_type, idempotency_key, payload, operator, created_at)
                VALUES (?,?,?,?,?,?,?)
                """, "action-1", fingerprint, "MANUAL_RESOLVE", "manual:resolve:detail", "status=RESOLVED",
                "ops-a", now);
        jdbc.update("""
                INSERT INTO reversal_suggestion(id, fingerprint, run_id, group_key, suggested_amount_minor,
                    currency, status, idempotency_key, operator, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, "reversal-1", fingerprint, "run-1", "ORDER-42", 100L, "USD", "SUGGESTED",
                "reversal:detail", null, now);
        jdbc.update("""
                INSERT INTO alert_outbox(id, run_id, fingerprint, payload, status, attempt, idempotency_key, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, "alert-1", "run-1", fingerprint, "{}", "FAILED", 1, "alert:detail", now);

        mvc.perform(get("/recon/discrepancies")
                        .param("status", "resolved")
                        .param("type", "amount_mismatch")
                        .param("q", "order-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].discrepancyId").value("disc-detail"))
                .andExpect(jsonPath("$.content[0].dispositionStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.content[0].dispositionVersion").value(2));

        mvc.perform(get("/recon/discrepancies").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].discrepancyId").value("disc-other"));

        mvc.perform(get("/recon/discrepancies/disc-detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discrepancy.leftRawRef").value("left:ORDER-42"))
                .andExpect(jsonPath("$.actions[0].actionType").value("MANUAL_RESOLVE"))
                .andExpect(jsonPath("$.reversals[0].suggestedAmountMinor").value("100"))
                .andExpect(jsonPath("$.alerts[0].status").value("FAILED"))
                .andExpect(jsonPath("$.alerts[0].attempt").value(1));
    }

    @Test
    void invalidPaginationEnumsAndCurrencyReturn400() throws Exception {
        mvc.perform(get("/recon/runs").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/recon/runs").param("page", "1000001"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/recon/runs").param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/recon/runs").param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/recon/discrepancies").param("type", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/recon/discrepancies").param("currency", "US"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/recon/discrepancies/no-such"))
                .andExpect(status().isNotFound());

        assertThat(ReconConsoleQueryRepository.PageResult.of(List.of(), 0, 100, Long.MAX_VALUE).totalPages())
                .isEqualTo(Integer.MAX_VALUE);
    }

    private void seedRun(String runId, int sequence, String status, Instant createdAt) {
        Timestamp time = Timestamp.from(createdAt);
        jdbc.update("""
                INSERT INTO recon_run(run_id, scenario_code, accounting_period, sequence_no, cutoff_time,
                    match_window_from, match_window_to, bucket_count, status, revision, created_at, updated_at,
                    started_at, finished_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, runId, SCENARIO, PERIOD, sequence, time, time, time, 64, status, 1L, time, time, time,
                status.equals("COMPLETED") ? time : null);
    }

    private void seedDiscrepancy(String id, String runId, String type, String fingerprint, String groupKey) {
        Timestamp now = Timestamp.from(BASE_TIME.plusSeconds(150));
        jdbc.update("""
                INSERT INTO discrepancy(discrepancy_id, run_id, segment_id, type, fingerprint, group_key, match_key,
                    currency, expected_amount_minor, actual_amount_minor, delta_amount_minor, left_raw_ref,
                    right_raw_ref, machine_result, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)
                """, id, runId, "SEG1_MKT_ACCT", type, fingerprint, groupKey, "MATCH-" + groupKey, "USD",
                1000L, 900L, 100L, "left:" + groupKey, "right:" + groupKey, now, now);
    }

    private void seedDisposition(String fingerprint, String status, int version) {
        Timestamp now = Timestamp.from(BASE_TIME.plusSeconds(170));
        jdbc.update("""
                INSERT INTO discrepancy_disposition(id, fingerprint, scenario_code, accounting_period, segment_id,
                    status, operator, note, last_seen_run_id, version, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, "disp-" + fingerprint.substring(0, 4), fingerprint, SCENARIO, PERIOD, "SEG1_MKT_ACCT",
                status, "ops-a", "verified", "run-1", version, now, now);
    }

    private void seedReport(String id, String runId, boolean balanced) {
        Timestamp now = Timestamp.from(BASE_TIME.plusSeconds(200));
        jdbc.update("""
                INSERT INTO recon_report(report_id, run_id, segment_id, currency, expected_total_minor,
                    matched_amount_minor, amount_mismatch_minor, missing_minor, duplicate_minor, extra_minor,
                    timing_minor, status_mismatch_minor, currency_mismatch_minor, group_sum_mismatch_minor,
                    bridge_broken_minor, right_side_total_minor, left_residual_minor, right_residual_minor,
                    balanced, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, runId, "SEG1_MKT_ACCT", "USD", 1000L, 900L, 100L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 900L, 0L, 0L, balanced ? 1 : 0, now);
    }
}
