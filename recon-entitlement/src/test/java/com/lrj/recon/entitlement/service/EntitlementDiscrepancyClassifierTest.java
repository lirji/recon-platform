package com.lrj.recon.entitlement.service;

import com.lrj.recon.entitlement.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntitlementDiscrepancyClassifierTest {
    private final EntitlementDiscrepancyClassifier classifier = new EntitlementDiscrepancyClassifier();

    @Test
    void classifiesCleanWithoutInventingMoney() {
        EntitlementMatchGroup group = group(
                List.of(obs("e", EntitlementRole.EXPECTED, EntitlementStatus.EXPECTED, "P1", 1)),
                List.of(obs("i", EntitlementRole.INTERNAL, EntitlementStatus.ISSUED, "P1", 1)),
                List.of(obs("p", EntitlementRole.PROVIDER, EntitlementStatus.ISSUED, "P1", 1)));

        assertThat(classifier.classify(group).types()).containsExactly(EntitlementDiscrepancyType.CLEAN);
    }

    @Test
    void classifiesMissingAndQuantityMismatch() {
        EntitlementMatchGroup group = group(
                List.of(obs("e", EntitlementRole.EXPECTED, EntitlementStatus.EXPECTED, null, 1)),
                List.of(), List.of());

        assertThat(classifier.classify(group).types()).contains(
                EntitlementDiscrepancyType.MISSING_INTERNAL,
                EntitlementDiscrepancyType.MISSING_PROVIDER,
                EntitlementDiscrepancyType.QUANTITY_MISMATCH);
    }

    @Test
    void unknownIsExplicitAndNeverClean() {
        EntitlementMatchGroup group = group(
                List.of(obs("e", EntitlementRole.EXPECTED, EntitlementStatus.EXPECTED, "P1", 1)),
                List.of(obs("i", EntitlementRole.INTERNAL, EntitlementStatus.UNKNOWN, "P1", 1)),
                List.of(obs("p", EntitlementRole.PROVIDER, EntitlementStatus.UNKNOWN, "P1", 1)));

        assertThat(classifier.classify(group).types()).contains(EntitlementDiscrepancyType.UNKNOWN);
        assertThat(classifier.classify(group).clean()).isFalse();
    }

    @Test
    void detectsDuplicateSkuAndReferenceMismatch() {
        EntitlementMatchGroup group = group(
                List.of(obs("e", EntitlementRole.EXPECTED, EntitlementStatus.EXPECTED, "P1", 1)),
                List.of(obs("same", EntitlementRole.INTERNAL, EntitlementStatus.ISSUED, "P1", 1),
                        obs("same", EntitlementRole.INTERNAL, EntitlementStatus.ISSUED, "P1", 1)),
                List.of(new EntitlementObservation("T1", "p", "I1", "SKU-OTHER", 1,
                        EntitlementStatus.ISSUED, "P2", EntitlementRole.PROVIDER, Instant.EPOCH, "raw")));

        assertThat(classifier.classify(group).types()).contains(
                EntitlementDiscrepancyType.DUPLICATE_INTERNAL,
                EntitlementDiscrepancyType.SKU_MISMATCH,
                EntitlementDiscrepancyType.PROVIDER_REFERENCE_MISMATCH,
                EntitlementDiscrepancyType.QUANTITY_MISMATCH);
    }

    private static EntitlementMatchGroup group(List<EntitlementObservation> expected,
                                               List<EntitlementObservation> internal,
                                               List<EntitlementObservation> provider) {
        return new EntitlementMatchGroup("T1", "I1", expected, internal, provider);
    }

    private static EntitlementObservation obs(String eventId, EntitlementRole role,
                                              EntitlementStatus status, String ref, long quantity) {
        return new EntitlementObservation("T1", eventId, "I1", "SKU1", quantity,
                status, ref, role, Instant.EPOCH, "raw:" + eventId);
    }
}
