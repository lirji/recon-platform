package com.lrj.recon.entitlement.service;

import com.lrj.recon.entitlement.model.EntitlementDiscrepancy;
import com.lrj.recon.entitlement.model.EntitlementDiscrepancyType;
import com.lrj.recon.entitlement.model.EntitlementMatchGroup;
import com.lrj.recon.entitlement.model.EntitlementObservation;
import com.lrj.recon.entitlement.model.EntitlementStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic, multi-label classifier; UNKNOWN remains an explicit manual-review gate. */
public final class EntitlementDiscrepancyClassifier {

    public EntitlementDiscrepancy classify(EntitlementMatchGroup group) {
        List<EntitlementDiscrepancyType> types = new ArrayList<>();
        long expected = group.expectedQuantity();
        long internal = group.internalQuantity();
        long provider = group.providerQuantity();

        if (group.internal().isEmpty() && expected > 0) types.add(EntitlementDiscrepancyType.MISSING_INTERNAL);
        if (group.provider().isEmpty() && expected > 0) types.add(EntitlementDiscrepancyType.MISSING_PROVIDER);
        if (expected == 0 && internal > 0) types.add(EntitlementDiscrepancyType.EXTRA_INTERNAL);
        if (expected == 0 && provider > 0) types.add(EntitlementDiscrepancyType.EXTRA_PROVIDER);
        if (hasDuplicateEvent(group.internal())) types.add(EntitlementDiscrepancyType.DUPLICATE_INTERNAL);
        if (hasDuplicateEvent(group.provider())) types.add(EntitlementDiscrepancyType.DUPLICATE_PROVIDER);
        if (!(expected == internal && internal == provider)) types.add(EntitlementDiscrepancyType.QUANTITY_MISMATCH);
        if (differentSku(group)) types.add(EntitlementDiscrepancyType.SKU_MISMATCH);
        if (differentStatus(group)) types.add(EntitlementDiscrepancyType.STATUS_MISMATCH);
        if (differentProviderReference(group)) types.add(EntitlementDiscrepancyType.PROVIDER_REFERENCE_MISMATCH);
        if (containsUnknown(group)) types.add(EntitlementDiscrepancyType.UNKNOWN);
        if (types.isEmpty()) types.add(EntitlementDiscrepancyType.CLEAN);

        return new EntitlementDiscrepancy(group.tenantId(), group.issueId(), types,
                expected, internal, provider,
                "expected=" + expected + ",internal=" + internal + ",provider=" + provider);
    }

    private static boolean hasDuplicateEvent(List<EntitlementObservation> values) {
        Set<String> ids = new HashSet<>();
        return values.stream().anyMatch(v -> !ids.add(v.eventId()));
    }

    private static boolean differentSku(EntitlementMatchGroup group) {
        return all(group).stream().map(EntitlementObservation::skuId).distinct().count() > 1;
    }

    private static boolean differentStatus(EntitlementMatchGroup group) {
        List<EntitlementStatus> actual = allActual(group).stream().map(EntitlementObservation::status)
                .filter(status -> status != EntitlementStatus.UNKNOWN).distinct().toList();
        return actual.size() > 1;
    }

    private static boolean differentProviderReference(EntitlementMatchGroup group) {
        Set<String> internalRefs = refs(group.internal());
        Set<String> providerRefs = refs(group.provider());
        return !internalRefs.isEmpty() && !providerRefs.isEmpty() && !internalRefs.equals(providerRefs);
    }

    private static boolean containsUnknown(EntitlementMatchGroup group) {
        return allActual(group).stream().anyMatch(v -> v.status() == EntitlementStatus.UNKNOWN);
    }

    private static Set<String> refs(List<EntitlementObservation> values) {
        Set<String> refs = new HashSet<>();
        values.stream().map(EntitlementObservation::providerRef)
                .filter(v -> v != null && !v.isBlank()).forEach(refs::add);
        return refs;
    }

    private static List<EntitlementObservation> all(EntitlementMatchGroup group) {
        List<EntitlementObservation> all = new ArrayList<>(group.expected());
        all.addAll(group.internal());
        all.addAll(group.provider());
        return all;
    }

    private static List<EntitlementObservation> allActual(EntitlementMatchGroup group) {
        List<EntitlementObservation> all = new ArrayList<>(group.internal());
        all.addAll(group.provider());
        return all;
    }
}
