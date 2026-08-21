package com.lrj.recon.batch.service;

/** Kafka/回放消息的幂等收件箱端口。 */
public interface MessageInboxStore {
    ClaimResult claim(String tenantId, String consumerGroup, String eventId, String payloadHash);

    void markProcessed(String tenantId, String consumerGroup, String eventId);

    enum ClaimResult { CLAIMED, REPLAY }
}
