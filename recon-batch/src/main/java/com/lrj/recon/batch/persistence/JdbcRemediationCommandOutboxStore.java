package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.RemediationCommandOutboxRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcRemediationCommandOutboxStore implements RemediationCommandOutboxRepository {
    private final JdbcTemplate jdbc;
    public JdbcRemediationCommandOutboxStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean enqueue(String tenantId, String commandId, String suggestionId, String idempotencyKey,
                                     String payloadHash, String payload, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO remediation_command_outbox
                    (tenant_id,command_id,suggestion_id,idempotency_key,payload_hash,payload,status,attempt_count,
                     next_attempt_at,published_at,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, tenantId, commandId, suggestionId, idempotencyKey, payloadHash, payload,
                    "PENDING", 0, Timestamp.from(now), null, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException replay) { return false; }
    }

    @Override public List<Command> claimDue(String workerId, Instant now, int limit) {
        jdbc.update("""
                UPDATE remediation_command_outbox
                SET status='FAILED',lease_owner=NULL,lease_until=NULL
                WHERE status='SENDING' AND lease_until<?
                """, Timestamp.from(now));
        List<Command> candidates = jdbc.query("""
                SELECT tenant_id,command_id,suggestion_id,payload,attempt_count
                FROM remediation_command_outbox
                WHERE status IN ('PENDING','FAILED') AND (next_attempt_at IS NULL OR next_attempt_at<=?)
                ORDER BY created_at,command_id LIMIT ?
                """, (rs, row) -> new Command(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5)), Timestamp.from(now), limit);
        return candidates.stream().filter(command -> jdbc.update("""
                UPDATE remediation_command_outbox
                SET status='SENDING',lease_owner=?,lease_until=?,attempt_count=attempt_count+1
                WHERE tenant_id=? AND command_id=? AND status IN ('PENDING','FAILED')
                """, workerId, Timestamp.from(now.plusSeconds(60)), command.tenantId(), command.commandId()) == 1)
                .map(command -> new Command(command.tenantId(), command.commandId(), command.suggestionId(),
                        command.payload(), command.attemptCount() + 1))
                .toList();
    }

    @Override public java.util.Optional<String> findSuggestionId(String tenantId, String commandId) {
        List<String> values = jdbc.query("""
                SELECT suggestion_id FROM remediation_command_outbox WHERE tenant_id=? AND command_id=?
                """, (rs, row) -> rs.getString(1), tenantId, commandId);
        return values.stream().findFirst();
    }

    @Override public void markPublished(String tenantId, String commandId, String workerId, Instant now) {
        requireOne(jdbc.update("""
                UPDATE remediation_command_outbox
                SET status='PUBLISHED',published_at=?,lease_owner=NULL,lease_until=NULL
                WHERE tenant_id=? AND command_id=? AND status='SENDING' AND lease_owner=?
                """, Timestamp.from(now), tenantId, commandId, workerId));
    }

    @Override public void markFailed(String tenantId, String commandId, String workerId,
                                     Instant nextAttempt, int maxAttempts, String error) {
        requireOne(jdbc.update("""
                UPDATE remediation_command_outbox
                SET status=CASE WHEN attempt_count>=? THEN 'DEAD' ELSE 'FAILED' END,next_attempt_at=?,
                    last_error=?,lease_owner=NULL,lease_until=NULL
                WHERE tenant_id=? AND command_id=? AND status='SENDING' AND lease_owner=?
                """, maxAttempts, Timestamp.from(nextAttempt), truncate(error), tenantId, commandId, workerId));
    }

    private static void requireOne(int changed) {
        if (changed != 1) throw new IllegalStateException("remediation command outbox lease was lost");
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
