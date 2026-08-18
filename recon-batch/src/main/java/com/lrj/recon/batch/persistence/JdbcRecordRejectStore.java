package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.spi.RejectedRow;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 畸形行落库 {@code recon_record_reject} (M2 loadStep: 标准化失败的源行入 reject, <b>不中断整流</b>)。
 *
 * <p>非 recon-core 端口 —— 拒绝行是组合根的运维审计, 不属领域内核契约, 故直接在 recon-batch 持久化层实现,
 * 不污染 {@code application.port.out}。JDBC 局限于 {@code ..persistence..} (ArchUnit 门禁)。
 */
@Repository
public class JdbcRecordRejectStore {

    private final JdbcTemplate jdbc;

    public JdbcRecordRejectStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 批量写入某 (run, segment, side-role) 的拒绝行。空列表直接返回。 */
    public void saveAll(String runId, String segmentId, SourceRole sourceRole, List<RejectedRow> rejects) {
        if (rejects == null || rejects.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        String role = sourceRole == null ? null : sourceRole.name();
        jdbc.batchUpdate("""
                INSERT INTO recon_record_reject(id, run_id, segment_id, source_role, raw_ref, reason, raw_payload, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                RejectedRow r = rejects.get(i);
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, runId);
                ps.setString(3, segmentId);
                ps.setString(4, role);
                ps.setString(5, r.rawRef());
                ps.setString(6, truncate(r.reason(), 128));
                ps.setString(7, r.rawPayload());
                ps.setTimestamp(8, SqlTimes.ts(now));
            }

            @Override
            public int getBatchSize() {
                return rejects.size();
            }
        });
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 重跑前分批清理该 Run 的机器拒绝结果，防相同行每次重跑重复累积。 */
    public int deleteByRunBounded(String runId, int limit) {
        return jdbc.update("""
                DELETE FROM recon_record_reject
                 WHERE id IN (
                     SELECT sub.id FROM (
                         SELECT id FROM recon_record_reject WHERE run_id = ? LIMIT ?
                     ) sub
                 )
                """, runId, limit);
    }
}
