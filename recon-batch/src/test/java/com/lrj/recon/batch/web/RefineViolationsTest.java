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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A5 / KI-6 · 函数性 refine 违规诊断 {@code GET /recon/runs/{id}/refine-violations}。验证:同一 (segment, match_key)
 * 落多个 group_key(脏跨表数据,产假 BRIDGE_BROKEN/EXTRA 而守恒抓不到)被显式列出;同 group 的干净键与 null 键不误报。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefineViolationsTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static final Instant T = Instant.parse("2026-08-18T10:00:00Z");

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM recon_record");
    }

    @Test
    void functionalRefineViolationIsReported() throws Exception {
        // 脏: 同一 (SEG1, MK-DIRTY) 左挂 Ga、右挂 Gb → 违反函数性
        seed("r1", "run-dirty", "SEG1_MKT_ACCT", "LEFT", "MK-DIRTY", "Ga");
        seed("r2", "run-dirty", "SEG1_MKT_ACCT", "RIGHT", "MK-DIRTY", "Gb");
        // 干净: 同一 (SEG1, MK-CLEAN) 两侧同 group Gc
        seed("r3", "run-dirty", "SEG1_MKT_ACCT", "LEFT", "MK-CLEAN", "Gc");
        seed("r4", "run-dirty", "SEG1_MKT_ACCT", "RIGHT", "MK-CLEAN", "Gc");
        // null match_key: 逐条单边路由、不参与勾兑, 应被排除
        seed("r5", "run-dirty", "SEG1_MKT_ACCT", "LEFT", null, "Gnull");

        mvc.perform(get("/recon/runs/run-dirty/refine-violations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-dirty"))
                .andExpect(jsonPath("$.violationCount").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.violations[0].segmentId").value("SEG1_MKT_ACCT"))
                .andExpect(jsonPath("$.violations[0].matchKey").value("MK-DIRTY"))
                .andExpect(jsonPath("$.violations[0].distinctGroupCount").value(2));
    }

    @Test
    void cleanDataReportsNoViolations() throws Exception {
        seed("c1", "run-clean", "SEG1_MKT_ACCT", "LEFT", "MK-1", "G1");
        seed("c2", "run-clean", "SEG1_MKT_ACCT", "RIGHT", "MK-1", "G1");
        seed("c3", "run-clean", "SEG2_ACCT_CHANNEL", "LEFT", "MK-2", "G2");

        mvc.perform(get("/recon/runs/run-clean/refine-violations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.violationCount").value(0))
                .andExpect(jsonPath("$.violations").isEmpty());
    }

    private void seed(String id, String run, String segment, String side, String matchKey, String groupKey) {
        Timestamp now = Timestamp.from(T);
        jdbc.update("""
                INSERT INTO recon_record(record_id, run_id, segment_id, side, source_role, match_key, group_key,
                    bucket, currency, signed_amount_minor, entry_type, biz_time, raw_ref, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, run, segment, side, "MARKETING", matchKey, groupKey, 0, "USD", 100L,
                "ISSUE", now, "t:" + id, now);
    }
}
