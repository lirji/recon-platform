package com.lrj.recon.rules.drools;

import com.lrj.recon.core.domain.model.DiscrepancyType;

/**
 * Drools 判差策略的可变 fact。
 *
 * <p>由 {@link DroolsDiscrepancyEvaluator} 从 {@code DiscrepancyClassifier} 的<b>候选</b>差异 +
 * {@code DiscrepancyRule} + {@code EvaluationContext} 投影而来, 插入 KieSession 供 DRL 规则改写:
 * <ul>
 *   <li>{@link #suppress()} —— 抹平该候选 (视为干净匹配, 不产差);</li>
 *   <li>{@link #overrideType(DiscrepancyType)} —— 改判主类型 (会导致 fingerprint 重算, 见 evaluator)。</li>
 * </ul>
 *
 * <p>只读投影字段(candidateType/absDeltaMinor/currency/...) 供规则做条件匹配; 安全关键的金额/指纹/桥归因构造
 * <b>不在此 fact 内</b>, 仍由 recon-core 的 {@code DiscrepancyClassifier} 产出, DRL 只做策略后处理。
 * {@code candidateType == null} 表示干净匹配 —— evaluator 在此情形不插入本 fact (与 Exact/Tolerance 早返回一致)。
 */
public final class DiscrepancyDecision {

    // ---- 只读投影(条件) ----
    private final DiscrepancyType candidateType;
    private final long expectedAmountMinor;
    private final long actualAmountMinor;
    private final long deltaAmountMinor;
    private final long absDeltaMinor;
    private final String currency;
    private final boolean multiLine;
    private final boolean typeEnabled;
    private final boolean withinAmountTolerance;
    private final long absToleranceMinor;
    private final long ratioToleranceBps;
    private final String scenarioCode;
    private final String accountingPeriod;
    private final String segmentId;

    // ---- 可变输出(规则改写) ----
    private boolean suppressed;
    private DiscrepancyType overrideType;

    private DiscrepancyDecision(Builder b) {
        this.candidateType = b.candidateType;
        this.expectedAmountMinor = b.expectedAmountMinor;
        this.actualAmountMinor = b.actualAmountMinor;
        this.deltaAmountMinor = b.deltaAmountMinor;
        this.absDeltaMinor = b.absDeltaMinor;
        this.currency = b.currency;
        this.multiLine = b.multiLine;
        this.typeEnabled = b.typeEnabled;
        this.withinAmountTolerance = b.withinAmountTolerance;
        this.absToleranceMinor = b.absToleranceMinor;
        this.ratioToleranceBps = b.ratioToleranceBps;
        this.scenarioCode = b.scenarioCode;
        this.accountingPeriod = b.accountingPeriod;
        this.segmentId = b.segmentId;
    }

    public static Builder builder() {
        return new Builder();
    }

    // 只读 getter(DRL 条件)。
    public DiscrepancyType getCandidateType() { return candidateType; }
    public long getExpectedAmountMinor() { return expectedAmountMinor; }
    public long getActualAmountMinor() { return actualAmountMinor; }
    public long getDeltaAmountMinor() { return deltaAmountMinor; }
    public long getAbsDeltaMinor() { return absDeltaMinor; }
    public String getCurrency() { return currency; }
    public boolean isMultiLine() { return multiLine; }
    public boolean isTypeEnabled() { return typeEnabled; }
    public boolean isWithinAmountTolerance() { return withinAmountTolerance; }
    public long getAbsToleranceMinor() { return absToleranceMinor; }
    public long getRatioToleranceBps() { return ratioToleranceBps; }
    public String getScenarioCode() { return scenarioCode; }
    public String getAccountingPeriod() { return accountingPeriod; }
    public String getSegmentId() { return segmentId; }

    // 可变输出。
    public boolean isSuppressed() { return suppressed; }
    public DiscrepancyType getOverrideType() { return overrideType; }

    /** 规则动作: 抹平候选(视为干净匹配)。 */
    public void suppress() { this.suppressed = true; }

    /** 规则动作: 改判主类型(fingerprint 会由 evaluator 用新 type 重算)。 */
    public void overrideType(DiscrepancyType type) { this.overrideType = type; }

    /** 最终主类型 = 改判优先, 否则候选。 */
    public DiscrepancyType resolvedType() {
        return overrideType != null ? overrideType : candidateType;
    }

    public static final class Builder {
        private DiscrepancyType candidateType;
        private long expectedAmountMinor;
        private long actualAmountMinor;
        private long deltaAmountMinor;
        private long absDeltaMinor;
        private String currency;
        private boolean multiLine;
        private boolean typeEnabled;
        private boolean withinAmountTolerance;
        private long absToleranceMinor;
        private long ratioToleranceBps;
        private String scenarioCode;
        private String accountingPeriod;
        private String segmentId;

        public Builder candidateType(DiscrepancyType v) { this.candidateType = v; return this; }
        public Builder expectedAmountMinor(long v) { this.expectedAmountMinor = v; return this; }
        public Builder actualAmountMinor(long v) { this.actualAmountMinor = v; return this; }
        public Builder deltaAmountMinor(long v) { this.deltaAmountMinor = v; return this; }
        public Builder absDeltaMinor(long v) { this.absDeltaMinor = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder multiLine(boolean v) { this.multiLine = v; return this; }
        public Builder typeEnabled(boolean v) { this.typeEnabled = v; return this; }
        public Builder withinAmountTolerance(boolean v) { this.withinAmountTolerance = v; return this; }
        public Builder absToleranceMinor(long v) { this.absToleranceMinor = v; return this; }
        public Builder ratioToleranceBps(int v) { this.ratioToleranceBps = v; return this; }
        public Builder scenarioCode(String v) { this.scenarioCode = v; return this; }
        public Builder accountingPeriod(String v) { this.accountingPeriod = v; return this; }
        public Builder segmentId(String v) { this.segmentId = v; return this; }

        public DiscrepancyDecision build() {
            return new DiscrepancyDecision(this);
        }
    }
}
