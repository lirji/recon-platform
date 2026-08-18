package com.lrj.recon.core.domain.model;

import java.time.Instant;

/**
 * 冲正建议 (设计 §3/§5): 按 {@code idempotencyKey} 幂等 ({@code uk_rev}), 重复触发不重复生成。
 *
 * <p><b>无资金动作</b>, 只是运营台账里的一条建议。金额为 signed long 分 (禁 double)。
 */
public final class ReversalSuggestion {

    private final String id;
    private final String fingerprint;
    private final String runId;
    private final String groupKey;
    private final long suggestedAmountMinor;   // signed 最小货币单位 (分)
    private final String currency;
    private final ReversalStatus status;
    private final String idempotencyKey;
    private final String operator;
    private final Instant createdAt;

    private ReversalSuggestion(Builder b) {
        this.id = b.id;
        this.fingerprint = b.fingerprint;
        this.runId = b.runId;
        this.groupKey = b.groupKey;
        this.suggestedAmountMinor = b.suggestedAmountMinor;
        this.currency = b.currency;
        this.status = b.status == null ? ReversalStatus.SUGGESTED : b.status;
        this.idempotencyKey = b.idempotencyKey;
        this.operator = b.operator;
        this.createdAt = b.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() { return id; }
    public String fingerprint() { return fingerprint; }
    public String runId() { return runId; }
    public String groupKey() { return groupKey; }
    public long suggestedAmountMinor() { return suggestedAmountMinor; }
    public String currency() { return currency; }
    public ReversalStatus status() { return status; }
    public String idempotencyKey() { return idempotencyKey; }
    public String operator() { return operator; }
    public Instant createdAt() { return createdAt; }

    @Override
    public String toString() {
        return "ReversalSuggestion{" + idempotencyKey + ", " + suggestedAmountMinor + " " + currency
                + ", " + status + '}';
    }

    public static final class Builder {
        private String id;
        private String fingerprint;
        private String runId;
        private String groupKey;
        private long suggestedAmountMinor;
        private String currency;
        private ReversalStatus status;
        private String idempotencyKey;
        private String operator;
        private Instant createdAt;

        public Builder id(String v) { this.id = v; return this; }
        public Builder fingerprint(String v) { this.fingerprint = v; return this; }
        public Builder runId(String v) { this.runId = v; return this; }
        public Builder groupKey(String v) { this.groupKey = v; return this; }
        public Builder suggestedAmountMinor(long v) { this.suggestedAmountMinor = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder status(ReversalStatus v) { this.status = v; return this; }
        public Builder idempotencyKey(String v) { this.idempotencyKey = v; return this; }
        public Builder operator(String v) { this.operator = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }

        public ReversalSuggestion build() {
            return new ReversalSuggestion(this);
        }
    }
}
