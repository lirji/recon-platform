package com.lrj.recon.entitlement.model;

import java.util.List;
import java.util.Objects;

public record EntitlementMatchGroup(
        String tenantId,
        String issueId,
        List<EntitlementObservation> expected,
        List<EntitlementObservation> internal,
        List<EntitlementObservation> provider) {

    public EntitlementMatchGroup {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(issueId, "issueId");
        expected = List.copyOf(expected == null ? List.of() : expected);
        internal = List.copyOf(internal == null ? List.of() : internal);
        provider = List.copyOf(provider == null ? List.of() : provider);
        expected.forEach(v -> requireIdentity(v, tenantId, issueId, EntitlementRole.EXPECTED));
        internal.forEach(v -> requireIdentity(v, tenantId, issueId, EntitlementRole.INTERNAL));
        provider.forEach(v -> requireIdentity(v, tenantId, issueId, EntitlementRole.PROVIDER));
    }

    public long expectedQuantity() { return quantity(expected); }
    public long internalQuantity() { return quantity(internal); }
    public long providerQuantity() { return quantity(provider); }

    private static long quantity(List<EntitlementObservation> values) {
        return values.stream()
                .filter(v -> v.status() != EntitlementStatus.REVERSED && v.status() != EntitlementStatus.FAILED)
                .mapToLong(EntitlementObservation::quantity)
                .sum();
    }

    private static void requireIdentity(EntitlementObservation value, String tenantId, String issueId,
                                        EntitlementRole role) {
        Objects.requireNonNull(value, "observation");
        if (!tenantId.equals(value.tenantId()) || !issueId.equals(value.issueId()) || value.role() != role) {
            throw new IllegalArgumentException("observation identity/role does not belong to group");
        }
    }
}
