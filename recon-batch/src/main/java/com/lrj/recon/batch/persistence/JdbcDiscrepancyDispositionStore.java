package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.DiscrepancyDispositionRepository;
import com.lrj.recon.core.domain.model.ConflictException;
import com.lrj.recon.core.domain.model.DiscrepancyDisposition;
import com.lrj.recon.core.domain.model.DispositionStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link DiscrepancyDispositionRepository} 的 JDBC 实现 (按 {@code uk_disp(fingerprint)} + version 乐观锁)。
 * 本表永不被重跑删除 (ADR-7); 无删除方法即结构性保证。
 */
@Repository
public class JdbcDiscrepancyDispositionStore implements DiscrepancyDispositionRepository {

    private final JdbcTemplate jdbc;

    public JdbcDiscrepancyDispositionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DiscrepancyDisposition> MAPPER = (rs, n) -> DiscrepancyDisposition.builder()
            .id(rs.getString("id"))
            .fingerprint(rs.getString("fingerprint"))
            .scenarioCode(rs.getString("scenario_code"))
            .accountingPeriod(rs.getString("accounting_period"))
            .segmentId(rs.getString("segment_id"))
            .status(DispositionStatus.valueOf(rs.getString("status")))
            .operator(rs.getString("operator"))
            .note(rs.getString("note"))
            .lastSeenRunId(rs.getString("last_seen_run_id"))
            .version(rs.getInt("version"))
            .createdAt(SqlTimes.instant(rs, "created_at"))
            .updatedAt(SqlTimes.instant(rs, "updated_at"))
            .build();

    @Override
    public void upsert(DiscrepancyDisposition d) {
        // 正常更新不得靠“先撞唯一键再继续事务”：PostgreSQL 唯一键异常会使当前事务 aborted。
        // 先判存在并走 version 条件更新；并发首插撞唯一键则翻译为 Conflict 并让外层事务回滚。
        if (findByFingerprint(d.fingerprint()).isPresent()) {
            int updated = jdbc.update("""
                    UPDATE discrepancy_disposition
                       SET status = ?, operator = ?, note = ?, last_seen_run_id = ?,
                           version = version + 1, updated_at = ?
                     WHERE fingerprint = ? AND version = ?
                    """,
                    d.status().name(), d.operator(), d.note(), d.lastSeenRunId(),
                    SqlTimes.ts(Instant.now()), d.fingerprint(), d.version());
            if (updated != 1) {
                throw new ConflictException("disposition optimistic lock failed for fingerprint "
                        + d.fingerprint() + " at version " + d.version());
            }
            return;
        }
        try {
            insert(d);
        } catch (DuplicateKeyException concurrentInsert) {
            throw new ConflictException("disposition concurrently created for fingerprint " + d.fingerprint(),
                    concurrentInsert);
        }
    }

    private void insert(DiscrepancyDisposition d) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO discrepancy_disposition(id, fingerprint, scenario_code, accounting_period,
                    segment_id, status, operator, note, last_seen_run_id, version, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                d.id(), d.fingerprint(), d.scenarioCode(), d.accountingPeriod(), d.segmentId(),
                d.status().name(), d.operator(), d.note(), d.lastSeenRunId(), d.version(),
                SqlTimes.ts(now), SqlTimes.ts(now));
    }

    @Override
    public Optional<DiscrepancyDisposition> findByFingerprint(String fingerprint) {
        List<DiscrepancyDisposition> rows = jdbc.query(
                "SELECT * FROM discrepancy_disposition WHERE fingerprint = ?", MAPPER, fingerprint);
        return rows.stream().findFirst();
    }

    @Override
    public List<DiscrepancyDisposition> findLiveByScenarioPeriod(String scenarioCode, String accountingPeriod) {
        return jdbc.query("""
                SELECT * FROM discrepancy_disposition
                 WHERE scenario_code = ? AND accounting_period = ? AND status <> 'STALE'
                """, MAPPER, scenarioCode, accountingPeriod);
    }

    @Override
    public boolean relink(String fingerprint, String lastSeenRunId, int expectedVersion) {
        // A1①: 保持状态与 version 不变, 仅刷新 last_seen_run_id/updated_at (不与人工乐观锁竞争)。
        return jdbc.update("""
                UPDATE discrepancy_disposition
                   SET last_seen_run_id = ?, updated_at = ?
                 WHERE fingerprint = ? AND version = ? AND status <> 'STALE'
                """, lastSeenRunId, SqlTimes.ts(Instant.now()), fingerprint, expectedVersion) == 1;
    }

    @Override
    public boolean markStale(String fingerprint, String lastSeenRunId, int expectedVersion) {
        // A1②/③: 置 STALE 自动关闭 + bump version; 已 STALE 的不重复处理 (幂等)。
        return jdbc.update("""
                UPDATE discrepancy_disposition
                   SET status = 'STALE', last_seen_run_id = ?, version = version + 1, updated_at = ?
                 WHERE fingerprint = ? AND version = ? AND status <> 'STALE'
                """, lastSeenRunId, SqlTimes.ts(Instant.now()), fingerprint, expectedVersion) == 1;
    }
}
