package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.AlertOutboxRepository;
import com.lrj.recon.core.domain.model.AlertOutbox;
import com.lrj.recon.core.domain.model.AlertStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * {@link AlertOutboxRepository} 的 JDBC 实现 (按 {@code uk_outbox(idempotency_key)} 幂等 + 中继状态流转, ADR-10)。
 */
@Repository
public class JdbcAlertOutboxStore implements AlertOutboxRepository {

    private final JdbcTemplate jdbc;

    public JdbcAlertOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AlertOutbox> MAPPER = (rs, n) -> AlertOutbox.builder()
            .id(rs.getString("id"))
            .runId(rs.getString("run_id"))
            .fingerprint(rs.getString("fingerprint"))
            .payload(rs.getString("payload"))
            .status(AlertStatus.valueOf(rs.getString("status")))
            .attempt(rs.getInt("attempt"))
            .idempotencyKey(rs.getString("idempotency_key"))
            .createdAt(SqlTimes.instant(rs, "created_at"))
            .sentAt(SqlTimes.instant(rs, "sent_at"))
            .build();

    @Override
    public boolean insertIfAbsent(AlertOutbox a) {
        Instant createdAt = a.createdAt() == null ? Instant.now() : a.createdAt();
        try {
            jdbc.update("""
                    INSERT INTO alert_outbox(id, run_id, fingerprint, payload, status, attempt,
                        idempotency_key, created_at, sent_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,
                    a.id(), a.runId(), a.fingerprint(), a.payload(), a.status().name(), a.attempt(),
                    a.idempotencyKey(), SqlTimes.ts(createdAt), SqlTimes.ts(a.sentAt()));
            return true;
        } catch (DuplicateKeyException idempotentHit) {
            return false;
        }
    }

    @Override
    public List<AlertOutbox> listPending() {
        return jdbc.query("SELECT * FROM alert_outbox WHERE status = 'PENDING' ORDER BY created_at, id", MAPPER);
    }

    @Override
    public void markSent(String id, Instant sentAt) {
        jdbc.update("UPDATE alert_outbox SET status = 'SENT', sent_at = ? WHERE id = ?",
                SqlTimes.ts(sentAt), id);
    }

    @Override
    public void markFailed(String id) {
        jdbc.update("UPDATE alert_outbox SET status = 'FAILED', attempt = attempt + 1 WHERE id = ?", id);
    }
}
