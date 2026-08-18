package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.ReconReportRepository;
import com.lrj.recon.core.domain.model.ReconReport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * {@link ReconReportRepository} 的 JDBC 实现 (按 {@code uk_report(run_id, segment_id, currency)} 幂等 upsert)。
 * upsert 走可移植 update-else-insert, 支持同 Run 重算覆盖。
 */
@Repository
public class JdbcReconReportStore implements ReconReportRepository {

    private final JdbcTemplate jdbc;

    public JdbcReconReportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ReconReport> MAPPER = (rs, n) -> ReconReport.builder()
            .runId(rs.getString("run_id"))
            .segmentId(rs.getString("segment_id"))
            .currency(rs.getString("currency"))
            .expectedTotalMinor(rs.getLong("expected_total_minor"))
            .matchedAmountMinor(rs.getLong("matched_amount_minor"))
            .amountMismatchMinor(rs.getLong("amount_mismatch_minor"))
            .missingMinor(rs.getLong("missing_minor"))
            .duplicateMinor(rs.getLong("duplicate_minor"))
            .extraMinor(rs.getLong("extra_minor"))
            .timingMinor(rs.getLong("timing_minor"))
            .statusMismatchMinor(rs.getLong("status_mismatch_minor"))
            .currencyMismatchMinor(rs.getLong("currency_mismatch_minor"))
            .groupSumMismatchMinor(rs.getLong("group_sum_mismatch_minor"))
            .bridgeBrokenMinor(rs.getLong("bridge_broken_minor"))
            .rightSideTotalMinor(rs.getLong("right_side_total_minor"))
            .leftResidualMinor(rs.getLong("left_residual_minor"))
            .rightResidualMinor(rs.getLong("right_residual_minor"))
            .balanced(rs.getBoolean("balanced"))
            .build();

    @Override
    public void saveAll(Iterable<ReconReport> reports) {
        for (ReconReport report : reports) {
            if (update(report) != 1) {
                try {
                    insert(report);
                } catch (DuplicateKeyException concurrent) {
                    update(report);
                }
            }
        }
    }

    private int update(ReconReport r) {
        return jdbc.update("""
                UPDATE recon_report
                   SET expected_total_minor = ?, matched_amount_minor = ?, amount_mismatch_minor = ?,
                       missing_minor = ?, duplicate_minor = ?, extra_minor = ?, timing_minor = ?,
                       status_mismatch_minor = ?, currency_mismatch_minor = ?, group_sum_mismatch_minor = ?,
                       bridge_broken_minor = ?, right_side_total_minor = ?, left_residual_minor = ?,
                       right_residual_minor = ?, balanced = ?
                 WHERE run_id = ? AND segment_id = ? AND currency = ?
                """,
                r.expectedTotalMinor(), r.matchedAmountMinor(), r.amountMismatchMinor(),
                r.missingMinor(), r.duplicateMinor(), r.extraMinor(), r.timingMinor(),
                r.statusMismatchMinor(), r.currencyMismatchMinor(), r.groupSumMismatchMinor(),
                r.bridgeBrokenMinor(), r.rightSideTotalMinor(), r.leftResidualMinor(),
                r.rightResidualMinor(), r.balanced() ? 1 : 0,
                r.runId(), r.segmentId(), r.currency());
    }

    private void insert(ReconReport r) {
        jdbc.update("""
                INSERT INTO recon_report(report_id, run_id, segment_id, currency, expected_total_minor,
                    matched_amount_minor, amount_mismatch_minor, missing_minor, duplicate_minor, extra_minor,
                    timing_minor, status_mismatch_minor, currency_mismatch_minor, group_sum_mismatch_minor,
                    bridge_broken_minor, right_side_total_minor, left_residual_minor, right_residual_minor,
                    balanced, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                reportId(r), r.runId(), r.segmentId(), r.currency(), r.expectedTotalMinor(),
                r.matchedAmountMinor(), r.amountMismatchMinor(), r.missingMinor(), r.duplicateMinor(),
                r.extraMinor(), r.timingMinor(), r.statusMismatchMinor(), r.currencyMismatchMinor(),
                r.groupSumMismatchMinor(), r.bridgeBrokenMinor(), r.rightSideTotalMinor(),
                r.leftResidualMinor(), r.rightResidualMinor(), r.balanced() ? 1 : 0, SqlTimes.ts(Instant.now()));
    }

    /**
     * report_id 代理键 (VARCHAR(64)): 业务身份由 {@code uk_report(run_id, segment_id, currency)} 保证,
     * 故此处用随机 UUID 即可, 不与业务键长度耦合 (runId 本身可达 64 字符, 不能拼接)。
     */
    private static String reportId(ReconReport r) {
        return java.util.UUID.randomUUID().toString();
    }

    @Override
    public List<ReconReport> listByRun(String runId) {
        return jdbc.query("SELECT * FROM recon_report WHERE run_id = ? ORDER BY segment_id, currency",
                MAPPER, runId);
    }

    @Override
    public int deleteByRunBounded(String runId, int limit) {
        // #5: 分批删旧报表行 (可移植 IN-子查询 LIMIT, 与其它 bounded 删除同风格), 清孤儿陈旧金额。
        return jdbc.update("""
                DELETE FROM recon_report
                 WHERE report_id IN (
                     SELECT sub.report_id FROM (
                         SELECT report_id FROM recon_report WHERE run_id = ? LIMIT ?
                     ) sub
                 )
                """, runId, limit);
    }
}
