package com.lrj.recon.entitlement.model;

import java.util.List;
import java.util.Objects;

public record EntitlementDiscrepancy(
        String tenantId,
        String issueId,
        List<EntitlementDiscrepancyType> types,
        long expectedQuantity,
        long internalQuantity,
        long providerQuantity,
        String detail) {

    public EntitlementDiscrepancy {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(issueId, "issueId");
        types = List.copyOf(types);
        if (types.isEmpty()) {
            throw new IllegalArgumentException("at least one classification is required");
        }
    }

    public boolean clean() {
        return types.size() == 1 && types.getFirst() == EntitlementDiscrepancyType.CLEAN;
    }
}
