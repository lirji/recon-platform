package com.lrj.recon.core.domain.model;

import java.time.Instant;

/**
 * 告警发件箱条目 (设计 §5/ADR-10): 按 {@code idempotencyKey} 幂等 ({@code uk_outbox})。
 *
 * <p>批内只 {@code insertIfAbsent} (不直接发外部告警), 批后中继读 PENDING 投递并置 SENT/FAILED,
 * 使外部副作用与可重试的 chunk 事务解耦, chunk 重试不重复发。
 */
public final class AlertOutbox {

    private final String id;
    private final String runId;
    private final String fingerprint;
    private final String payload;
    private final AlertStatus status;
    private final int attempt;
    private final String idempotencyKey;
    private final Instant createdAt;
    private final Instant sentAt;   // nullable

    private AlertOutbox(Builder b) {
        this.id = b.id;
        this.runId = b.runId;
        this.fingerprint = b.fingerprint;
        this.payload = b.payload;
        this.status = b.status == null ? AlertStatus.PENDING : b.status;
        this.attempt = b.attempt;
        this.idempotencyKey = b.idempotencyKey;
        this.createdAt = b.createdAt;
        this.sentAt = b.sentAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() { return id; }
    public String runId() { return runId; }
    public String fingerprint() { return fingerprint; }
    public String payload() { return payload; }
    public AlertStatus status() { return status; }
    public int attempt() { return attempt; }
    public String idempotencyKey() { return idempotencyKey; }
    public Instant createdAt() { return createdAt; }
    public Instant sentAt() { return sentAt; }

    @Override
    public String toString() {
        return "AlertOutbox{" + idempotencyKey + ", " + status + ", attempt=" + attempt + '}';
    }

    public static final class Builder {
        private String id;
        private String runId;
        private String fingerprint;
        private String payload;
        private AlertStatus status;
        private int attempt;
        private String idempotencyKey;
        private Instant createdAt;
        private Instant sentAt;

        public Builder id(String v) { this.id = v; return this; }
        public Builder runId(String v) { this.runId = v; return this; }
        public Builder fingerprint(String v) { this.fingerprint = v; return this; }
        public Builder payload(String v) { this.payload = v; return this; }
        public Builder status(AlertStatus v) { this.status = v; return this; }
        public Builder attempt(int v) { this.attempt = v; return this; }
        public Builder idempotencyKey(String v) { this.idempotencyKey = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder sentAt(Instant v) { this.sentAt = v; return this; }

        public AlertOutbox build() {
            return new AlertOutbox(this);
        }
    }
}
