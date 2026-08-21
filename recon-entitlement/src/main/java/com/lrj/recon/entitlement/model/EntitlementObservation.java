package com.lrj.recon.entitlement.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A quantity/existence fact. It intentionally contains no currency or amount so a coupon,
 * redemption code or physical item can never be disguised as synthetic money.
 */
public record EntitlementObservation(
        String tenantId,
        String eventId,
        String issueId,
        String skuId,
        long quantity,
        EntitlementStatus status,
        String providerRef,
        EntitlementRole role,
        Instant occurredAt,
        String rawRef) {

    public EntitlementObservation {
        requireText("tenantId", tenantId);
        requireText("eventId", eventId);
        requireText("issueId", issueId);
        requireText("skuId", skuId);
        requireText("rawRef", rawRef);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
