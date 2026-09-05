package com.lrj.recon.batch.persistence;

import com.lrj.recon.batch.service.EntitlementReconStore;
import com.lrj.recon.entitlement.model.EntitlementDiscrepancy;
import com.lrj.recon.entitlement.model.EntitlementDiscrepancyType;
import com.lrj.recon.entitlement.model.EntitlementMatchGroup;
import com.lrj.recon.entitlement.model.EntitlementObservation;
import com.lrj.recon.entitlement.model.EntitlementRole;
import com.lrj.recon.entitlement.model.EntitlementStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JDBC 版非金额权益对账存储；所有查询都强制携带 tenant 与事件时间窗口。 */
@Repository
public class JdbcEntitlementReconStore implements EntitlementReconStore {

    private static final String SEGMENT = "ENTITLEMENT";

    private final JdbcTemplate jdbc;

    public JdbcEntitlementReconStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 先对三张 ODS 表的 issueId 做有界合并，再逐组加载事实，确保不会全租户扫描或一次性载入整个账期。
     */
    @Override
    public List<ReconCase> nextPage(String tenantId, Instant windowFrom, Instant windowTo,
                                    String afterIssueId, int limit) {
        String sql = """
                SELECT issue_id FROM (
                    SELECT issue_id FROM recon_ods_entitlement_expected
                     WHERE tenant_id=? AND occurred_at>=? AND occurred_at<=? AND issue_id>?
                    UNION
                    SELECT issue_id FROM recon_ods_entitlement_internal
                     WHERE tenant_id=? AND occurred_at>=? AND occurred_at<=? AND issue_id>?
                    UNION
                    SELECT issue_id FROM recon_ods_entitlement_provider
                     WHERE tenant_id=? AND occurred_at>=? AND occurred_at<=? AND issue_id>?
                ) entitlement_issues ORDER BY issue_id LIMIT ?
                """;
        Timestamp from = Timestamp.from(windowFrom);
        Timestamp to = Timestamp.from(windowTo);
        List<String> issueIds = jdbc.queryForList(sql, String.class,
                tenantId, from, to, afterIssueId,
                tenantId, from, to, afterIssueId,
                tenantId, from, to, afterIssueId, limit);
        if (issueIds.isEmpty()) {
            return List.of();
        }
        Map<EntitlementRole, Map<String, List<Fact>>> facts = new EnumMap<>(EntitlementRole.class);
        for (EntitlementRole role : EntitlementRole.values()) {
            facts.put(role, loadFacts(tenantId, windowFrom, windowTo, role, issueIds));
        }
        return issueIds.stream().map(issueId -> loadCase(tenantId, issueId, facts)).toList();
    }

    /** 差异 ID 与指纹都由稳定业务键派生，使批次重试不会重复生成机器差异。 */
    @Override
    public void insertDiscrepancies(String runId, String scenarioCode, ReconCase reconCase,
                                    EntitlementDiscrepancy discrepancy, Instant occurredAt) {
        for (EntitlementDiscrepancyType type : discrepancy.types()) {
            if (type == EntitlementDiscrepancyType.CLEAN) {
                continue;
            }
            String fingerprint = sha256(discrepancy.tenantId() + '\0' + scenarioCode + '\0'
                    + discrepancy.issueId() + '\0' + type.name());
            String id = UUID.nameUUIDFromBytes((runId + '\0' + fingerprint).getBytes(StandardCharsets.UTF_8)).toString();
            jdbc.update("""
                    INSERT INTO discrepancy(discrepancy_id,run_id,segment_id,type,fingerprint,group_key,match_key,
                        expected_amount_minor,actual_amount_minor,delta_amount_minor,left_raw_ref,right_raw_ref,
                        machine_result,created_at,updated_at,measure_kind,expected_quantity,internal_quantity,
                        provider_quantity,expected_source_system,marketing_source_request_id,benefit_order_no)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,?,?,?,?,?,?)
                    """, id, runId, SEGMENT, type.name(), fingerprint, discrepancy.issueId(), discrepancy.issueId(),
                    0L, 0L, 0L, reconCase.leftRawRef(), reconCase.rightRawRef(), Timestamp.from(occurredAt),
                    Timestamp.from(occurredAt), "QUANTITY", discrepancy.expectedQuantity(),
                    discrepancy.internalQuantity(), discrepancy.providerQuantity(), reconCase.expectedSourceSystem(),
                    reconCase.marketingSourceRequestId(), reconCase.benefitOrderNo());
        }
    }

    private ReconCase loadCase(String tenantId, String issueId,
                               Map<EntitlementRole, Map<String, List<Fact>>> pageFacts) {
        List<Fact> expected = factsFor(pageFacts, EntitlementRole.EXPECTED, issueId);
        List<Fact> internal = factsFor(pageFacts, EntitlementRole.INTERNAL, issueId);
        List<Fact> provider = factsFor(pageFacts, EntitlementRole.PROVIDER, issueId);
        EntitlementMatchGroup group = new EntitlementMatchGroup(tenantId, issueId,
                observations(expected), observations(internal), observations(provider));
        String sourceRequestId = first(expected, Fact::marketingSourceRequestId);
        if (sourceRequestId == null) {
            sourceRequestId = first(internal, Fact::marketingSourceRequestId);
        }
        String benefitOrderNo = first(internal, Fact::benefitOrderNo);
        if (benefitOrderNo == null) {
            benefitOrderNo = first(provider, Fact::benefitOrderNo);
        }
        String leftRawRef = expected.isEmpty() ? null : expected.getFirst().observation().rawRef();
        String rightRawRef = !provider.isEmpty() ? provider.getFirst().observation().rawRef()
                : internal.isEmpty() ? null : internal.getFirst().observation().rawRef();
        return new ReconCase(group, first(expected, Fact::expectedSourceSystem), sourceRequestId,
                benefitOrderNo, leftRawRef, rightRawRef);
    }

    /** 每个角色每页只查一次，避免按 issueId 逐组访问数据库形成 N+1。 */
    private Map<String, List<Fact>> loadFacts(String tenantId, Instant windowFrom, Instant windowTo,
                                              EntitlementRole role, List<String> issueIds) {
        String table = switch (role) {
            case EXPECTED -> "recon_ods_entitlement_expected";
            case INTERNAL -> "recon_ods_entitlement_internal";
            case PROVIDER -> "recon_ods_entitlement_provider";
        };
        String placeholders = String.join(",", java.util.Collections.nCopies(issueIds.size(), "?"));
        List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(tenantId);
        parameters.add(Timestamp.from(windowFrom));
        parameters.add(Timestamp.from(windowTo));
        parameters.addAll(issueIds);
        List<Fact> page = jdbc.query("SELECT event_id,issue_id,sku_id,quantity,fulfillment_status,provider_ref,occurred_at,"
                        + "raw_ref,expected_source_system,marketing_source_request_id,benefit_order_no FROM " + table
                        + " WHERE tenant_id=? AND occurred_at>=? AND occurred_at<=? AND issue_id IN ("
                        + placeholders + ") ORDER BY issue_id,event_id",
                (rs, rowNum) -> fact(rs, tenantId, role), parameters.toArray());
        Map<String, List<Fact>> byIssue = new LinkedHashMap<>();
        for (Fact fact : page) {
            byIssue.computeIfAbsent(fact.observation().issueId(), ignored -> new java.util.ArrayList<>()).add(fact);
        }
        return byIssue;
    }

    private static List<Fact> factsFor(Map<EntitlementRole, Map<String, List<Fact>>> pageFacts,
                                       EntitlementRole role, String issueId) {
        return pageFacts.get(role).getOrDefault(issueId, List.of());
    }

    private static Fact fact(ResultSet rs, String tenantId, EntitlementRole role) throws SQLException {
        EntitlementObservation observation = new EntitlementObservation(tenantId, rs.getString("event_id"),
                rs.getString("issue_id"), rs.getString("sku_id"), rs.getLong("quantity"),
                status(rs.getString("fulfillment_status")), rs.getString("provider_ref"), role,
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("raw_ref"));
        return new Fact(observation, rs.getString("expected_source_system"),
                rs.getString("marketing_source_request_id"), rs.getString("benefit_order_no"));
    }

    private static EntitlementStatus status(String value) {
        try {
            return EntitlementStatus.valueOf(value);
        } catch (RuntimeException unknown) {
            return EntitlementStatus.UNKNOWN;
        }
    }

    private static List<EntitlementObservation> observations(List<Fact> facts) {
        return facts.stream().map(Fact::observation).toList();
    }

    private static String first(List<Fact> facts, java.util.function.Function<Fact, String> getter) {
        return facts.stream().map(getter).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Fact(EntitlementObservation observation, String expectedSourceSystem,
                        String marketingSourceRequestId, String benefitOrderNo) {
    }
}
