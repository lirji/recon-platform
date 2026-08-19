package com.lrj.recon.batch.web;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B1 · 三方合并 roll-up 读接口 {@code GET /recon/runs/{id}/three-way}。验证由两段报表派生的合成口径:
 * 两段皆 balanced → threeWayConsistent/threeWayBalanced=true;任一段不平或缺段 → false;桥断额两段相加。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ThreeWayRollupTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static final String SCENARIO = "MARKETING_3WAY";
    private static final String PERIOD = "2026-08-18";
    private static final Instant T = Instant.parse("2026-08-18T10:00:00Z");

    @BeforeEach
    void reset() {
        for (String table : List.of("recon_report", "recon_run_seq", "recon_run")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void bothSegmentsBalancedYieldsThreeWayBalanced() throws Exception {
        seedRun("run-ok", "COMPLETED");
        seedReport("rep-1", "run-ok", "SEG1_MKT_ACCT", "USD", true, 0L);
        seedReport("rep-2", "run-ok", "SEG2_ACCT_CHANNEL", "USD", true, 0L);

        mvc.perform(get("/recon/runs/run-ok/three-way"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-ok"))
                .andExpect(jsonPath("$.threeWayBalanced").value(true))
                .andExpect(jsonPath("$.currencies[0].currency").value("USD"))
                .andExpect(jsonPath("$.currencies[0].threeWayConsistent").value(true))
                .andExpect(jsonPath("$.currencies[0].bridgeBrokenMinor").value("0"))
                .andExpect(jsonPath("$.currencies[0].seg1.segmentId").value("SEG1_MKT_ACCT"))
                .andExpect(jsonPath("$.currencies[0].seg2.segmentId").value("SEG2_ACCT_CHANNEL"));
    }

    @Test
    void unbalancedSegmentWithBridgeBreakIsInconsistentAndSumsBridge() throws Exception {
        seedRun("run-broken", "COMPLETED");
        seedReport("rep-3", "run-broken", "SEG1_MKT_ACCT", "USD", true, 200L);
        seedReport("rep-4", "run-broken", "SEG2_ACCT_CHANNEL", "USD", false, 500L);

        mvc.perform(get("/recon/runs/run-broken/three-way"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threeWayBalanced").value(false))
                .andExpect(jsonPath("$.currencies[0].threeWayConsistent").value(false))
                .andExpect(jsonPath("$.currencies[0].bridgeBrokenMinor").value("700"));
    }

    @Test
    void missingSecondSegmentIsInconsistent() throws Exception {
        seedRun("run-partial", "COMPLETED");
        seedReport("rep-5", "run-partial", "SEG1_MKT_ACCT", "USD", true, 0L);

        mvc.perform(get("/recon/runs/run-partial/three-way"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threeWayBalanced").value(false))
                .andExpect(jsonPath("$.currencies[0].threeWayConsistent").value(false))
                .andExpect(jsonPath("$.currencies[0].seg2").doesNotExist());
    }

    private void seedRun(String runId, String status) {
        Timestamp time = Timestamp.from(T);
        jdbc.update("""
                INSERT INTO recon_run(run_id, scenario_code, accounting_period, sequence_no, cutoff_time,
                    match_window_from, match_window_to, bucket_count, status, revision, created_at, updated_at,
                    started_at, finished_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, runId, SCENARIO, PERIOD, 1, time, time, time, 64, status, 1L, time, time, time, time);
    }

    private void seedReport(String id, String runId, String segment, String currency, boolean balanced, long bridge) {
        Timestamp now = Timestamp.from(T.plusSeconds(200));
        jdbc.update("""
                INSERT INTO recon_report(report_id, run_id, segment_id, currency, expected_total_minor,
                    matched_amount_minor, amount_mismatch_minor, missing_minor, duplicate_minor, extra_minor,
                    timing_minor, status_mismatch_minor, currency_mismatch_minor, group_sum_mismatch_minor,
                    bridge_broken_minor, right_side_total_minor, left_residual_minor, right_residual_minor,
                    balanced, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, runId, segment, currency, 1000L, 900L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, bridge, 900L, 0L, 0L, balanced ? 1 : 0, now);
    }
}
