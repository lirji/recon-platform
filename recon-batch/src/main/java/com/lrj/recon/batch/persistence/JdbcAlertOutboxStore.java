package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.AlertOutboxRepository;
import com.lrj.recon.core.domain.model.AlertOutbox;
import com.lrj.recon.core.domain.model.AlertStatus;
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
    private final JdbcDuplicateSafeInsert inserts;

    public JdbcAlertOutboxStore(JdbcTemplate jdbc, JdbcDuplicateSafeInsert inserts) {
        this.jdbc = jdbc;
        this.inserts = inserts;
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
        return inserts.execute(() ->
            jdbc.update("""
                    INSERT INTO alert_outbox(id, run_id, fingerprint, payload, status, attempt,
                        idempotency_key, created_at, sent_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,
                    a.id(), a.runId(), a.fingerprint(), a.payload(), a.status().name(), a.attempt(),
                    a.idempotencyKey(), SqlTimes.ts(createdAt), SqlTimes.ts(a.sentAt())));
    }

    @Override
    public List<AlertOutbox> listPending() {
        return jdbc.query("SELECT * FROM alert_outbox WHERE status = 'PENDING' ORDER BY created_at, id", MAPPER);
    }

    @Override
    public List<AlertOutbox> listRetryable(int maxAttempt) {
        // 首投 (PENDING) + 补投 (FAILED 且未超投递上限); 超 maxAttempt 的失败条目视为死信不再补投。
        return jdbc.query(
                "SELECT * FROM alert_outbox WHERE (status = 'PENDING' OR (status = 'FAILED' AND attempt < ?)) "
                        + "ORDER BY created_at, id",
                MAPPER, maxAttempt);
    }

    @Override
    public void markSent(String id, Instant sentAt) {
        jdbc.update("UPDATE alert_outbox SET status = 'SENT', sent_at = ? WHERE id = ?",
                SqlTimes.ts(sentAt), id);
    }

    @Override
    public void markFailed(String id) {
        // 并发中继允许重复投递 (at-least-once)，但迟到失败不得把已经成功的 SENT 降级回 FAILED。
        jdbc.update("""
                UPDATE alert_outbox SET status = 'FAILED', attempt = attempt + 1
                 WHERE id = ? AND status <> 'SENT'
                """, id);
    }
}
