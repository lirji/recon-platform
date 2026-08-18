package com.lrj.recon.core.domain.model;

/**
 * 勾稽报表: 按 (segmentId, currency) 桶, 左右双口径守恒 (设计 §8)。
 *
 * <p>每桶只在<b>本币种</b>内闭合 (跨币种不相加)。
 * <ul>
 *   <li>左口径 (完整性): {@code expectedTotal = matched + Σ 左侧各类差异贡献}, {@code leftResidual} 应=0;</li>
 *   <li>右口径 (完整性): {@code rightSideTotal = matchedRight + extra + 右侧 bridge + 右侧 currency}, {@code rightResidual} 应=0。</li>
 * </ul>
 * 任一 residual ≠ 0 → {@code balanced=false} → Run 应置 REPORT_IMBALANCE。守恒为构造性恒等式,
 * 该信号只标示<b>桶路由/溢出回归</b>, <b>不</b>覆盖分类判定正确性 (见 ConservationChecker 说明)。
 *
 * <p>说明: {@code matchedAmountMinor} 展示的是<b>干净匹配</b>(左口径, L==R) 的量; 右口径把
 * "按键配上但金额/状态/时点有差"的两侧配对额并入右侧 matched 参与右口径闭合 (见 ConservationChecker)。
 */
public final class ReconReport {

    private final String runId;
    private final String segmentId;
    private final String currency;

    private final long expectedTotalMinor;
    private final long matchedAmountMinor;

    private final long amountMismatchMinor;
    private final long missingMinor;
    private final long duplicateMinor;
    private final long extraMinor;
    private final long timingMinor;
    private final long statusMismatchMinor;
    private final long currencyMismatchMinor;
    private final long groupSumMismatchMinor;
    private final long bridgeBrokenMinor;

    private final long rightSideTotalMinor;
    private final long leftResidualMinor;
    private final long rightResidualMinor;
    private final boolean balanced;

    private ReconReport(Builder b) {
        this.runId = b.runId;
        this.segmentId = b.segmentId;
        this.currency = b.currency;
        this.expectedTotalMinor = b.expectedTotalMinor;
        this.matchedAmountMinor = b.matchedAmountMinor;
        this.amountMismatchMinor = b.amountMismatchMinor;
        this.missingMinor = b.missingMinor;
        this.duplicateMinor = b.duplicateMinor;
        this.extraMinor = b.extraMinor;
        this.timingMinor = b.timingMinor;
        this.statusMismatchMinor = b.statusMismatchMinor;
        this.currencyMismatchMinor = b.currencyMismatchMinor;
        this.groupSumMismatchMinor = b.groupSumMismatchMinor;
        this.bridgeBrokenMinor = b.bridgeBrokenMinor;
        this.rightSideTotalMinor = b.rightSideTotalMinor;
        this.leftResidualMinor = b.leftResidualMinor;
        this.rightResidualMinor = b.rightResidualMinor;
        this.balanced = b.balanced;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String runId() { return runId; }
    public String segmentId() { return segmentId; }
    public String currency() { return currency; }
    public long expectedTotalMinor() { return expectedTotalMinor; }
    public long matchedAmountMinor() { return matchedAmountMinor; }
    public long amountMismatchMinor() { return amountMismatchMinor; }
    public long missingMinor() { return missingMinor; }
    public long duplicateMinor() { return duplicateMinor; }
    public long extraMinor() { return extraMinor; }
    public long timingMinor() { return timingMinor; }
    public long statusMismatchMinor() { return statusMismatchMinor; }
    public long currencyMismatchMinor() { return currencyMismatchMinor; }
    public long groupSumMismatchMinor() { return groupSumMismatchMinor; }
    public long bridgeBrokenMinor() { return bridgeBrokenMinor; }
    public long rightSideTotalMinor() { return rightSideTotalMinor; }
    public long leftResidualMinor() { return leftResidualMinor; }
    public long rightResidualMinor() { return rightResidualMinor; }
    public boolean balanced() { return balanced; }

    @Override
    public String toString() {
        return "ReconReport{" + segmentId + "/" + currency
                + ", expected=" + expectedTotalMinor + ", matched=" + matchedAmountMinor
                + ", rightTotal=" + rightSideTotalMinor
                + ", leftResidual=" + leftResidualMinor + ", rightResidual=" + rightResidualMinor
                + ", balanced=" + balanced + '}';
    }

    public static final class Builder {
        private String runId;
        private String segmentId;
        private String currency;
        private long expectedTotalMinor;
        private long matchedAmountMinor;
        private long amountMismatchMinor;
        private long missingMinor;
        private long duplicateMinor;
        private long extraMinor;
        private long timingMinor;
        private long statusMismatchMinor;
        private long currencyMismatchMinor;
        private long groupSumMismatchMinor;
        private long bridgeBrokenMinor;
        private long rightSideTotalMinor;
        private long leftResidualMinor;
        private long rightResidualMinor;
        private boolean balanced;

        public Builder runId(String v) { this.runId = v; return this; }
        public Builder segmentId(String v) { this.segmentId = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder expectedTotalMinor(long v) { this.expectedTotalMinor = v; return this; }
        public Builder matchedAmountMinor(long v) { this.matchedAmountMinor = v; return this; }
        public Builder amountMismatchMinor(long v) { this.amountMismatchMinor = v; return this; }
        public Builder missingMinor(long v) { this.missingMinor = v; return this; }
        public Builder duplicateMinor(long v) { this.duplicateMinor = v; return this; }
        public Builder extraMinor(long v) { this.extraMinor = v; return this; }
        public Builder timingMinor(long v) { this.timingMinor = v; return this; }
        public Builder statusMismatchMinor(long v) { this.statusMismatchMinor = v; return this; }
        public Builder currencyMismatchMinor(long v) { this.currencyMismatchMinor = v; return this; }
        public Builder groupSumMismatchMinor(long v) { this.groupSumMismatchMinor = v; return this; }
        public Builder bridgeBrokenMinor(long v) { this.bridgeBrokenMinor = v; return this; }
        public Builder rightSideTotalMinor(long v) { this.rightSideTotalMinor = v; return this; }
        public Builder leftResidualMinor(long v) { this.leftResidualMinor = v; return this; }
        public Builder rightResidualMinor(long v) { this.rightResidualMinor = v; return this; }
        public Builder balanced(boolean v) { this.balanced = v; return this; }

        public ReconReport build() {
            return new ReconReport(this);
        }
    }
}
