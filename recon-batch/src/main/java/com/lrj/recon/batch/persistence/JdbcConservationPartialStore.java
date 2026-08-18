package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.ConservationPartialRepository;
import com.lrj.recon.core.domain.model.ConservationPartial;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * {@link ConservationPartialRepository} 的 JDBC 实现 (M3 单遍守恒局部结果 {@code recon_report_partial})。
 *
 * <p>幂等 upsert 走可移植 <b>update-else-insert</b> (借鉴既有 {@code JdbcReconReportStore} 范式):
 * 先按 {@code uk_partial(run_id, segment_id, bucket, currency)} 条件 UPDATE, 未命中再 INSERT; 并发撞唯一键
 * ({@link DuplicateKeyException}) 回退再 UPDATE。partition 每 chunk 落"累计到当前"的快照 → 完成即完整局部结果;
 * 断点/重放同键覆盖, 无重复行。
 */
@Repository
public class JdbcConservationPartialStore implements ConservationPartialRepository {

    private final JdbcTemplate jdbc;

    public JdbcConservationPartialStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ConservationPartial> MAPPER = (rs, n) -> ConservationPartial.builder()
            .runId(rs.getString("run_id"))
            .segmentId(rs.getString("segment_id"))
            .bucket(rs.getInt("bucket"))
            .subIndex(rs.getInt("sub_index"))
            .currency(rs.getString("currency"))
            .expectedTotalMinor(rs.getLong("expected_total_minor"))
            .rightSideTotalMinor(rs.getLong("right_side_total_minor"))
            .matchedLeftMinor(rs.getLong("matched_left_minor"))
            .matchedRightMinor(rs.getLong("matched_right_minor"))
            .missingMinor(rs.getLong("missing_minor"))
            .extraMinor(rs.getLong("extra_minor"))
            .amountMismatchLeftMinor(rs.getLong("amount_mismatch_left_minor"))
            .statusLeftMinor(rs.getLong("status_left_minor"))
            .timingLeftMinor(rs.getLong("timing_left_minor"))
            .groupSumLeftMinor(rs.getLong("group_sum_left_minor"))
            .duplicateLeftMinor(rs.getLong("duplicate_left_minor"))
            .bridgeBrokenLeftMinor(rs.getLong("bridge_broken_left_minor"))
            .bridgeBrokenRightMinor(rs.getLong("bridge_broken_right_minor"))
            .currencyMismatchLeftMinor(rs.getLong("currency_mismatch_left_minor"))
            .currencyMismatchRightMinor(rs.getLong("currency_mismatch_right_minor"))
            .build();

    @Override
    public void savePartials(Iterable<ConservationPartial> partials) {
        for (ConservationPartial p : partials) {
            if (update(p) != 1) {
                try {
                    insert(p);
                } catch (DuplicateKeyException concurrent) {
                    update(p);
                }
            }
        }
    }

    private int update(ConservationPartial p) {
        return jdbc.update("""
                UPDATE recon_report_partial
                   SET expected_total_minor = ?, right_side_total_minor = ?, matched_left_minor = ?,
                       matched_right_minor = ?, missing_minor = ?, extra_minor = ?, amount_mismatch_left_minor = ?,
                       status_left_minor = ?, timing_left_minor = ?, group_sum_left_minor = ?,
                       duplicate_left_minor = ?, bridge_broken_left_minor = ?, bridge_broken_right_minor = ?,
                       currency_mismatch_left_minor = ?, currency_mismatch_right_minor = ?
                 WHERE run_id = ? AND segment_id = ? AND bucket = ? AND sub_index = ? AND currency = ?
                """,
                p.expectedTotalMinor(), p.rightSideTotalMinor(), p.matchedLeftMinor(),
                p.matchedRightMinor(), p.missingMinor(), p.extraMinor(), p.amountMismatchLeftMinor(),
                p.statusLeftMinor(), p.timingLeftMinor(), p.groupSumLeftMinor(),
                p.duplicateLeftMinor(), p.bridgeBrokenLeftMinor(), p.bridgeBrokenRightMinor(),
                p.currencyMismatchLeftMinor(), p.currencyMismatchRightMinor(),
                p.runId(), p.segmentId(), p.bucket(), p.subIndex(), p.currency());
    }

    private void insert(ConservationPartial p) {
        jdbc.update("""
                INSERT INTO recon_report_partial(id, run_id, segment_id, bucket, sub_index, currency,
                    expected_total_minor, right_side_total_minor, matched_left_minor, matched_right_minor,
                    missing_minor, extra_minor, amount_mismatch_left_minor, status_left_minor, timing_left_minor,
                    group_sum_left_minor, duplicate_left_minor, bridge_broken_left_minor, bridge_broken_right_minor,
                    currency_mismatch_left_minor, currency_mismatch_right_minor, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                java.util.UUID.randomUUID().toString(), p.runId(), p.segmentId(), p.bucket(), p.subIndex(), p.currency(),
                p.expectedTotalMinor(), p.rightSideTotalMinor(), p.matchedLeftMinor(), p.matchedRightMinor(),
                p.missingMinor(), p.extraMinor(), p.amountMismatchLeftMinor(), p.statusLeftMinor(), p.timingLeftMinor(),
                p.groupSumLeftMinor(), p.duplicateLeftMinor(), p.bridgeBrokenLeftMinor(), p.bridgeBrokenRightMinor(),
                p.currencyMismatchLeftMinor(), p.currencyMismatchRightMinor(), SqlTimes.ts(Instant.now()));
    }

    @Override
    public List<ConservationPartial> listByRun(String runId) {
        return jdbc.query("""
                SELECT * FROM recon_report_partial WHERE run_id = ? ORDER BY segment_id, bucket, sub_index, currency
                """, MAPPER, runId);
    }

    @Override
    public int deleteStaleBucketPartials(String runId, String segmentId, int bucket, int subIndex, int subFanout) {
        // #1: 只清本 bucket 的陈旧形状行, 保 partition 断点续跑 (不动其它 bucket / 同形状兄弟子分片)。
        if (subIndex < 0) {
            // 整桶 worker: 删该 bucket 全部 sub-bucket 残留 (上次拆分留下的 sub_index>=0)。
            return jdbc.update("""
                    DELETE FROM recon_report_partial
                     WHERE run_id = ? AND segment_id = ? AND bucket = ? AND sub_index >= 0
                    """, runId, segmentId, bucket);
        }
        // sub-bucket worker: 删整桶陈旧行 (-1) 与超出当前 fanout 的陈旧子分片; 保留同形状兄弟 (0..fanout-1)。
        return jdbc.update("""
                DELETE FROM recon_report_partial
                 WHERE run_id = ? AND segment_id = ? AND bucket = ? AND (sub_index = -1 OR sub_index >= ?)
                """, runId, segmentId, bucket, subFanout);
    }

    @Override
    public int deleteByRunBounded(String runId, int limit) {
        return jdbc.update("""
                DELETE FROM recon_report_partial
                 WHERE id IN (
                     SELECT sub.id FROM (
                         SELECT id FROM recon_report_partial WHERE run_id = ? LIMIT ?
                     ) sub
                 )
                """, runId, limit);
    }
}
