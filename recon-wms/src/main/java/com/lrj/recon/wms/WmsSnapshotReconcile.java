package com.lrj.recon.wms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消费双方数量快照并维护差异状态。修复后再核对；不写 WMS 库存表。
 */
public final class WmsSnapshotReconcile {
    public static final int WMS_SCHEMA = 1;
    public static final int RECON_SCHEMA = 1;

    public record Case(String id, String matchKey, String code, String state, int wmsSchema, int reconSchema) {
    }

    private final Map<String, Case> cases = new LinkedHashMap<>();
    private List<WmsQuantityFact> lastLeft = List.of();
    private List<WmsQuantityFact> lastRight = List.of();

    public List<Case> consume(String leftJsonl, boolean leftComplete, String rightJsonl, boolean rightComplete) {
        lastLeft = WmsSnapshotParser.parseJsonl(leftJsonl, leftComplete);
        lastRight = WmsSnapshotParser.parseJsonl(rightJsonl, rightComplete);
        return rematch();
    }

    public Case approve(String caseId) {
        Case existing = cases.get(caseId);
        if (existing == null) {
            throw new IllegalArgumentException("case not found");
        }
        Case remediating = new Case(existing.id(), existing.matchKey(), existing.code(), "REMEDIATING",
                existing.wmsSchema(), existing.reconSchema());
        cases.put(caseId, remediating);
        return remediating;
    }

    public List<Case> rematch() {
        List<WmsQuantityClassifier.Finding> findings = new WmsQuantityClassifier().classify(lastLeft, lastRight);
        Map<String, WmsQuantityClassifier.Finding> open = new LinkedHashMap<>();
        for (WmsQuantityClassifier.Finding finding : findings) {
            open.put(finding.matchKey() + "/" + finding.code(), finding);
        }
        for (Case existing : List.copyOf(cases.values())) {
            String key = existing.matchKey() + "/" + existing.code();
            if (!open.containsKey(key) && "REMEDIATING".equals(existing.state())) {
                cases.put(existing.id(), new Case(existing.id(), existing.matchKey(), existing.code(), "CLOSED",
                        existing.wmsSchema(), existing.reconSchema()));
            }
        }
        for (WmsQuantityClassifier.Finding finding : findings) {
            String key = finding.matchKey() + "/" + finding.code();
            boolean present = cases.values().stream().anyMatch(item -> (item.matchKey() + "/" + item.code()).equals(key)
                    && !"CLOSED".equals(item.state()));
            if (!present) {
                Case created = new Case(UUID.randomUUID().toString(), finding.matchKey(), finding.code(), "OPEN",
                        WMS_SCHEMA, RECON_SCHEMA);
                cases.put(created.id(), created);
            }
        }
        return List.copyOf(cases.values());
    }

    public List<Case> cases() {
        return new ArrayList<>(cases.values());
    }

    public Map<String, Integer> contract() {
        return Map.of("wmsSchemaVersion", WMS_SCHEMA, "reconSchemaVersion", RECON_SCHEMA);
    }
}
