package com.lrj.recon.core.domain.model;

import java.time.Instant;

/**
 * 处置/处理动作审计条目 (设计 §5 discrepancy_action): 处理链与人工核销的<b>审计 + 外部幂等</b>。
 *
 * <p>按 {@code idempotencyKey} 幂等 ({@code uk_action}); 与冲正建议 / 告警 outbox 共用 {@code fingerprint+handlerId}
 * 幂等口径, 保重复触发不重复留痕。无金额字段 (金额存 payload 文本供运营)。
 */
public final class DiscrepancyAction {

    private final String id;
    private final String fingerprint;
    private final DiscrepancyActionType actionType;
    private final String idempotencyKey;
    private final String payload;      // nullable, 自由文本 / JSON 快照
    private final String operator;
    private final Instant createdAt;

    private DiscrepancyAction(Builder b) {
        this.id = b.id;
        this.fingerprint = b.fingerprint;
        this.actionType = b.actionType;
        this.idempotencyKey = b.idempotencyKey;
        this.payload = b.payload;
        this.operator = b.operator;
        this.createdAt = b.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() { return id; }
    public String fingerprint() { return fingerprint; }
    public DiscrepancyActionType actionType() { return actionType; }
    public String idempotencyKey() { return idempotencyKey; }
    public String payload() { return payload; }
    public String operator() { return operator; }
    public Instant createdAt() { return createdAt; }

    @Override
    public String toString() {
        return "DiscrepancyAction{" + actionType + ", " + idempotencyKey + ", by=" + operator + '}';
    }

    public static final class Builder {
        private String id;
        private String fingerprint;
        private DiscrepancyActionType actionType;
        private String idempotencyKey;
        private String payload;
        private String operator;
        private Instant createdAt;

        public Builder id(String v) { this.id = v; return this; }
        public Builder fingerprint(String v) { this.fingerprint = v; return this; }
        public Builder actionType(DiscrepancyActionType v) { this.actionType = v; return this; }
        public Builder idempotencyKey(String v) { this.idempotencyKey = v; return this; }
        public Builder payload(String v) { this.payload = v; return this; }
        public Builder operator(String v) { this.operator = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }

        public DiscrepancyAction build() {
            return new DiscrepancyAction(this);
        }
    }
}
