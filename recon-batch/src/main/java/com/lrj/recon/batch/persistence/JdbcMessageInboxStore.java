package com.lrj.recon.batch.persistence;

import com.lrj.recon.batch.service.MessageInboxStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class JdbcMessageInboxStore implements MessageInboxStore {
    private final JdbcTemplate jdbc;

    public JdbcMessageInboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ClaimResult claim(String tenantId, String consumerGroup, String eventId, String payloadHash) {
        try {
            jdbc.update("""
                    INSERT INTO ods_message_inbox
                    (tenant_id,consumer_group,event_id,payload_hash,status,received_at,processed_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, tenantId, consumerGroup, eventId, payloadHash, "PROCESSING",
                    Timestamp.from(Instant.now()), null);
            return ClaimResult.CLAIMED;
        } catch (DuplicateKeyException replay) {
            String existing = jdbc.queryForObject("""
                    SELECT payload_hash FROM ods_message_inbox
                    WHERE tenant_id=? AND consumer_group=? AND event_id=?
                    """, String.class, tenantId, consumerGroup, eventId);
            if (!payloadHash.equals(existing)) {
                throw new IllegalStateException("inbox event payload conflict");
            }
            return ClaimResult.REPLAY;
        }
    }

    @Override
    public void markProcessed(String tenantId, String consumerGroup, String eventId) {
        if (jdbc.update("""
                UPDATE ods_message_inbox SET status='PROCESSED',processed_at=?
                WHERE tenant_id=? AND consumer_group=? AND event_id=? AND status='PROCESSING'
                """, Timestamp.from(Instant.now()), tenantId, consumerGroup, eventId) != 1) {
            throw new IllegalStateException("inbox event was not claimed");
        }
    }
}
