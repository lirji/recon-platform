package com.lrj.recon.core.domain.model;

import java.time.Instant;

/**
 * 人工处置 (设计 §3/§5, 独立于 Run 生命周期): 按 {@code fingerprint} 唯一 (一差一处置)。
 *
 * <p>{@code version} 乐观锁保证并发核销安全 (冲突返回 409 / 抛 {@link ConflictException});
 * 本表<b>永不被重跑删除</b> (ADR-7)。{@code lastSeenRunId} 记录最近一次机器重算仍见该差异的 Run,
 * 供重跑收敛 (STALE 自动关闭) 逻辑 (M5/M6) 使用。无金额字段。
 */
public final class DiscrepancyDisposition {

    private final String id;
    private final String fingerprint;
    private final String scenarioCode;
    private final String accountingPeriod;
    private final String segmentId;
    private final DispositionStatus status;
    private final String operator;
    private final String note;
    private final String lastSeenRunId;
    private final int version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private DiscrepancyDisposition(Builder b) {
        this.id = b.id;
        this.fingerprint = b.fingerprint;
        this.scenarioCode = b.scenarioCode;
        this.accountingPeriod = b.accountingPeriod;
        this.segmentId = b.segmentId;
        this.status = b.status;
        this.operator = b.operator;
        this.note = b.note;
        this.lastSeenRunId = b.lastSeenRunId;
        this.version = b.version;
        this.createdAt = b.createdAt;
        this.updatedAt = b.updatedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() { return id; }
    public String fingerprint() { return fingerprint; }
    public String scenarioCode() { return scenarioCode; }
    public String accountingPeriod() { return accountingPeriod; }
    public String segmentId() { return segmentId; }
    public DispositionStatus status() { return status; }
    public String operator() { return operator; }
    public String note() { return note; }
    public String lastSeenRunId() { return lastSeenRunId; }
    public int version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "DiscrepancyDisposition{" + fingerprint + ", " + status + ", v=" + version + '}';
    }

    public static final class Builder {
        private String id;
        private String fingerprint;
        private String scenarioCode;
        private String accountingPeriod;
        private String segmentId;
        private DispositionStatus status;
        private String operator;
        private String note;
        private String lastSeenRunId;
        private int version;
        private Instant createdAt;
        private Instant updatedAt;

        public Builder id(String v) { this.id = v; return this; }
        public Builder fingerprint(String v) { this.fingerprint = v; return this; }
        public Builder scenarioCode(String v) { this.scenarioCode = v; return this; }
        public Builder accountingPeriod(String v) { this.accountingPeriod = v; return this; }
        public Builder segmentId(String v) { this.segmentId = v; return this; }
        public Builder status(DispositionStatus v) { this.status = v; return this; }
        public Builder operator(String v) { this.operator = v; return this; }
        public Builder note(String v) { this.note = v; return this; }
        public Builder lastSeenRunId(String v) { this.lastSeenRunId = v; return this; }
        public Builder version(int v) { this.version = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder updatedAt(Instant v) { this.updatedAt = v; return this; }

        public DiscrepancyDisposition build() {
            return new DiscrepancyDisposition(this);
        }
    }
}
