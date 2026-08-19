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

/** B7 · 1:N 明细下钻 API 契约(H2/MockMvc):组底层 staged 记录明细,金额十进制字符串。 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupRecordsDrillDownTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private static final String RUN = "MARKETING_3WAY:2026-08-18:1";
    private static final String SEG1 = "SEG1_MKT_ACCT";

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM recon_record");
    }

    private void rec(String recordId, String side, String role, String matchKey, String groupKey, long amountMinor) {
        jdbc.update("""
                INSERT INTO recon_record(record_id, run_id, segment_id, side, source_role, match_key, group_key,
                    bucket, currency, signed_amount_minor, entry_type, biz_status, biz_time, raw_ref, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                recordId, RUN, SEG1, side, role, matchKey, groupKey, 0, "USD", amountMinor, "ISSUE", "PAID",
                Timestamp.from(Instant.parse("2026-08-18T10:00:00Z")), "tbl:" + recordId,
                Timestamp.from(Instant.parse("2026-08-18T10:00:00Z")));
    }

    @Test
    void drills_down_group_records_of_a_release_order() throws Exception {
        // 发放单 O1 下两条营销发放(左) + 一条账务(右)。
        rec("r-l1", "LEFT", "MARKETING", "I1a", "O1", 1000);
        rec("r-l2", "LEFT", "MARKETING", "I1b", "O1", 2000);
        rec("r-r1", "RIGHT", "ACCOUNTING", "I1a", "O1", 1000);
        // 另一发放单 O2 不应被查出。
        rec("r-x", "LEFT", "MARKETING", "I2", "O2", 500);

        mvc.perform(get("/recon/runs/{id}/records", RUN)
                        .param("segmentId", SEG1).param("groupKey", "O1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(3))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.records.length()").value(3))
                .andExpect(jsonPath("$.records[0].signedAmountMinor").isString());
    }

    @Test
    void empty_group_returns_no_records() throws Exception {
        mvc.perform(get("/recon/runs/{id}/records", RUN).param("segmentId", SEG1).param("groupKey", "NOPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(0));
    }
}
