package com.lrj.recon.core.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 标准化对账记录 (不可变 VO, 经 {@link Builder} 构造)。
 *
 * <p>金额只经 {@link Money} (signed long 分); 汇率留位字段 {@code baseAmountMinor/fxRate/fxRateTime/fxRateSource}
 * 为阶段二保留, <b>MVP 只读存档、不参与任何比较</b> (ADR-3)。{@code fxRate} 用 {@link BigDecimal} 而非 double。
 */
public final class ReconRecord {

    private final String recordId;
    private final String runId;
    private final String segmentId;
    private final Side side;
    private final SourceRole sourceRole;

    private final MatchKey matchKey;   // 该侧无键时可为 null
    private final int bucket;
    private final GroupKey groupKey;

    private final Money money;         // signed 金额 (红蓝字已含符号)
    private final EntryType entryType;

    // ---- 阶段二留位: 只读存档, 不参与 MVP 判差 ----
    private final Long baseAmountMinor;
    private final BigDecimal fxRate;
    private final Instant fxRateTime;
    private final String fxRateSource;

    private final String bizStatus;    // STATUS_MISMATCH 用
    private final Instant bizTime;     // 营销应发时点
    private final Instant postingTime; // 账务记账时点 (TIMING 锚点)
    private final String claimedRunId; // TIMING 跨 Run 认领标记
    private final String rawRef;       // 血缘 file:line / table:pk

    private ReconRecord(Builder b) {
        this.recordId = b.recordId;
        this.runId = b.runId;
        this.segmentId = b.segmentId;
        this.side = Objects.requireNonNull(b.side, "side");
        this.sourceRole = b.sourceRole;
        this.matchKey = b.matchKey;
        this.bucket = b.bucket;
        this.groupKey = b.groupKey;
        this.money = Objects.requireNonNull(b.money, "money");
        this.entryType = b.entryType == null ? EntryType.ISSUE : b.entryType;
        this.baseAmountMinor = b.baseAmountMinor;
        this.fxRate = b.fxRate;
        this.fxRateTime = b.fxRateTime;
        this.fxRateSource = b.fxRateSource;
        this.bizStatus = b.bizStatus;
        this.bizTime = b.bizTime;
        this.postingTime = b.postingTime;
        this.claimedRunId = b.claimedRunId;
        this.rawRef = b.rawRef;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 复制为可变 builder (不可变风格): 用于 M2 loadStep 的标准化 processor 在原始记录上
     * 重算 match_key / group_key / bucket 后重建记录, 而不逐字段手抄。
     */
    public Builder toBuilder() {
        return new Builder()
                .recordId(recordId).runId(runId).segmentId(segmentId).side(side).sourceRole(sourceRole)
                .matchKey(matchKey).bucket(bucket).groupKey(groupKey).money(money).entryType(entryType)
                .baseAmountMinor(baseAmountMinor).fxRate(fxRate).fxRateTime(fxRateTime).fxRateSource(fxRateSource)
                .bizStatus(bizStatus).bizTime(bizTime).postingTime(postingTime)
                .claimedRunId(claimedRunId).rawRef(rawRef);
    }

    public String recordId() { return recordId; }
    public String runId() { return runId; }
    public String segmentId() { return segmentId; }
    public Side side() { return side; }
    public SourceRole sourceRole() { return sourceRole; }
    public MatchKey matchKey() { return matchKey; }
    public int bucket() { return bucket; }
    public GroupKey groupKey() { return groupKey; }
    public Money money() { return money; }
    public String currency() { return money.currency(); }
    /** 带符号最小货币单位 (分)。 */
    public long signedAmountMinor() { return money.amountMinor(); }
    public EntryType entryType() { return entryType; }
    public Long baseAmountMinor() { return baseAmountMinor; }
    public BigDecimal fxRate() { return fxRate; }
    public Instant fxRateTime() { return fxRateTime; }
    public String fxRateSource() { return fxRateSource; }
    public String bizStatus() { return bizStatus; }
    public Instant bizTime() { return bizTime; }
    public Instant postingTime() { return postingTime; }
    public String claimedRunId() { return claimedRunId; }
    public String rawRef() { return rawRef; }

    @Override
    public String toString() {
        return "ReconRecord{" + recordId + ", " + side + "/" + sourceRole
                + ", key=" + matchKey + ", " + money + ", " + entryType + '}';
    }

    /** 不可变 builder。 */
    public static final class Builder {
        private String recordId;
        private String runId;
        private String segmentId;
        private Side side;
        private SourceRole sourceRole;
        private MatchKey matchKey;
        private int bucket;
        private GroupKey groupKey;
        private Money money;
        private EntryType entryType;
        private Long baseAmountMinor;
        private BigDecimal fxRate;
        private Instant fxRateTime;
        private String fxRateSource;
        private String bizStatus;
        private Instant bizTime;
        private Instant postingTime;
        private String claimedRunId;
        private String rawRef;

        public Builder recordId(String v) { this.recordId = v; return this; }
        public Builder runId(String v) { this.runId = v; return this; }
        public Builder segmentId(String v) { this.segmentId = v; return this; }
        public Builder side(Side v) { this.side = v; return this; }
        public Builder sourceRole(SourceRole v) { this.sourceRole = v; return this; }
        public Builder matchKey(MatchKey v) { this.matchKey = v; return this; }
        public Builder bucket(int v) { this.bucket = v; return this; }
        public Builder groupKey(GroupKey v) { this.groupKey = v; return this; }
        public Builder money(Money v) { this.money = v; return this; }
        public Builder entryType(EntryType v) { this.entryType = v; return this; }
        public Builder baseAmountMinor(Long v) { this.baseAmountMinor = v; return this; }
        public Builder fxRate(BigDecimal v) { this.fxRate = v; return this; }
        public Builder fxRateTime(Instant v) { this.fxRateTime = v; return this; }
        public Builder fxRateSource(String v) { this.fxRateSource = v; return this; }
        public Builder bizStatus(String v) { this.bizStatus = v; return this; }
        public Builder bizTime(Instant v) { this.bizTime = v; return this; }
        public Builder postingTime(Instant v) { this.postingTime = v; return this; }
        public Builder claimedRunId(String v) { this.claimedRunId = v; return this; }
        public Builder rawRef(String v) { this.rawRef = v; return this; }

        public ReconRecord build() {
            return new ReconRecord(this);
        }
    }
}
