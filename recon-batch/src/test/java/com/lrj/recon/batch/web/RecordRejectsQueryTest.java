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

/** 载入期拒绝行分页查询契约(H2/MockMvc)。 */
@SpringBootTest
@AutoConfigureMockMvc
class RecordRejectsQueryTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static final String RUN = "MARKETING_3WAY:2026-08-18:1";
    private static final Instant T0 = Instant.parse("2026-08-18T10:00:00Z");

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM recon_record_reject");
        jdbc.update("DELETE FROM recon_run WHERE run_id=?", RUN);
        Timestamp time = Timestamp.from(T0);
        jdbc.update("""
                INSERT INTO recon_run(run_id, scenario_code, accounting_period, sequence_no, cutoff_time,
                    match_window_from, match_window_to, bucket_count, status, revision, created_at, updated_at,
                    started_at, finished_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, RUN, "MARKETING_3WAY", "2026-08-18", 1, time, time, time, 1, "COMPLETED", 1L,
                time, time, time, time);
    }

    private void reject(String id, String segmentId, String role, String reason, Instant created) {
        jdbc.update("""
                INSERT INTO recon_record_reject(id, run_id, segment_id, source_role, raw_ref, reason, raw_payload, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, RUN, segmentId, role, "/tmp/mkt.csv:12", reason, "bad,row", Timestamp.from(created));
    }

    @Test
    void lists_rejects_newest_first_and_filters_by_role() throws Exception {
        reject("rj-1", "SEG1_MKT_ACCT", "MARKETING", "invalid amount", T0);
        reject("rj-2", "SEG2_ACCT_CHANNEL", "CHANNEL", "missing key", T0.plusSeconds(30));

        mvc.perform(get("/recon/runs/{id}/rejects", RUN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value("rj-2"))
                .andExpect(jsonPath("$.content[0].reason").value("missing key"))
                .andExpect(jsonPath("$.content[1].id").value("rj-1"));

        mvc.perform(get("/recon/runs/{id}/rejects", RUN).param("sourceRole", "MARKETING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sourceRole").value("MARKETING"));
    }

    @Test
    void unknown_run_is_404() throws Exception {
        mvc.perform(get("/recon/runs/{id}/rejects", "NO-SUCH-RUN"))
                .andExpect(status().isNotFound());
    }
}
