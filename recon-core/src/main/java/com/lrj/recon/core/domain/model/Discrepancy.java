package com.lrj.recon.core.domain.model;

/**
 * 机器判差结果 (不含人工状态; 人工处置在独立聚合)。
 *
 * <p>身份锚点是 {@link #fingerprint} (ADR-9: null-safe canonical 的 SHA-256), 对 BRIDGE_BROKEN /
 * CURRENCY_MISMATCH 等空键类型仍幂等。金额一律 signed long 分。
 */
public final class Discrepancy {

    private final String discrepancyId;
    private final String runId;
    private final String segmentId;
    private final DiscrepancyType type;
    private final String bridgeBreakStage;   // SEG1 | SEG2 (仅 BRIDGE_BROKEN)
    private final String groupKey;
    private final String matchKey;
    private final String currency;           // CURRENCY_MISMATCH 时为 null (横跨两币种)

    private final long expectedAmountMinor;  // 左口径应对 (权威侧)
    private final long actualAmountMinor;    // 右口径实际
    private final long deltaAmountMinor;     // expected - actual (signed)

    private final String leftRawRef;
    private final String rightRawRef;
    private final String fingerprint;

    private Discrepancy(Builder b) {
        this.discrepancyId = b.discrepancyId;
        this.runId = b.runId;
        this.segmentId = b.segmentId;
        this.type = b.type;
        this.bridgeBreakStage = b.bridgeBreakStage;
        this.groupKey = b.groupKey;
        this.matchKey = b.matchKey;
        this.currency = b.currency;
        this.expectedAmountMinor = b.expectedAmountMinor;
        this.actualAmountMinor = b.actualAmountMinor;
        this.deltaAmountMinor = b.deltaAmountMinor;
        this.leftRawRef = b.leftRawRef;
        this.rightRawRef = b.rightRawRef;
        this.fingerprint = b.fingerprint;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String discrepancyId() { return discrepancyId; }
    public String runId() { return runId; }
    public String segmentId() { return segmentId; }
    public DiscrepancyType type() { return type; }
    public String bridgeBreakStage() { return bridgeBreakStage; }
    public String groupKey() { return groupKey; }
    public String matchKey() { return matchKey; }
    public String currency() { return currency; }
    public long expectedAmountMinor() { return expectedAmountMinor; }
    public long actualAmountMinor() { return actualAmountMinor; }
    public long deltaAmountMinor() { return deltaAmountMinor; }
    /** 运营展示用绝对差额 (设计修补④: 上报 delta = |左 - 右|)。 */
    public long absDeltaMinor() { return Math.abs(deltaAmountMinor); }
    public String leftRawRef() { return leftRawRef; }
    public String rightRawRef() { return rightRawRef; }
    public String fingerprint() { return fingerprint; }

    @Override
    public String toString() {
        return "Discrepancy{" + type + (bridgeBreakStage == null ? "" : "/" + bridgeBreakStage)
                + ", key=" + matchKey + ", exp=" + expectedAmountMinor + ", act=" + actualAmountMinor
                + ", ccy=" + currency + ", fp=" + fingerprint + '}';
    }

    public static final class Builder {
        private String discrepancyId;
        private String runId;
        private String segmentId;
        private DiscrepancyType type;
        private String bridgeBreakStage;
        private String groupKey;
        private String matchKey;
        private String currency;
        private long expectedAmountMinor;
        private long actualAmountMinor;
        private long deltaAmountMinor;
        private String leftRawRef;
        private String rightRawRef;
        private String fingerprint;

        public Builder discrepancyId(String v) { this.discrepancyId = v; return this; }
        public Builder runId(String v) { this.runId = v; return this; }
        public Builder segmentId(String v) { this.segmentId = v; return this; }
        public Builder type(DiscrepancyType v) { this.type = v; return this; }
        public Builder bridgeBreakStage(String v) { this.bridgeBreakStage = v; return this; }
        public Builder groupKey(String v) { this.groupKey = v; return this; }
        public Builder matchKey(String v) { this.matchKey = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder expectedAmountMinor(long v) { this.expectedAmountMinor = v; return this; }
        public Builder actualAmountMinor(long v) { this.actualAmountMinor = v; return this; }
        public Builder deltaAmountMinor(long v) { this.deltaAmountMinor = v; return this; }
        public Builder leftRawRef(String v) { this.leftRawRef = v; return this; }
        public Builder rightRawRef(String v) { this.rightRawRef = v; return this; }
        public Builder fingerprint(String v) { this.fingerprint = v; return this; }

        public Discrepancy build() {
            return new Discrepancy(this);
        }
    }
}
