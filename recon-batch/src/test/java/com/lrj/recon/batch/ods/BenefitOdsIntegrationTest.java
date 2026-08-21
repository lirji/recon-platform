package com.lrj.recon.batch.ods;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BenefitOdsIntegrationTest {
    @Autowired BenefitOdsIngestionService ingestion;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ods_message_inbox WHERE tenant_id='benefit-test'");
        jdbc.update("DELETE FROM recon_ods_entitlement_expected WHERE tenant_id='benefit-test'");
        jdbc.update("DELETE FROM recon_ods_entitlement_internal WHERE tenant_id='benefit-test'");
        jdbc.update("DELETE FROM recon_ods_entitlement_provider WHERE tenant_id='benefit-test'");
    }

    @Test
    void entitlementIngressIsIdempotentTenantScopedAndNeverInventsMoney() throws Exception {
        BenefitOdsConsumer consumer = new BenefitOdsConsumer(ingestion, json);
        String raw = """
                {"eventId":"EV-1","eventType":"FULFILLMENT_EXPECTED","schemaVersion":"1.0",
                 "tenantId":"benefit-test","occurredAt":"2026-08-21T00:00:00Z","payload":{
                   "factType":"EXPECTED","benefitType":"COUPON","awardItemNo":"ITEM-1",
                   "awardOrderNo":"ORDER-1","skuId":"COUPON-1","quantity":1,"status":"PENDING"}}
                """;

        consumer.consume(raw);
        consumer.consume(raw);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM recon_ods_entitlement_expected
                WHERE tenant_id='benefit-test' AND event_id='EV-1'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT fulfillment_status FROM recon_ods_entitlement_expected
                WHERE tenant_id='benefit-test' AND event_id='EV-1'
                """, String.class)).isEqualTo("EXPECTED");
    }

    @Test
    void sameEventIdWithDifferentPayloadIsRejected() {
        var first = entitlement("EV-CONFLICT", 1L);
        assertThat(ingestion.ingest(first).replay()).isFalse();

        assertThatThrownBy(() -> ingestion.ingest(entitlement("EV-CONFLICT", 2L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload conflict");
    }

    @Test
    void nonCashFactRejectsSyntheticCurrencyAndAmount() {
        assertThatThrownBy(() -> new BenefitOdsEvent("benefit-test", "EV-BAD",
                BenefitOdsEvent.FactType.ENTITLEMENT_INTERNAL, "ITEM-1", "ORDER-1", null,
                "XXX", 0L, "ISSUE", "COUPON-1", 1L, "ISSUED", null,
                Instant.EPOCH, null, null, null, null, "raw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("synthetic");
    }

    private static BenefitOdsEvent entitlement(String eventId, long quantity) {
        return new BenefitOdsEvent("benefit-test", eventId,
                BenefitOdsEvent.FactType.ENTITLEMENT_INTERNAL, "ITEM-1", "ORDER-1", null,
                null, null, "ISSUE", "COUPON-1", quantity, "ISSUED", "PROVIDER-1",
                Instant.EPOCH, null, "ITEM-1", null, null, "raw:" + eventId + ':' + quantity);
    }
}
