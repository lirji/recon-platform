package com.lrj.recon.batch.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.recon.batch.config.ScenarioDefinitionSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M5 REST 接口 (设计 §11 M5) 端到端: 发起 Run / 重跑 / 人工核销 / 报表查询 + 参数校验 (400) / 未找到 (404) /
 * 乐观锁冲突 (409) / 幂等。发起接口跑真实 {@code marketingThreeWayJob} (H2, 免 Docker), 核销接口对 seed 差异操作。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiscrepancyControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScenarioDefinitionSeeder scenarioSeeder;
    private final ObjectMapper json = new ObjectMapper();

    private static final String SCENARIO = "MARKETING_3WAY";
    private static final String PERIOD = "2026-08-20";
    private static final Instant BIZ = Instant.parse("2026-08-20T10:00:00Z");

    @BeforeEach
    void reset() {
        // 其它共享 Spring 上下文测试会清理场景表；本用例显式恢复内置定义，消除执行顺序依赖。
        scenarioSeeder.run(null);
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
                "recon_ods_cash_expected", "recon_ods_cash_accounting", "recon_ods_cash_channel",
                "recon_ods_entitlement_expected", "recon_ods_entitlement_internal",
                "recon_ods_entitlement_provider",
                "recon_record", "recon_record_reject", "discrepancy", "discrepancy_disposition",
                "reversal_suggestion", "discrepancy_action", "alert_outbox", "recon_report",
                "recon_report_partial", "recon_run", "recon_run_seq")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    @Test
    void launchRunThenQueryReportReturnsBalanced() throws Exception {
        // 干净三方: 营销↔账务↔渠道 各 1000
        jdbc.update("INSERT INTO recon_src_marketing(id, order_no, issue_id, ccy, amount_minor, entry_type,"
                        + " biz_status, biz_time, posting_time) VALUES ('m1','O1','I1','USD',1000,'ISSUE','PAID',?,?)",
                Timestamp.from(BIZ), Timestamp.from(BIZ));
        jdbc.update("INSERT INTO recon_src_accounting(id, order_no, issue_id, channel_serial_no, ccy, amount_minor,"
                        + " entry_type, biz_status, biz_time, posting_time)"
                        + " VALUES ('a1','O1','I1','C1','USD',1000,'ISSUE','PAID',?,?)",
                Timestamp.from(BIZ), Timestamp.from(BIZ));
        jdbc.update("INSERT INTO recon_src_channel(id, channel_serial_no, ccy, amount_minor, entry_type,"
                        + " biz_status, biz_time, posting_time) VALUES ('c1','C1','USD',1000,'ISSUE','PAID',?,?)",
                Timestamp.from(BIZ), Timestamp.from(BIZ));

        MvcResult launched = mvc.perform(post("/recon/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioCode":"%s","accountingPeriod":"%s","jobName":"marketingThreeWayJob","bucketCount":8}
                                """.formatted(SCENARIO, PERIOD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(SCENARIO + ":" + PERIOD + ":1"))
                .andExpect(jsonPath("$.sequenceNo").value(1))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn();
        String runId = json.readTree(launched.getResponse().getContentAsString()).get("runId").asText();

        mvc.perform(get("/recon/runs/{id}/report", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.reports.length()").value(2)); // SEG1 USD + SEG2 USD
    }

    @Test
    void benefitCashRunRequiresTenantAndNeverScansAnotherTenant() throws Exception {
        mvc.perform(post("/recon/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioCode":"BENEFIT_CASH_3WAY","accountingPeriod":"%s"}
                                """.formatted(PERIOD)))
                .andExpect(status().isBadRequest());

        insertCash("recon_ods_cash_expected", "tenant-a", "E-A", "REQ-A", "CLIENT-A", null, 500L,
                "marketing-award-expected:REQ-A");
        insertCash("recon_ods_cash_accounting", "tenant-a", "A-A", "REQ-A", "CLIENT-A", "SER-A", 500L,
                "benefit-fulfillment:BO-A|marketing-award-expected:REQ-A");
        insertCash("recon_ods_cash_channel", "tenant-a", "C-A", "REQ-A", "CLIENT-A", "SER-A", 500L,
                "benefit-fulfillment:BO-A|marketing-award-expected:REQ-A");
        // 同租户再放一组真实差异，用来验证现金通用引擎也能回传三项关联字段。
        insertCash("recon_ods_cash_expected", "tenant-a", "E-C", "REQ-C", "CLIENT-C", null, 500L,
                "marketing-award-expected:REQ-C");
        insertCash("recon_ods_cash_accounting", "tenant-a", "A-C", "REQ-C", "CLIENT-C", "SER-C", 400L,
                "benefit-fulfillment:BO-C|marketing-award-expected:REQ-C");
        insertCash("recon_ods_cash_channel", "tenant-a", "C-C", "REQ-C", "CLIENT-C", "SER-C", 400L,
                "benefit-fulfillment:BO-C|marketing-award-expected:REQ-C");
        // 另一租户只有应发；若 reader 全租户扫描，本 Run 会产生 MISSING 并失衡。
        insertCash("recon_ods_cash_expected", "tenant-b", "E-B", "REQ-B", "CLIENT-B", null, 900L,
                "marketing-award-expected:REQ-B");

        MvcResult launched = mvc.perform(post("/recon/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioCode":"BENEFIT_CASH_3WAY","accountingPeriod":"%s","tenantId":"tenant-a"}
                                """.formatted(PERIOD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn();
        String runId = json.readTree(launched.getResponse().getContentAsString()).path("runId").asText();

        mvc.perform(get("/recon/runs/{id}/report", runId))
                .andExpect(status().isOk())
                // balanced 是守恒等式，不等价于“零差异”；下面单独断言唯一业务差异。
                .andExpect(jsonPath("$.balanced").value(true));
        mvc.perform(get("/recon/discrepancies").param("runId", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].expectedSourceSystem").value("MARKETING"))
                .andExpect(jsonPath("$.content[0].marketingSourceRequestId").value("REQ-C"))
                .andExpect(jsonPath("$.content[0].benefitOrderNo").value("BO-C"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_record WHERE run_id=?", Integer.class, runId))
                .isEqualTo(8); // 两组各三条事实，accounting 作为 spine 在两段各读一次
    }

    @Test
    void nonCashRunUsesQuantityModelAndPublishesCorrelationFields() throws Exception {
        insertEntitlement("recon_ods_entitlement_expected", "tenant-a", "E-1", "CLIENT-1",
                "EXPECTED", "marketing-award-expected:REQ-1", "MARKETING", "REQ-1", null);
        insertEntitlement("recon_ods_entitlement_internal", "tenant-a", "I-1", "CLIENT-1",
                "ISSUED", "benefit-fulfillment:BO-1", null, "REQ-1", "BO-1");
        // tenant-b 的 provider 不得补齐 tenant-a 的缺失。
        insertEntitlement("recon_ods_entitlement_provider", "tenant-b", "P-OTHER", "CLIENT-1",
                "ISSUED", "benefit-fulfillment:BO-OTHER", null, "REQ-OTHER", "BO-OTHER");

        MvcResult launched = mvc.perform(post("/recon/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioCode":"ENTITLEMENT_FULFILLMENT","accountingPeriod":"%s","tenantId":"tenant-a"}
                                """.formatted(PERIOD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn();
        String runId = json.readTree(launched.getResponse().getContentAsString()).path("runId").asText();
        // JobExecution 完成与业务 Run 判差状态是两个维度。
        assertThat(jdbc.queryForObject("SELECT status FROM recon_run WHERE run_id=?", String.class, runId))
                .isEqualTo("REPORT_IMBALANCE");

        mvc.perform(get("/recon/discrepancies").param("runId", runId).param("type", "MISSING_PROVIDER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].measureKind").value("QUANTITY"))
                .andExpect(jsonPath("$.content[0].expectedQuantity").value("1"))
                .andExpect(jsonPath("$.content[0].internalQuantity").value("1"))
                .andExpect(jsonPath("$.content[0].providerQuantity").value("0"))
                .andExpect(jsonPath("$.content[0].expectedSourceSystem").value("MARKETING"))
                .andExpect(jsonPath("$.content[0].marketingSourceRequestId").value("REQ-1"))
                .andExpect(jsonPath("$.content[0].benefitOrderNo").value("BO-1"));
    }

    @Test
    void repeatedLaunchesKeepRunLocalDiscrepanciesAndGlobalHandlerIdempotency() throws Exception {
        // 同一账期、同一业务键连续发起两个新 Run；fingerprint 相同，但每个 Run 都必须有自己的机器差异行。
        jdbc.update("INSERT INTO recon_src_marketing(id, order_no, issue_id, ccy, amount_minor, entry_type,"
                        + " biz_status, biz_time, posting_time) VALUES ('m2','O2','I2','USD',1000,'ISSUE','PAID',?,?)",
                Timestamp.from(BIZ), Timestamp.from(BIZ));
        jdbc.update("INSERT INTO recon_src_accounting(id, order_no, issue_id, channel_serial_no, ccy, amount_minor,"
                        + " entry_type, biz_status, biz_time, posting_time)"
                        + " VALUES ('a2','O2','I2','C2','USD',900,'ISSUE','PAID',?,?)",
                Timestamp.from(BIZ), Timestamp.from(BIZ));
        jdbc.update("INSERT INTO recon_src_channel(id, channel_serial_no, ccy, amount_minor, entry_type,"
                        + " biz_status, biz_time, posting_time) VALUES ('c2','C2','USD',900,'ISSUE','PAID',?,?)",
                Timestamp.from(BIZ), Timestamp.from(BIZ));

        String request = """
                {"scenarioCode":"%s","accountingPeriod":"%s","jobName":"marketingThreeWayJob","bucketCount":8}
                """.formatted(SCENARIO, PERIOD);
        mvc.perform(post("/recon/runs").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sequenceNo").value(1));
        mvc.perform(post("/recon/runs").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sequenceNo").value(2));

        String run1 = SCENARIO + ":" + PERIOD + ":1";
        String run2 = SCENARIO + ":" + PERIOD + ":2";
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM discrepancy WHERE run_id=?", Long.class, run1))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM discrepancy WHERE run_id=?", Long.class, run2))
                .isEqualTo(1L);
        assertThat(jdbc.queryForList(
                "SELECT discrepancy_id FROM discrepancy ORDER BY run_id", String.class))
                .hasSize(2).doesNotHaveDuplicates();
        // fingerprint 级处理产物跨重复发起仍幂等，不重复冲正或告警。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reversal_suggestion", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_outbox", Long.class)).isEqualTo(1L);
    }

    @Test
    void resolveThenCloseThenConflict() throws Exception {
        String did = seedDiscrepancy("run-rest-mc", "R".repeat(64));

        mvc.perform(post("/recon/discrepancies/{id}/resolve", did).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"ops\",\"note\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.version").value(0));

        mvc.perform(post("/recon/discrepancies/{id}/close", did).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"ops\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.version").value(1));

        // 陈旧版本 → 409
        mvc.perform(post("/recon/discrepancies/{id}/close", did).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"ops\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void resolveUnknownDiscrepancyReturns404() throws Exception {
        mvc.perform(post("/recon/discrepancies/{id}/resolve", "no-such").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"ops\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankOperatorReturns400() throws Exception {
        String did = seedDiscrepancy("run-rest-400", "B".repeat(64));
        mvc.perform(post("/recon/discrepancies/{id}/resolve", did).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void launchWithoutScenarioReturns400() throws Exception {
        mvc.perform(post("/recon/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountingPeriod\":\"2026-08-20\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void launchRejectsUnsupportedOrOversizedScenarioBeforePersistence() throws Exception {
        mvc.perform(post("/recon/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioCode":"UNKNOWN","accountingPeriod":"2026-08-20"}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/recon/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioCode":"%s","accountingPeriod":"2026-08-20"}
                                """.formatted("S".repeat(33))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void launchRejectsUnsafeBucketCountAndNonReproducibleJobSelection() throws Exception {
        mvc.perform(post("/recon/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioCode":"%s","accountingPeriod":"%s","bucketCount":0}
                                """.formatted(SCENARIO, PERIOD)))
                .andExpect(status().isBadRequest());

        // rerun 端点没有 jobName 参数，首发必须使用场景/配置可稳定重建的 Job，不能任意选另一个 Job。
        mvc.perform(post("/recon/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioCode":"%s","accountingPeriod":"%s","jobName":"reconciliationJob"}
                                """.formatted(SCENARIO, PERIOD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void manualClearingRejectsValuesThatExceedStorageContract() throws Exception {
        String did = seedDiscrepancy("run-rest-size", "S".repeat(64));

        mvc.perform(post("/recon/discrepancies/{id}/resolve", did).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"%s"}
                                """.formatted("x".repeat(65))))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/recon/discrepancies/{id}/resolve", did).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"ops","note":"%s"}
                                """.formatted("n".repeat(513))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rerunUnknownRunReturns404() throws Exception {
        mvc.perform(post("/recon/runs/{id}/rerun", "nope"))
                .andExpect(status().isNotFound());
    }

    private void insertCash(String table, String tenantId, String eventId, String orderNo,
                            String issueId, String channelSerialNo, long amountMinor, String rawRef) {
        Timestamp time = Timestamp.from(BIZ);
        jdbc.update("INSERT INTO " + table + " (tenant_id,id,event_id,issue_id,order_no,channel_serial_no,"
                        + "ccy,amount_minor,entry_type,biz_status,biz_time,posting_time,raw_ref,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, eventId, eventId, issueId, orderNo, channelSerialNo, "USD", amountMinor, "ISSUE",
                "ISSUED", time, time, rawRef, time);
    }

    private void insertEntitlement(String table, String tenantId, String eventId, String issueId,
                                   String fulfillmentStatus, String rawRef, String expectedSourceSystem,
                                   String sourceRequestId, String benefitOrderNo) {
        Timestamp time = Timestamp.from(BIZ);
        jdbc.update("INSERT INTO " + table + " (tenant_id,id,event_id,issue_id,sku_id,quantity,"
                        + "fulfillment_status,occurred_at,raw_ref,created_at,expected_source_system,"
                        + "marketing_source_request_id,benefit_order_no) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, eventId, eventId, issueId, "COUPON-1", 1L, fulfillmentStatus, time, rawRef, time,
                expectedSourceSystem, sourceRequestId, benefitOrderNo);
    }

    /** seed 一个 COMPLETED run + 一条 AMOUNT_MISMATCH 差异 (免跑 Job), 返回 discrepancy_id。 */
    private String seedDiscrepancy(String runId, String fingerprint) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO recon_run(run_id, scenario_code, accounting_period, sequence_no, cutoff_time,
                    match_window_from, match_window_to, bucket_count, status, revision, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, runId, SCENARIO, PERIOD, 1, now, now, now, 8, "COMPLETED", 4, now, now);
        String did = "disc-" + runId;
        jdbc.update("""
                INSERT INTO discrepancy(discrepancy_id, run_id, segment_id, type, fingerprint,
                    expected_amount_minor, actual_amount_minor, delta_amount_minor, machine_result, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,1,?,?)
                """, did, runId, "SEG1_MKT_ACCT", "AMOUNT_MISMATCH", fingerprint, 1000, 900, 100, now, now);
        return did;
    }
}
