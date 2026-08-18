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
        try {
            insert(d);
        } catch (DuplicateKeyException exists) {
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
                        + d.fingerprint() + " at version " + d.version(), exists);
            }
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
}
