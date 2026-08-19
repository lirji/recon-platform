package com.lrj.recon.batch.persistence;

import com.lrj.recon.batch.service.ReconConsoleQueryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 管理台跨聚合只读投影的 JDBC 实现。所有动态条件值均使用绑定参数。 */
@Repository
public class JdbcReconConsoleQueryStore implements ReconConsoleQueryRepository {

    private static final String RUN_SELECT = """
            SELECT r.run_id, r.scenario_code, r.accounting_period, r.sequence_no, r.status, r.bucket_count,
                   r.created_at, r.started_at, r.finished_at,
                   (SELECT COUNT(*) FROM discrepancy d WHERE d.run_id = r.run_id) AS discrepancy_count,
                   (SELECT COUNT(*) FROM discrepancy d
                      LEFT JOIN discrepancy_disposition dd ON dd.fingerprint = d.fingerprint
                     WHERE d.run_id = r.run_id
                       AND (dd.id IS NULL OR dd.status = 'REOPENED')) AS open_discrepancy_count,
                   (SELECT CASE WHEN COUNT(*) = 0 THEN NULL
                                WHEN MIN(rr.balanced) = 1 THEN 1 ELSE 0 END
                      FROM recon_report rr WHERE rr.run_id = r.run_id) AS balanced
              FROM recon_run r
            """;

    private static final String DISCREPANCY_SELECT = """
            SELECT d.discrepancy_id, d.run_id, r.scenario_code, r.accounting_period, d.segment_id,
                   d.type, d.bridge_break_stage, d.fingerprint, d.group_key, d.match_key, d.currency,
                   d.expected_amount_minor, d.actual_amount_minor, d.delta_amount_minor,
                   d.left_raw_ref, d.right_raw_ref,
                   COALESCE(dd.status, 'OPEN') AS disposition_status,
                   dd.operator, dd.note, dd.version AS disposition_version,
                   d.created_at, COALESCE(dd.updated_at, d.updated_at) AS updated_at
              FROM discrepancy d
              JOIN recon_run r ON r.run_id = d.run_id
              LEFT JOIN discrepancy_disposition dd ON dd.fingerprint = d.fingerprint
            """;

    private static final RowMapper<RunSummary> RUN_MAPPER = (rs, rowNum) -> new RunSummary(
            rs.getString("run_id"), rs.getString("scenario_code"), rs.getString("accounting_period"),
            rs.getInt("sequence_no"), rs.getString("status"), rs.getInt("bucket_count"),
            SqlTimes.instant(rs, "created_at"), SqlTimes.instant(rs, "started_at"),
            SqlTimes.instant(rs, "finished_at"), rs.getLong("discrepancy_count"),
            rs.getLong("open_discrepancy_count"), nullableBoolean(rs, "balanced"));

    private static final RowMapper<DiscrepancySummary> DISCREPANCY_MAPPER = (rs, rowNum) ->
            new DiscrepancySummary(
                    rs.getString("discrepancy_id"), rs.getString("run_id"), rs.getString("scenario_code"),
                    rs.getString("accounting_period"), rs.getString("segment_id"), rs.getString("type"),
                    rs.getString("bridge_break_stage"), rs.getString("fingerprint"), rs.getString("group_key"),
                    rs.getString("match_key"), rs.getString("currency"), rs.getString("expected_amount_minor"),
                    rs.getString("actual_amount_minor"), rs.getString("delta_amount_minor"),
                    rs.getString("left_raw_ref"), rs.getString("right_raw_ref"),
                    rs.getString("disposition_status"), rs.getString("operator"), rs.getString("note"),
                    nullableInteger(rs, "disposition_version"), SqlTimes.instant(rs, "created_at"),
                    SqlTimes.instant(rs, "updated_at"));

    private final JdbcTemplate jdbc;

    public JdbcReconConsoleQueryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DashboardView dashboard() {
        DashboardMetrics metrics = jdbc.queryForObject("""
                SELECT COUNT(*) AS total_runs,
                       COALESCE(SUM(CASE WHEN status IN ('CREATED','LOADING','MATCHING') THEN 1 ELSE 0 END), 0)
                           AS running_runs,
                       COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completed_runs,
                       COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_runs,
                       COALESCE(SUM(CASE WHEN status = 'REPORT_IMBALANCE' THEN 1 ELSE 0 END), 0) AS imbalanced_runs
                  FROM recon_run
                """, (rs, rowNum) -> new DashboardMetrics(
                rs.getLong("total_runs"), rs.getLong("running_runs"), rs.getLong("completed_runs"),
                rs.getLong("failed_runs"), rs.getLong("imbalanced_runs"), 0, 0, 0));

        long[] dispositions = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN dd.id IS NULL OR dd.status = 'REOPENED'
                                         THEN 1 ELSE 0 END), 0) AS open_count,
                       COALESCE(SUM(CASE WHEN dd.status = 'RESOLVED' THEN 1 ELSE 0 END), 0) AS resolved_count,
                       COALESCE(SUM(CASE WHEN dd.status = 'CLOSED' THEN 1 ELSE 0 END), 0) AS closed_count
                  FROM discrepancy d
                  LEFT JOIN discrepancy_disposition dd ON dd.fingerprint = d.fingerprint
                """, (rs, rowNum) -> new long[]{
                rs.getLong("open_count"), rs.getLong("resolved_count"), rs.getLong("closed_count")});

        DashboardMetrics combined = new DashboardMetrics(metrics.totalRuns(), metrics.runningRuns(),
                metrics.completedRuns(), metrics.failedRuns(), metrics.imbalancedRuns(),
                dispositions[0], dispositions[1], dispositions[2]);
        List<KeyCount> typeCounts = jdbc.query("""
                SELECT type AS count_key, COUNT(*) AS item_count
                  FROM discrepancy
                 GROUP BY type
                 ORDER BY item_count DESC, count_key
                """, (rs, rowNum) -> new KeyCount(rs.getString("count_key"), rs.getLong("item_count")));
        List<RunSummary> recent = listRuns(new RunFilter(null, null, null, 0, 5)).content();
        return new DashboardView(combined, typeCounts, recent);
    }

    @Override
    public PageResult<RunSummary> listRuns(RunFilter filter) {
        SqlFilter sqlFilter = new SqlFilter();
        sqlFilter.eq("r.scenario_code", filter.scenarioCode());
        sqlFilter.eq("r.accounting_period", filter.accountingPeriod());
        sqlFilter.eq("r.status", filter.status());
        long total = count("SELECT COUNT(*) FROM recon_run r" + sqlFilter.where(), sqlFilter.params());
        List<Object> pageParams = new ArrayList<>(sqlFilter.params());
        pageParams.add(filter.size());
        pageParams.add(filter.page() * filter.size());
        List<RunSummary> rows = jdbc.query(RUN_SELECT + sqlFilter.where()
                        + " ORDER BY r.created_at DESC, r.sequence_no DESC, r.run_id DESC LIMIT ? OFFSET ?",
                RUN_MAPPER, pageParams.toArray());
        return PageResult.of(rows, filter.page(), filter.size(), total);
    }

    @Override
    public Optional<RunSummary> findRun(String runId) {
        return jdbc.query(RUN_SELECT + " WHERE r.run_id = ?", RUN_MAPPER, runId).stream().findFirst();
    }

    @Override
    public Optional<RunDetail> findRunDetail(String runId) {
        Optional<RunSummary> run = findRun(runId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        List<ReportEntry> reports = jdbc.query("""
                SELECT segment_id, currency, expected_total_minor, matched_amount_minor,
                       amount_mismatch_minor, missing_minor, duplicate_minor, extra_minor, timing_minor,
                       status_mismatch_minor, currency_mismatch_minor, group_sum_mismatch_minor,
                       bridge_broken_minor, right_side_total_minor, left_residual_minor, right_residual_minor,
                       balanced
                  FROM recon_report
                 WHERE run_id = ?
                 ORDER BY segment_id, currency
                """, (rs, rowNum) -> new ReportEntry(
                rs.getString("segment_id"), rs.getString("currency"), rs.getString("expected_total_minor"),
                rs.getString("matched_amount_minor"), rs.getString("amount_mismatch_minor"),
                rs.getString("missing_minor"), rs.getString("duplicate_minor"), rs.getString("extra_minor"),
                rs.getString("timing_minor"), rs.getString("status_mismatch_minor"),
                rs.getString("currency_mismatch_minor"), rs.getString("group_sum_mismatch_minor"),
                rs.getString("bridge_broken_minor"), rs.getString("right_side_total_minor"),
                rs.getString("left_residual_minor"), rs.getString("right_residual_minor"),
                rs.getInt("balanced") == 1), runId);
        return Optional.of(new RunDetail(run.orElseThrow(), reports));
    }

    @Override
    public PageResult<DiscrepancySummary> listDiscrepancies(DiscrepancyFilter filter) {
        SqlFilter sqlFilter = new SqlFilter();
        sqlFilter.eq("d.run_id", filter.runId());
        sqlFilter.eq("d.type", filter.type());
        if ("OPEN".equals(filter.status())) {
            sqlFilter.raw("dd.id IS NULL");
        } else {
            sqlFilter.eq("dd.status", filter.status());
        }
        sqlFilter.eq("d.segment_id", filter.segmentId());
        sqlFilter.eq("d.currency", filter.currency());
        if (filter.query() != null) {
            String pattern = "%" + filter.query().toLowerCase() + "%";
            sqlFilter.raw("(LOWER(d.discrepancy_id) LIKE ? OR LOWER(d.fingerprint) LIKE ?"
                    + " OR LOWER(COALESCE(d.group_key, '')) LIKE ? OR LOWER(COALESCE(d.match_key, '')) LIKE ?"
                    + " OR LOWER(COALESCE(d.left_raw_ref, '')) LIKE ? OR LOWER(COALESCE(d.right_raw_ref, '')) LIKE ?)",
                    pattern, pattern, pattern, pattern, pattern, pattern);
        }

        String countFrom = " FROM discrepancy d JOIN recon_run r ON r.run_id = d.run_id"
                + " LEFT JOIN discrepancy_disposition dd ON dd.fingerprint = d.fingerprint";
        long total = count("SELECT COUNT(*)" + countFrom + sqlFilter.where(), sqlFilter.params());
        List<Object> pageParams = new ArrayList<>(sqlFilter.params());
        pageParams.add(filter.size());
        pageParams.add(filter.page() * filter.size());
        List<DiscrepancySummary> rows = jdbc.query(DISCREPANCY_SELECT + sqlFilter.where()
                        + " ORDER BY COALESCE(dd.updated_at, d.updated_at) DESC, d.discrepancy_id DESC LIMIT ? OFFSET ?",
                DISCREPANCY_MAPPER, pageParams.toArray());
        return PageResult.of(rows, filter.page(), filter.size(), total);
    }

    @Override
    public Optional<DiscrepancyDetail> findDiscrepancy(String discrepancyId) {
        Optional<DiscrepancySummary> summary = jdbc.query(
                DISCREPANCY_SELECT + " WHERE d.discrepancy_id = ?", DISCREPANCY_MAPPER, discrepancyId)
                .stream().findFirst();
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        String fingerprint = summary.orElseThrow().fingerprint();
        List<ActionEntry> actions = jdbc.query("""
                SELECT id, action_type, payload, operator, created_at
                  FROM discrepancy_action
                 WHERE fingerprint = ?
                 ORDER BY created_at DESC, id DESC
                """, (rs, rowNum) -> new ActionEntry(
                rs.getString("id"), rs.getString("action_type"), rs.getString("payload"),
                rs.getString("operator"), SqlTimes.instant(rs, "created_at")), fingerprint);
        List<ReversalEntry> reversals = jdbc.query("""
                SELECT id, run_id, group_key, suggested_amount_minor, currency, status, operator, created_at
                  FROM reversal_suggestion
                 WHERE fingerprint = ?
                 ORDER BY created_at DESC, id DESC
                """, (rs, rowNum) -> new ReversalEntry(
                rs.getString("id"), rs.getString("run_id"), rs.getString("group_key"),
                rs.getString("suggested_amount_minor"), rs.getString("currency"), rs.getString("status"),
                rs.getString("operator"), SqlTimes.instant(rs, "created_at")), fingerprint);
        List<AlertEntry> alerts = jdbc.query("""
                SELECT id, run_id, status, attempt, created_at, sent_at
                  FROM alert_outbox
                 WHERE fingerprint = ?
                 ORDER BY created_at DESC, id DESC
                """, (rs, rowNum) -> new AlertEntry(
                rs.getString("id"), rs.getString("run_id"), rs.getString("status"), rs.getInt("attempt"),
                SqlTimes.instant(rs, "created_at"), SqlTimes.instant(rs, "sent_at")), fingerprint);
        return Optional.of(new DiscrepancyDetail(summary.orElseThrow(), actions, reversals, alerts));
    }

    @Override
    public List<RefineViolation> findRefineViolations(String runId, int limit) {
        // KI-6: DB 侧聚合找函数性 refine 违规 —— 同一 (segment, match_key) 落多个 group_key。null match_key 逐条单边
        // 路由、不参与勾兑, 排除。按冲突组数降序便于优先处置; LIMIT 有界(上层传 N+1 判是否截断)。
        return jdbc.query("""
                SELECT segment_id, match_key, COUNT(DISTINCT group_key) AS group_count
                  FROM recon_record
                 WHERE run_id = ? AND match_key IS NOT NULL
                 GROUP BY segment_id, match_key
                HAVING COUNT(DISTINCT group_key) > 1
                 ORDER BY group_count DESC, segment_id, match_key
                 LIMIT ?
                """, (rs, rowNum) -> new RefineViolation(
                rs.getString("segment_id"), rs.getString("match_key"), rs.getLong("group_count")),
                runId, limit);
    }

    @Override
    public List<GroupRecordDetail> findGroupRecords(String runId, String segmentId, String groupKey, int limit) {
        // B7: 组内 staged 明细(左右两侧),按 side/match_key/record_id 稳定排序;金额转十进制字符串防精度损失。
        return jdbc.query("""
                SELECT record_id, side, source_role, match_key, currency, signed_amount_minor,
                       entry_type, biz_status, raw_ref
                  FROM recon_record
                 WHERE run_id = ? AND segment_id = ? AND group_key = ?
                 ORDER BY side, (match_key IS NULL), match_key, record_id
                 LIMIT ?
                """, (rs, rowNum) -> new GroupRecordDetail(
                rs.getString("record_id"), rs.getString("side"), rs.getString("source_role"),
                rs.getString("match_key"), rs.getString("currency"),
                Long.toString(rs.getLong("signed_amount_minor")), rs.getString("entry_type"),
                rs.getString("biz_status"), rs.getString("raw_ref")),
                runId, segmentId, groupKey, limit);
    }

    private long count(String sql, List<Object> params) {
        Long value = jdbc.queryForObject(sql, Long.class, params.toArray());
        return value == null ? 0 : value;
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.intValue() == 1;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.intValue();
    }

    private static final class SqlFilter {
        private final List<String> conditions = new ArrayList<>();
        private final List<Object> params = new ArrayList<>();

        void eq(String column, Object value) {
            if (value != null) {
                raw(column + " = ?", value);
            }
        }

        void raw(String condition, Object... values) {
            conditions.add(condition);
            params.addAll(List.of(values));
        }

        String where() {
            return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        }

        List<Object> params() {
            return List.copyOf(params);
        }
    }
}
