package com.lrj.recon.batch.ods;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.recon.batch.service.MessageInboxStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;

/** 双 topic ODS 入库事务：先抢 inbox，再写事实并标记完成，保证每个消费者命名空间内幂等。 */
@Service
public class BenefitOdsIngestionService {
    private static final String FULFILLMENT_CONSUMER = "benefit-fulfillment-ods-v1";
    private static final String MARKETING_EXPECTED_CONSUMER = "marketing-award-expected-ods-v1";
    private final MessageInboxStore inbox;
    private final BenefitOdsStore ods;
    private final ObjectMapper json;

    public BenefitOdsIngestionService(MessageInboxStore inbox, BenefitOdsStore ods, ObjectMapper json) {
        this.inbox = inbox;
        this.ods = ods;
        this.json = json;
    }

    /** 兼容旧调用，默认按履约事件命名空间处理。 */
    @Transactional
    public IngestionResult ingest(BenefitOdsEvent event) {
        return ingest(event, FULFILLMENT_CONSUMER);
    }

    /** 权益履约事实与营销应发事实使用独立 inbox 命名空间，避免跨 topic eventId 碰撞。 */
    @Transactional
    public IngestionResult ingestFulfillment(BenefitOdsEvent event) {
        return ingest(event, FULFILLMENT_CONSUMER);
    }

    /** 营销应发消费入口。 */
    @Transactional
    public IngestionResult ingestMarketingExpected(BenefitOdsEvent event) {
        return ingest(event, MARKETING_EXPECTED_CONSUMER);
    }

    private IngestionResult ingest(BenefitOdsEvent event, String consumer) {
        String hash = hash(event);
        if (inbox.claim(event.tenantId(), consumer, event.eventId(), hash)
                == MessageInboxStore.ClaimResult.REPLAY) {
            return new IngestionResult(event.eventId(), true);
        }
        ods.insert(event);
        inbox.markProcessed(event.tenantId(), consumer, event.eventId());
        return new IngestionResult(event.eventId(), false);
    }

    private String hash(BenefitOdsEvent event) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(event)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public record IngestionResult(String eventId, boolean replay) {}
}
