package com.lrj.recon.entitlement.scenario;

import com.lrj.recon.core.spi.SourceDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Three-side existence/quantity scenario, independent from Money and the monetary aggregator. */
public record EntitlementFulfillmentScenario(List<SideDefinition> sides) {

    public static final String SCENARIO_CODE = "ENTITLEMENT_FULFILLMENT";

    public EntitlementFulfillmentScenario {
        sides = List.copyOf(sides);
        if (sides.size() != 3) throw new IllegalArgumentException("expected exactly three entitlement sides");
    }

    public static EntitlementFulfillmentScenario defaults() {
        return new EntitlementFulfillmentScenario(List.of(
                side("EXPECTED", "recon_ods_entitlement_expected"),
                side("INTERNAL", "recon_ods_entitlement_internal"),
                side("PROVIDER", "recon_ods_entitlement_provider")));
    }

    private static SideDefinition side(String role, String table) {
        return new SideDefinition(role, new SourceDescriptor("db", Map.of(
                "table", table,
                "idColumn", "id",
                "tenantColumn", "tenant_id",
                "matchKeyColumn", "issue_id",
                "bizTimeColumn", "occurred_at")));
    }

    public record SideDefinition(String role, SourceDescriptor descriptor) {
        public SideDefinition {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
