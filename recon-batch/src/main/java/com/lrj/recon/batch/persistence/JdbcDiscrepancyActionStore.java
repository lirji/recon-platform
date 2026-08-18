package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.DiscrepancyActionRepository;
import com.lrj.recon.core.domain.model.DiscrepancyAction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * {@link DiscrepancyActionRepository} 的 JDBC 实现 (按 {@code uk_action(idempotency_key)} 幂等)。
 * 属处理/人工痕迹, 无删除方法 —— 结构上永不被重跑删除 (ADR-7)。
 */
@Repository
public class JdbcDiscrepancyActionStore implements DiscrepancyActionRepository {

    private final JdbcTemplate jdbc;
    private final JdbcDuplicateSafeInsert inserts;

    public JdbcDiscrepancyActionStore(JdbcTemplate jdbc, JdbcDuplicateSafeInsert inserts) {
        this.jdbc = jdbc;
        this.inserts = inserts;
    }

    @Override
    public boolean insertIfAbsent(DiscrepancyAction a) {
        Instant createdAt = a.createdAt() == null ? Instant.now() : a.createdAt();
        return inserts.execute(() ->
            jdbc.update("""
                    INSERT INTO discrepancy_action(id, fingerprint, action_type, idempotency_key,
                        payload, operator, created_at)
                    VALUES (?,?,?,?,?,?,?)
                    """,
                    a.id(), a.fingerprint(), a.actionType().name(), a.idempotencyKey(),
                    a.payload(), a.operator(), SqlTimes.ts(createdAt)));
    }
}
