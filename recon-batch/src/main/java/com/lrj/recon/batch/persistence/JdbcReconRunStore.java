package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.ReconRunRepository;
import com.lrj.recon.core.domain.model.ConflictException;
import com.lrj.recon.core.domain.model.ReconRun;
import com.lrj.recon.core.domain.model.ReconRunStatus;
import com.lrj.recon.core.domain.model.RunKey;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link ReconRunRepository} 的 JDBC 实现。
 *
 * <p>范式 (借鉴 risk-platform 的唯一键幂等 + version 条件更新, 不 import 其代码):
 * <ul>
 *   <li>{@code claim}: INSERT 命中 {@code uk_run} / 主键重复 → {@link DuplicateKeyException} 吞并翻成 {@link ConflictException};</li>
 *   <li>{@code save}: 条件 UPDATE {@code WHERE run_id=? AND revision=expected}, 影响行数 ≠ 1 → {@link ConflictException}。</li>
 * </ul>
 */
@Repository
public class JdbcReconRunStore implements ReconRunRepository {

    private final JdbcTemplate jdbc;

    public JdbcReconRunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ReconRun> MAPPER = (rs, n) -> ReconRun.builder()
            .runId(rs.getString("run_id"))
            .key(RunKey.of(rs.getString("scenario_code"), rs.getString("accounting_period"),
                    rs.getInt("sequence_no")))
            .cutoffTime(SqlTimes.instant(rs, "cutoff_time"))
            .matchWindowFrom(SqlTimes.instant(rs, "match_window_from"))
            .matchWindowTo(SqlTimes.instant(rs, "match_window_to"))
            .bucketCount(rs.getInt("bucket_count"))
            .status(ReconRunStatus.valueOf(rs.getString("status")))
            .revision(rs.getLong("revision"))
            .createdAt(SqlTimes.instant(rs, "created_at"))
            .updatedAt(SqlTimes.instant(rs, "updated_at"))
            .startedAt(SqlTimes.instant(rs, "started_at"))
            .finishedAt(SqlTimes.instant(rs, "finished_at"))
            .build();

    @Override
    public void claim(ReconRun run) {
        Instant now = Instant.now();
        Instant createdAt = run.createdAt() == null ? now : run.createdAt();
        Instant updatedAt = run.updatedAt() == null ? now : run.updatedAt();
        try {
            jdbc.update("""
                    INSERT INTO recon_run(run_id, scenario_code, accounting_period, sequence_no,
                        cutoff_time, match_window_from, match_window_to, bucket_count, status, revision,
                        created_at, updated_at, started_at, finished_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    run.runId(), run.scenarioCode(), run.accountingPeriod(), run.sequenceNo(),
                    SqlTimes.ts(run.cutoffTime()), SqlTimes.ts(run.matchWindowFrom()), SqlTimes.ts(run.matchWindowTo()),
                    run.bucketCount(), run.status().name(), run.revision(),
                    SqlTimes.ts(createdAt), SqlTimes.ts(updatedAt),
                    SqlTimes.ts(run.startedAt()), SqlTimes.ts(run.finishedAt()));
        } catch (DuplicateKeyException dup) {
            throw new ConflictException("recon_run already claimed: " + run.key(), dup);
        }
    }

    @Override
    public Optional<ReconRun> find(String runId) {
        List<ReconRun> rows = jdbc.query("SELECT * FROM recon_run WHERE run_id = ?", MAPPER, runId);
        return rows.stream().findFirst();
    }

    @Override
    public void lockScenarioPeriod(String scenarioCode, String accountingPeriod) {
        // ORDER BY 保证并发收敛按相同行顺序加锁，避免死锁；结果只用于持有行锁至外层收敛事务提交。
        jdbc.queryForList("""
                SELECT run_id FROM recon_run
                 WHERE scenario_code = ? AND accounting_period = ?
                 ORDER BY sequence_no
                 FOR UPDATE
                """, String.class, scenarioCode, accountingPeriod);
    }

    @Override
    public boolean isLatestRun(String runId, String scenarioCode, String accountingPeriod) {
        List<String> latest = jdbc.queryForList("""
                SELECT run_id FROM recon_run
                 WHERE scenario_code = ? AND accounting_period = ?
                 ORDER BY sequence_no DESC
                 LIMIT 1
                """, String.class, scenarioCode, accountingPeriod);
        return !latest.isEmpty() && latest.get(0).equals(runId);
    }

    @Override
    public void save(ReconRun run, long expectedRevision) {
        int updated = jdbc.update("""
                UPDATE recon_run
                   SET status = ?, revision = ?, updated_at = ?, started_at = ?, finished_at = ?
                 WHERE run_id = ? AND revision = ?
                """,
                run.status().name(), expectedRevision + 1, SqlTimes.ts(Instant.now()),
                SqlTimes.ts(run.startedAt()), SqlTimes.ts(run.finishedAt()),
                run.runId(), expectedRevision);
        if (updated != 1) {
            throw new ConflictException("optimistic lock failed for run " + run.runId()
                    + " at revision " + expectedRevision);
        }
    }
}
