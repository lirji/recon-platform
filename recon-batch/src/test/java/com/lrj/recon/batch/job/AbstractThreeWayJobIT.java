package com.lrj.recon.batch.job;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * M4 营销三方端到端集成测试基类 (H2, 免 Docker): 建/清三张源表 (marketing/accounting/channel) + Batch 元数据 +
 * recon 全表, 提供 seed / launch(marketingThreeWayJob) 助手。
 *
 * <p>源表列约定与 {@link com.lrj.recon.scenario.MarketingThreeWayScenario} 的描述符投影一致:
 * <ul>
 *   <li>{@code recon_src_marketing}: order_no(发放单) + issue_id(营销发放ID) —— SEG1 左侧;</li>
 *   <li>{@code recon_src_accounting}: order_no + issue_id + channel_serial_no —— spine, SEG1 右侧(投 issue_id) /
 *       SEG2 左侧(投 channel_serial_no);</li>
 *   <li>{@code recon_src_channel}: channel_serial_no(渠道流水号) —— SEG2 右侧。</li>
 * </ul>
 */
@SpringBootTest
abstract class AbstractThreeWayJobIT {

    @Autowired protected JobLauncher jobLauncher;
    @Autowired protected Job marketingThreeWayJob;
    @Autowired protected JdbcTemplate jdbc;

    protected static final String SCENARIO = "MARKETING_3WAY";
    protected static final String PERIOD = "2026-08-17";
    protected static final int BUCKET_COUNT = 8;
    protected static final Instant CUTOFF = Instant.parse("2026-08-17T23:00:00Z");
    protected static final Instant WINDOW_FROM = Instant.parse("2026-08-17T00:00:00Z");
    protected static final Instant WINDOW_TO = Instant.parse("2026-08-18T23:59:59Z");
    protected static final Instant BIZ = Instant.parse("2026-08-17T10:00:00Z");

    protected static final String SEG1 = "SEG1_MKT_ACCT";
    protected static final String SEG2 = "SEG2_ACCT_CHANNEL";

    @BeforeEach
    void resetSchema() {
        // 与单段基类 AbstractReconJobIT 共用同一 H2 库 (DB_CLOSE_DELAY=-1), 但源表 schema 不同 (M4 多 order_no /
        // channel_serial_no 列)。故用 DROP+CREATE 强制建 M4 三方 schema (而非 CREATE IF NOT EXISTS, 避免撞上单段
        // 建的窄表)。单段基类的窄列 INSERT 仍能容忍本宽表 (多余列取 NULL), 反之不行, 故这里权威重建。
        for (String t : List.of("recon_src_marketing", "recon_src_accounting", "recon_src_channel")) {
            jdbc.execute("DROP TABLE IF EXISTS " + t);
        }
        jdbc.execute("""
                CREATE TABLE recon_src_marketing (
                  id VARCHAR(64) PRIMARY KEY, order_no VARCHAR(128), issue_id VARCHAR(128),
                  ccy CHAR(3), amount_minor BIGINT, entry_type VARCHAR(16), biz_status VARCHAR(32),
                  biz_time TIMESTAMP, posting_time TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE recon_src_accounting (
                  id VARCHAR(64) PRIMARY KEY, order_no VARCHAR(128), issue_id VARCHAR(128),
                  channel_serial_no VARCHAR(128), ccy CHAR(3), amount_minor BIGINT, entry_type VARCHAR(16),
                  biz_status VARCHAR(32), biz_time TIMESTAMP, posting_time TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE recon_src_channel (
                  id VARCHAR(64) PRIMARY KEY, channel_serial_no VARCHAR(128),
                  ccy CHAR(3), amount_minor BIGINT, entry_type VARCHAR(16), biz_status VARCHAR(32),
                  biz_time TIMESTAMP, posting_time TIMESTAMP)
                """);

        for (String t : List.of(
                "BATCH_STEP_EXECUTION_CONTEXT", "BATCH_STEP_EXECUTION",
                "BATCH_JOB_EXECUTION_CONTEXT", "BATCH_JOB_EXECUTION_PARAMS",
                "BATCH_JOB_EXECUTION", "BATCH_JOB_INSTANCE")) {
            jdbc.update("DELETE FROM " + t);
        }
        for (String t : List.of("recon_src_marketing", "recon_src_accounting", "recon_src_channel",
                "recon_record", "recon_record_reject", "discrepancy", "discrepancy_disposition",
                "reversal_suggestion", "discrepancy_action", "alert_outbox", "recon_report",
                "recon_report_partial", "recon_run", "recon_run_seq")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    // ---------- seed 源表 ----------

    /** 营销发放 (SEG1 左): 发放单 order_no + 营销发放ID issue_id。 */
    protected void marketing(String id, String orderNo, String issueId, String ccy, long amountMinor,
                             String entryType, String status) {
        jdbc.update("INSERT INTO recon_src_marketing"
                        + "(id, order_no, issue_id, ccy, amount_minor, entry_type, biz_status, biz_time, posting_time)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                id, orderNo, issueId, ccy, amountMinor, entryType, status,
                Timestamp.from(BIZ), Timestamp.from(BIZ));
    }

    /** 账务 spine (SEG1 右 投 issue_id / SEG2 左 投 channel_serial_no)。issueId / channelSerialNo 可空。 */
    protected void accounting(String id, String orderNo, String issueId, String channelSerialNo,
                              String ccy, long amountMinor, String entryType, String status) {
        accounting(id, orderNo, issueId, channelSerialNo, ccy, amountMinor, entryType, status, BIZ);
    }

    protected void accounting(String id, String orderNo, String issueId, String channelSerialNo,
                              String ccy, long amountMinor, String entryType, String status, Instant postingTime) {
        jdbc.update("INSERT INTO recon_src_accounting"
                        + "(id, order_no, issue_id, channel_serial_no, ccy, amount_minor, entry_type, biz_status,"
                        + " biz_time, posting_time) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, orderNo, issueId, channelSerialNo, ccy, amountMinor, entryType, status,
                Timestamp.from(BIZ), postingTime == null ? null : Timestamp.from(postingTime));
    }

    /** 渠道 (SEG2 右): 渠道流水号 channel_serial_no。 */
    protected void channel(String id, String channelSerialNo, String ccy, long amountMinor,
                           String entryType, String status) {
        channel(id, channelSerialNo, ccy, amountMinor, entryType, status, BIZ);
    }

    protected void channel(String id, String channelSerialNo, String ccy, long amountMinor,
                           String entryType, String status, Instant postingTime) {
        jdbc.update("INSERT INTO recon_src_channel"
                        + "(id, channel_serial_no, ccy, amount_minor, entry_type, biz_status, biz_time, posting_time)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                id, channelSerialNo, ccy, amountMinor, entryType, status,
                Timestamp.from(BIZ), postingTime == null ? null : Timestamp.from(postingTime));
    }

    // ---------- launch ----------

    protected JobExecution launch(String runId, long attempt) throws Exception {
        return jobLauncher.run(marketingThreeWayJob,
                new ReconJobContext(runId, SCENARIO, PERIOD, 1, CUTOFF, WINDOW_FROM, WINDOW_TO, BUCKET_COUNT, attempt)
                        .toJobParameters());
    }

    // ---------- 断言助手 ----------

    protected Long count(String table, String runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE run_id = ?", Long.class, runId);
    }

    protected List<String> discrepancyTypes(String runId, String segmentId) {
        return jdbc.queryForList(
                "SELECT type FROM discrepancy WHERE run_id = ? AND segment_id = ? ORDER BY type",
                String.class, runId, segmentId);
    }

    protected String runStatus(String runId) {
        return jdbc.queryForObject("SELECT status FROM recon_run WHERE run_id=?", String.class, runId);
    }
}
