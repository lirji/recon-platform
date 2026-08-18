package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.DiscrepancyRepository;
import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * {@link DiscrepancyRepository} 的 JDBC 实现。
 *
 * <p>幂等 upsert 走可移植的 <b>update-else-insert</b> (借鉴 risk-platform catalog 范式, 不 import 其代码):
 * 先按 {@code uk_disc(run_id, fingerprint)} 条件 UPDATE, 未命中再 INSERT; 并发 INSERT 撞唯一键
 * ({@link DuplicateKeyException}) 则回退再 UPDATE。对空 match_key/group_key 类型也幂等 (fingerprint 非空)。
 */
@Repository
public class JdbcDiscrepancyStore implements DiscrepancyRepository {

    private final JdbcTemplate jdbc;

    public JdbcDiscrepancyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Discrepancy> MAPPER = (rs, n) -> Discrepancy.builder()
            .discrepancyId(rs.getString("discrepancy_id"))
            .runId(rs.getString("run_id"))
            .segmentId(rs.getString("segment_id"))
            .type(DiscrepancyType.valueOf(rs.getString("type")))
            .bridgeBreakStage(rs.getString("bridge_break_stage"))
            .fingerprint(rs.getString("fingerprint"))
            .groupKey(rs.getString("group_key"))
            .matchKey(rs.getString("match_key"))
            .currency(rs.getString("currency"))
            .expectedAmountMinor(rs.getLong("expected_amount_minor"))
            .actualAmountMinor(rs.getLong("actual_amount_minor"))
            .deltaAmountMinor(rs.getLong("delta_amount_minor"))
            .leftRawRef(rs.getString("left_raw_ref"))
            .rightRawRef(rs.getString("right_raw_ref"))
            .build();

    @Override
    public void upsertByFingerprint(Discrepancy d) {
        if (updateByFingerprint(d) == 1) {
            return;
        }
        try {
            insert(d);
        } catch (DuplicateKeyException concurrent) {
            updateByFingerprint(d); // 并发已插入, 回退为更新, 保持幂等
        }
    }

    private int updateByFingerprint(Discrepancy d) {
        return jdbc.update("""
                UPDATE discrepancy
                   SET type = ?, bridge_break_stage = ?, group_key = ?, match_key = ?, currency = ?,
                       expected_amount_minor = ?, actual_amount_minor = ?, delta_amount_minor = ?,
                       left_raw_ref = ?, right_raw_ref = ?, machine_result = 1, updated_at = ?
                 WHERE run_id = ? AND fingerprint = ?
                """,
                d.type().name(), d.bridgeBreakStage(), d.groupKey(), d.matchKey(), d.currency(),
                d.expectedAmountMinor(), d.actualAmountMinor(), d.deltaAmountMinor(),
                d.leftRawRef(), d.rightRawRef(), SqlTimes.ts(Instant.now()),
                d.runId(), d.fingerprint());
    }

    private void insert(Discrepancy d) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO discrepancy(discrepancy_id, run_id, segment_id, type, bridge_break_stage,
                    fingerprint, group_key, match_key, currency, expected_amount_minor, actual_amount_minor,
                    delta_amount_minor, left_raw_ref, right_raw_ref, machine_result, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)
                """,
                d.discrepancyId(), d.runId(), d.segmentId(), d.type().name(), d.bridgeBreakStage(),
                d.fingerprint(), d.groupKey(), d.matchKey(), d.currency(),
                d.expectedAmountMinor(), d.actualAmountMinor(), d.deltaAmountMinor(),
                d.leftRawRef(), d.rightRawRef(), SqlTimes.ts(now), SqlTimes.ts(now));
    }

    @Override
    public int deleteOpenMachineByRunBounded(String runId, int limit) {
        return jdbc.update("""
                DELETE FROM discrepancy
                 WHERE discrepancy_id IN (
                     SELECT sub.discrepancy_id FROM (
                         SELECT discrepancy_id FROM discrepancy
                          WHERE run_id = ? AND machine_result = 1 LIMIT ?
                     ) sub
                 )
                """, runId, limit);
    }

    @Override
    public List<Discrepancy> listByRun(String runId) {
        return jdbc.query("SELECT * FROM discrepancy WHERE run_id = ? ORDER BY created_at, discrepancy_id",
                MAPPER, runId);
    }
}
