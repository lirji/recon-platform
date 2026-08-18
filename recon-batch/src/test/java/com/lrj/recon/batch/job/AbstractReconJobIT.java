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
 * M2 端到端集成测试基类 (H2, 免 Docker): 建/清源表 + Batch 元数据 + recon 全表, 提供 seed / launch 助手。
 *
 * <p>源表 {@code recon_src_marketing} / {@code recon_src_accounting} 的列约定与 {@code ReconM2Config.dbSource}
 * 一致。每方法前清空所有相关表 (含 BATCH_* 执行记录) 保证隔离; 各方法用不同 runId, 账期序号固定 (uk_run 每次被清)。
 */
@SpringBootTest
abstract class AbstractReconJobIT {

    @Autowired protected JobLauncher jobLauncher;
    @Autowired protected Job reconciliationJob;
    @Autowired protected JdbcTemplate jdbc;

    protected static final String SCENARIO = "MARKETING_3WAY";
    protected static final String PERIOD = "2026-08-17";
    protected static final int BUCKET_COUNT = 8;
    protected static final Instant CUTOFF = Instant.parse("2026-08-17T23:00:00Z");
    protected static final Instant WINDOW_FROM = Instant.parse("2026-08-17T00:00:00Z");
    protected static final Instant WINDOW_TO = Instant.parse("2026-08-18T23:59:59Z");
    protected static final Instant BIZ = Instant.parse("2026-08-17T10:00:00Z");

    @BeforeEach
    void resetSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS recon_src_marketing (
                  id VARCHAR(64) PRIMARY KEY, issue_id VARCHAR(128), ccy CHAR(3), amount_minor BIGINT,
                  entry_type VARCHAR(16), biz_status VARCHAR(32), biz_time TIMESTAMP, posting_time TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS recon_src_accounting (
                  id VARCHAR(64) PRIMARY KEY, issue_id VARCHAR(128), ccy CHAR(3), amount_minor BIGINT,
                  entry_type VARCHAR(16), biz_status VARCHAR(32), biz_time TIMESTAMP, posting_time TIMESTAMP)
                """);

        // Batch 执行记录 (FK 顺序) —— 每方法从干净的 JobRepository 开始
        for (String t : List.of(
                "BATCH_STEP_EXECUTION_CONTEXT", "BATCH_STEP_EXECUTION",
                "BATCH_JOB_EXECUTION_CONTEXT", "BATCH_JOB_EXECUTION_PARAMS",
                "BATCH_JOB_EXECUTION", "BATCH_JOB_INSTANCE")) {
            jdbc.update("DELETE FROM " + t);
        }
        // recon 全表 + 源表
        for (String t : List.of("recon_src_marketing", "recon_src_accounting",
                "recon_record", "recon_record_reject", "discrepancy", "discrepancy_disposition",
                "reversal_suggestion", "discrepancy_action", "alert_outbox", "recon_report",
                "recon_run", "recon_run_seq")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    // ---------- seed 源表 ----------

    protected void marketing(String id, String issueId, String ccy, long amountMinor, String entryType,
                             String status, Instant postingTime) {
        insert("recon_src_marketing", id, issueId, ccy, amountMinor, entryType, status, postingTime);
    }

    protected void accounting(String id, String issueId, String ccy, long amountMinor, String entryType,
                              String status, Instant postingTime) {
        insert("recon_src_accounting", id, issueId, ccy, amountMinor, entryType, status, postingTime);
    }

    private void insert(String table, String id, String issueId, String ccy, long amountMinor,
                        String entryType, String status, Instant postingTime) {
        jdbc.update("INSERT INTO " + table
                        + "(id, issue_id, ccy, amount_minor, entry_type, biz_status, biz_time, posting_time) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                id, issueId, ccy, amountMinor, entryType, status,
                Timestamp.from(BIZ), postingTime == null ? null : Timestamp.from(postingTime));
    }

    // ---------- launch ----------

    protected ReconJobContext ctx(String runId, long attempt) {
        return ctx(runId, attempt, BUCKET_COUNT);
    }

    protected ReconJobContext ctx(String runId, long attempt, int bucketCount) {
        return ctx(runId, attempt, bucketCount, 1);
    }

    /** sequenceNo 显式版: 同方法内并发多 Run 须给不同 seq (否则撞 uk_run(scenario,period,seq))。 */
    protected ReconJobContext ctx(String runId, long attempt, int bucketCount, int sequenceNo) {
        return new ReconJobContext(runId, SCENARIO, PERIOD, sequenceNo, CUTOFF, WINDOW_FROM, WINDOW_TO,
                bucketCount, attempt);
    }

    protected JobExecution launch(String runId, long attempt) throws Exception {
        return jobLauncher.run(reconciliationJob, ctx(runId, attempt).toJobParameters());
    }

    protected JobExecution launch(String runId, long attempt, int bucketCount) throws Exception {
        return jobLauncher.run(reconciliationJob, ctx(runId, attempt, bucketCount).toJobParameters());
    }

    protected JobExecution launch(String runId, long attempt, int bucketCount, int sequenceNo) throws Exception {
        return jobLauncher.run(reconciliationJob, ctx(runId, attempt, bucketCount, sequenceNo).toJobParameters());
    }

    // ---------- 断言助手 ----------

    protected Long count(String table, String runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE run_id = ?", Long.class, runId);
    }

    protected List<String> discrepancyTypes(String runId) {
        return jdbc.queryForList(
                "SELECT type FROM discrepancy WHERE run_id = ? ORDER BY type", String.class, runId);
    }
}
