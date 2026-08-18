package com.lrj.recon.core.domain.model;

import java.time.Instant;

/**
 * 1:N 匹配组的<b>流式聚合单元</b> —— 只持聚合量, 不物化左右全列表 (修补·内存缺口)。
 *
 * <p>持有: 左右带符号求和、左右计数、presence、duplicate 标记、左右币种、有界 rawRef 样本 (左右各首条),
 * 以及供 STATUS/TIMING 判定的左右代表 bizStatus/postingTime。阶段二明细下钻才物化列表。
 */
public final class MatchGroup {

    private final GroupKey groupKey;
    private final MatchKey matchKey;

    private final String leftCurrency;   // 左侧缺失时为 null
    private final String rightCurrency;  // 右侧缺失时为 null

    private final long sumSignedLeftMinor;
    private final long sumSignedRightMinor;
    private final int countLeft;
    private final int countRight;

    private final Presence presence;
    private final boolean duplicate;

    private final String leftSampleRawRef;
    private final String rightSampleRawRef;

    private final String leftBizStatus;
    private final String rightBizStatus;
    private final Instant leftPostingTime;
    private final Instant rightPostingTime;

    private MatchGroup(Builder b) {
        this.groupKey = b.groupKey;
        this.matchKey = b.matchKey;
        this.leftCurrency = b.leftCurrency;
        this.rightCurrency = b.rightCurrency;
        this.sumSignedLeftMinor = b.sumSignedLeftMinor;
        this.sumSignedRightMinor = b.sumSignedRightMinor;
        this.countLeft = b.countLeft;
        this.countRight = b.countRight;
        this.presence = b.presence;
        this.duplicate = b.duplicate;
        this.leftSampleRawRef = b.leftSampleRawRef;
        this.rightSampleRawRef = b.rightSampleRawRef;
        this.leftBizStatus = b.leftBizStatus;
        this.rightBizStatus = b.rightBizStatus;
        this.leftPostingTime = b.leftPostingTime;
        this.rightPostingTime = b.rightPostingTime;
    }

    public static Builder builder() {
        return new Builder();
    }

    public GroupKey groupKey() { return groupKey; }
    public MatchKey matchKey() { return matchKey; }
    public String leftCurrency() { return leftCurrency; }
    public String rightCurrency() { return rightCurrency; }
    public long sumSignedLeftMinor() { return sumSignedLeftMinor; }
    public long sumSignedRightMinor() { return sumSignedRightMinor; }
    public int countLeft() { return countLeft; }
    public int countRight() { return countRight; }
    public Presence presence() { return presence; }
    public boolean duplicate() { return duplicate; }
    public String leftSampleRawRef() { return leftSampleRawRef; }
    public String rightSampleRawRef() { return rightSampleRawRef; }
    public String leftBizStatus() { return leftBizStatus; }
    public String rightBizStatus() { return rightBizStatus; }
    public Instant leftPostingTime() { return leftPostingTime; }
    public Instant rightPostingTime() { return rightPostingTime; }

    public boolean hasLeft() {
        return presence == Presence.BOTH || presence == Presence.LEFT_ONLY;
    }

    public boolean hasRight() {
        return presence == Presence.BOTH || presence == Presence.RIGHT_ONLY;
    }

    /** 是否为多行 (1:N) 聚合组: 任一侧记录数 &gt; 1。 */
    public boolean isMultiLine() {
        return countLeft > 1 || countRight > 1;
    }

    /** 左右币种是否一致 (需两侧都在)。 */
    public boolean isCurrencyConsistent() {
        return leftCurrency != null && leftCurrency.equals(rightCurrency);
    }

    @Override
    public String toString() {
        return "MatchGroup{" + matchKey + ", presence=" + presence
                + ", L=" + sumSignedLeftMinor + "(" + leftCurrency + ")x" + countLeft
                + ", R=" + sumSignedRightMinor + "(" + rightCurrency + ")x" + countRight
                + ", dup=" + duplicate + '}';
    }

    public static final class Builder {
        private GroupKey groupKey;
        private MatchKey matchKey;
        private String leftCurrency;
        private String rightCurrency;
        private long sumSignedLeftMinor;
        private long sumSignedRightMinor;
        private int countLeft;
        private int countRight;
        private Presence presence;
        private boolean duplicate;
        private String leftSampleRawRef;
        private String rightSampleRawRef;
        private String leftBizStatus;
        private String rightBizStatus;
        private Instant leftPostingTime;
        private Instant rightPostingTime;

        public Builder groupKey(GroupKey v) { this.groupKey = v; return this; }
        public Builder matchKey(MatchKey v) { this.matchKey = v; return this; }
        public Builder leftCurrency(String v) { this.leftCurrency = v; return this; }
        public Builder rightCurrency(String v) { this.rightCurrency = v; return this; }
        public Builder sumSignedLeftMinor(long v) { this.sumSignedLeftMinor = v; return this; }
        public Builder sumSignedRightMinor(long v) { this.sumSignedRightMinor = v; return this; }
        public Builder countLeft(int v) { this.countLeft = v; return this; }
        public Builder countRight(int v) { this.countRight = v; return this; }
        public Builder presence(Presence v) { this.presence = v; return this; }
        public Builder duplicate(boolean v) { this.duplicate = v; return this; }
        public Builder leftSampleRawRef(String v) { this.leftSampleRawRef = v; return this; }
        public Builder rightSampleRawRef(String v) { this.rightSampleRawRef = v; return this; }
        public Builder leftBizStatus(String v) { this.leftBizStatus = v; return this; }
        public Builder rightBizStatus(String v) { this.rightBizStatus = v; return this; }
        public Builder leftPostingTime(Instant v) { this.leftPostingTime = v; return this; }
        public Builder rightPostingTime(Instant v) { this.rightPostingTime = v; return this; }

        public MatchGroup build() {
            return new MatchGroup(this);
        }
    }
}
