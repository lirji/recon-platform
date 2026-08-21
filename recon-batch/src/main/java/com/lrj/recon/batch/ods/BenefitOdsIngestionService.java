package com.lrj.recon.batch.ods;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.recon.batch.service.MessageInboxStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class BenefitOdsIngestionService {
    private static final String CONSUMER = "benefit-ods-v1";
    private final MessageInboxStore inbox;
    private final BenefitOdsStore ods;
    private final ObjectMapper json;

    public BenefitOdsIngestionService(MessageInboxStore inbox, BenefitOdsStore ods, ObjectMapper json) {
        this.inbox = inbox;
        this.ods = ods;
        this.json = json;
    }

    @Transactional
    public IngestionResult ingest(BenefitOdsEvent event) {
        String hash = hash(event);
        if (inbox.claim(event.tenantId(), CONSUMER, event.eventId(), hash)
                == MessageInboxStore.ClaimResult.REPLAY) {
            return new IngestionResult(event.eventId(), true);
        }
        ods.insert(event);
        inbox.markProcessed(event.tenantId(), CONSUMER, event.eventId());
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
