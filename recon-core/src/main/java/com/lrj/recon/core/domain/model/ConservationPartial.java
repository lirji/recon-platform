package com.lrj.recon.core.domain.model;

/**
 * 单个 (segment, bucket, currency) 的<b>局部守恒累计快照</b> (设计 §8 / M3 单遍守恒)。
 *
 * <p>M3 分桶并行下, 每个 partition 只处理<b>一个 bucket</b> 的组, 流式累计出该 bucket 内各币种的守恒子项
 * (常量内存), 落一份 {@link ConservationPartial}。汇总步跨 bucket 按 (segment, currency) 把同名子项逐个
 * {@code addExact} 合并, 复算出与 M2 单线程 {@code ConservationChecker} <b>逐字段等价</b>的最终 {@link ReconReport}。
 *
 * <p>本 VO 只承载<b>原始子项</b> (不含派生的 residual/balanced) —— residual 是 {@code expectedTotal − Σ 子项}
 * 的线性组合, 对分桶求和后再算, 与"整段一次累计"结果相同 (addExact 结合律)。全为带符号整数分, 禁 double。
 *
 * <p>字段与 {@code ConservationChecker} 的内部桶一一对应:
 * <ul>
 *   <li>左口径: expectedTotal / matchedLeft / missing / amountMismatchLeft / statusLeft / timingLeft /
 *       groupSumLeft / duplicateLeft / bridgeBrokenLeft / currencyMismatchLeft;</li>
 *   <li>右口径: rightSideTotal / matchedRight / extra / bridgeBrokenRight / currencyMismatchRight。</li>
 * </ul>
 */
public final class ConservationPartial {

    private final String runId;
    private final String segmentId;
    private final int bucket;
    /** 二级 sub-bucket 分片号 (数据倾斜拆分时唯一化局部结果; 未拆为 -1)。参与幂等键, 不参与汇总求和。 */
    private final int subIndex;
    private final String currency;

    private final long expectedTotalMinor;
    private final long rightSideTotalMinor;
    private final long matchedLeftMinor;
    private final long matchedRightMinor;
    private final long missingMinor;
    private final long extraMinor;
    private final long amountMismatchLeftMinor;
    private final long statusLeftMinor;
    private final long timingLeftMinor;
    private final long groupSumLeftMinor;
    private final long duplicateLeftMinor;
    private final long bridgeBrokenLeftMinor;
    private final long bridgeBrokenRightMinor;
    private final long currencyMismatchLeftMinor;
    private final long currencyMismatchRightMinor;

    private ConservationPartial(Builder b) {
        this.runId = b.runId;
        this.segmentId = b.segmentId;
        this.bucket = b.bucket;
        this.subIndex = b.subIndex;
        this.currency = b.currency;
        this.expectedTotalMinor = b.expectedTotalMinor;
        this.rightSideTotalMinor = b.rightSideTotalMinor;
        this.matchedLeftMinor = b.matchedLeftMinor;
        this.matchedRightMinor = b.matchedRightMinor;
        this.missingMinor = b.missingMinor;
        this.extraMinor = b.extraMinor;
        this.amountMismatchLeftMinor = b.amountMismatchLeftMinor;
        this.statusLeftMinor = b.statusLeftMinor;
        this.timingLeftMinor = b.timingLeftMinor;
        this.groupSumLeftMinor = b.groupSumLeftMinor;
        this.duplicateLeftMinor = b.duplicateLeftMinor;
        this.bridgeBrokenLeftMinor = b.bridgeBrokenLeftMinor;
        this.bridgeBrokenRightMinor = b.bridgeBrokenRightMinor;
        this.currencyMismatchLeftMinor = b.currencyMismatchLeftMinor;
        this.currencyMismatchRightMinor = b.currencyMismatchRightMinor;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String runId() { return runId; }
    public String segmentId() { return segmentId; }
    public int bucket() { return bucket; }
    public int subIndex() { return subIndex; }
    public String currency() { return currency; }
    public long expectedTotalMinor() { return expectedTotalMinor; }
    public long rightSideTotalMinor() { return rightSideTotalMinor; }
    public long matchedLeftMinor() { return matchedLeftMinor; }
    public long matchedRightMinor() { return matchedRightMinor; }
    public long missingMinor() { return missingMinor; }
    public long extraMinor() { return extraMinor; }
    public long amountMismatchLeftMinor() { return amountMismatchLeftMinor; }
    public long statusLeftMinor() { return statusLeftMinor; }
    public long timingLeftMinor() { return timingLeftMinor; }
    public long groupSumLeftMinor() { return groupSumLeftMinor; }
    public long duplicateLeftMinor() { return duplicateLeftMinor; }
    public long bridgeBrokenLeftMinor() { return bridgeBrokenLeftMinor; }
    public long bridgeBrokenRightMinor() { return bridgeBrokenRightMinor; }
    public long currencyMismatchLeftMinor() { return currencyMismatchLeftMinor; }
    public long currencyMismatchRightMinor() { return currencyMismatchRightMinor; }

    @Override
    public String toString() {
        return "ConservationPartial{" + segmentId + "/b" + bucket + "/" + currency
                + ", expected=" + expectedTotalMinor + ", rightTotal=" + rightSideTotalMinor + '}';
    }

    public static final class Builder {
        private String runId;
        private String segmentId;
        private int bucket;
        private int subIndex = -1;
        private String currency;
        private long expectedTotalMinor;
        private long rightSideTotalMinor;
        private long matchedLeftMinor;
        private long matchedRightMinor;
        private long missingMinor;
        private long extraMinor;
        private long amountMismatchLeftMinor;
        private long statusLeftMinor;
        private long timingLeftMinor;
        private long groupSumLeftMinor;
        private long duplicateLeftMinor;
        private long bridgeBrokenLeftMinor;
        private long bridgeBrokenRightMinor;
        private long currencyMismatchLeftMinor;
        private long currencyMismatchRightMinor;

        public Builder runId(String v) { this.runId = v; return this; }
        public Builder segmentId(String v) { this.segmentId = v; return this; }
        public Builder bucket(int v) { this.bucket = v; return this; }
        public Builder subIndex(int v) { this.subIndex = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder expectedTotalMinor(long v) { this.expectedTotalMinor = v; return this; }
        public Builder rightSideTotalMinor(long v) { this.rightSideTotalMinor = v; return this; }
        public Builder matchedLeftMinor(long v) { this.matchedLeftMinor = v; return this; }
        public Builder matchedRightMinor(long v) { this.matchedRightMinor = v; return this; }
        public Builder missingMinor(long v) { this.missingMinor = v; return this; }
        public Builder extraMinor(long v) { this.extraMinor = v; return this; }
        public Builder amountMismatchLeftMinor(long v) { this.amountMismatchLeftMinor = v; return this; }
        public Builder statusLeftMinor(long v) { this.statusLeftMinor = v; return this; }
        public Builder timingLeftMinor(long v) { this.timingLeftMinor = v; return this; }
        public Builder groupSumLeftMinor(long v) { this.groupSumLeftMinor = v; return this; }
        public Builder duplicateLeftMinor(long v) { this.duplicateLeftMinor = v; return this; }
        public Builder bridgeBrokenLeftMinor(long v) { this.bridgeBrokenLeftMinor = v; return this; }
        public Builder bridgeBrokenRightMinor(long v) { this.bridgeBrokenRightMinor = v; return this; }
        public Builder currencyMismatchLeftMinor(long v) { this.currencyMismatchLeftMinor = v; return this; }
        public Builder currencyMismatchRightMinor(long v) { this.currencyMismatchRightMinor = v; return this; }

        public ConservationPartial build() {
            return new ConservationPartial(this);
        }
    }
}
