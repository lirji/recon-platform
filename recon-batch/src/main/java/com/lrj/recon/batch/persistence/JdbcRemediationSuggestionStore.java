package com.lrj.recon.batch.persistence;

import com.lrj.recon.batch.service.RemediationSuggestionQuery;
import com.lrj.recon.core.application.port.out.RemediationSuggestionRepository;
import com.lrj.recon.core.domain.model.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRemediationSuggestionStore implements RemediationSuggestionRepository, RemediationSuggestionQuery {
    private final JdbcTemplate jdbc;
    public JdbcRemediationSuggestionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean insert(RemediationSuggestion value) {
        try {
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO remediation_suggestion
                    (tenant_id,suggestion_id,scenario_code,discrepancy_ref,award_item_no,original_operation_no,
                     action_type,reason,status,approval_ref,version,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, value.tenantId(), value.suggestionId(), value.scenarioCode(), value.discrepancyRef(),
                    value.awardItemNo(), value.originalOperationNo(), value.action().name(), value.reason(),
                    value.status().name(), null, value.version(), Timestamp.from(now), Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException replay) { return false; }
    }

    @Override public Optional<RemediationSuggestion> find(String tenantId, String suggestionId) {
        return jdbc.query("""
                SELECT tenant_id,suggestion_id,scenario_code,discrepancy_ref,award_item_no,original_operation_no,
                       action_type,reason,status,version
                FROM remediation_suggestion WHERE tenant_id=? AND suggestion_id=?
                """, (rs, row) -> new RemediationSuggestion(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), RemediationAction.valueOf(rs.getString(7)),
                rs.getString(8), RemediationStatus.valueOf(rs.getString(9)), rs.getLong(10)),
                tenantId, suggestionId).stream().findFirst();
    }

    @Override public boolean updateExpectedVersion(RemediationSuggestion value, long expectedVersion,
                                                   String approvalRef) {
        return jdbc.update("""
                UPDATE remediation_suggestion SET status=?,approval_ref=COALESCE(?,approval_ref),version=?,updated_at=?
                WHERE tenant_id=? AND suggestion_id=? AND version=?
                """, value.status().name(), approvalRef, value.version(), Timestamp.from(Instant.now()),
                value.tenantId(), value.suggestionId(), expectedVersion) == 1;
    }

    @Override
    public List<RemediationView> list(String tenantId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT tenant_id,suggestion_id,scenario_code,discrepancy_ref,award_item_no,original_operation_no,
                       action_type,reason,status,approval_ref,version,created_at,updated_at
                  FROM remediation_suggestion WHERE tenant_id=?
                """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (status != null) {
            sql.append(" AND status=?");
            params.add(status);
        }
        sql.append(" ORDER BY created_at DESC, suggestion_id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), (rs, row) -> new RemediationView(
                rs.getString("tenant_id"), rs.getString("suggestion_id"), rs.getString("scenario_code"),
                rs.getString("discrepancy_ref"), rs.getString("award_item_no"), rs.getString("original_operation_no"),
                rs.getString("action_type"), rs.getString("reason"), rs.getString("status"),
                rs.getString("approval_ref"), rs.getLong("version"),
                SqlTimes.instant(rs, "created_at"), SqlTimes.instant(rs, "updated_at")),
                params.toArray());
    }

    @Override
    public long count(String tenantId, String status) {
        if (status == null) {
            Long value = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM remediation_suggestion WHERE tenant_id=?", Long.class, tenantId);
            return value == null ? 0 : value;
        }
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM remediation_suggestion WHERE tenant_id=? AND status=?",
                Long.class, tenantId, status);
        return value == null ? 0 : value;
    }
}
