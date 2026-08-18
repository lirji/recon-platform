package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.ReversalSuggestionRepository;
import com.lrj.recon.core.domain.model.ReversalSuggestion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * {@link ReversalSuggestionRepository} 的 JDBC 实现 (按 {@code uk_rev(idempotency_key)} 幂等)。
 * 本表永不被重跑删除 (ADR-7)。
 */
@Repository
public class JdbcReversalSuggestionStore implements ReversalSuggestionRepository {

    private final JdbcTemplate jdbc;
    private final JdbcDuplicateSafeInsert inserts;

    public JdbcReversalSuggestionStore(JdbcTemplate jdbc, JdbcDuplicateSafeInsert inserts) {
        this.jdbc = jdbc;
        this.inserts = inserts;
    }

    @Override
    public boolean insertIfAbsent(ReversalSuggestion s) {
        Instant createdAt = s.createdAt() == null ? Instant.now() : s.createdAt();
        return inserts.execute(() ->
            jdbc.update("""
                    INSERT INTO reversal_suggestion(id, fingerprint, run_id, group_key,
                        suggested_amount_minor, currency, status, idempotency_key, operator, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """,
                    s.id(), s.fingerprint(), s.runId(), s.groupKey(),
                    s.suggestedAmountMinor(), s.currency(), s.status().name(),
                    s.idempotencyKey(), s.operator(), SqlTimes.ts(createdAt)));
    }
}
