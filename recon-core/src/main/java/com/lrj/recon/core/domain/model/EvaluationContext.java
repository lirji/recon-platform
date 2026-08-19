package com.lrj.recon.core.domain.model;

import java.time.Instant;

/**
 * 判差上下文: 携带段内角色 (leftRole/rightRole/spineRole) 与断段归因标签, 供 BRIDGE_BROKEN / MISSING /
 * EXTRA 的 presence 判定, 以及 TIMING 的 T~T+1 匹配窗口。是纯数据快照, 无框架依赖。
 */
public final class EvaluationContext {

    private final String runId;
    private final String scenarioCode;
    private final String accountingPeriod;
    private final String segmentId;
    private final SourceRole leftRole;
    private final SourceRole rightRole;
    private final SourceRole spineRole;
    private final String stageLabel;         // "SEG1" | "SEG2"
    private final Instant matchWindowFrom;   // T (可空 → 不做窗口约束)
    private final Instant matchWindowTo;     // T+1
    private final long fxToleranceMinor;     // B6: 跨币基准额容差(基准币最小单位); 0=严格, 任何基准差即 FX_RATE_DIFF

    private EvaluationContext(Builder b) {
        this.runId = b.runId;
        this.scenarioCode = b.scenarioCode;
        this.accountingPeriod = b.accountingPeriod;
        this.segmentId = b.segmentId;
        this.leftRole = b.leftRole;
        this.rightRole = b.rightRole;
        this.spineRole = b.spineRole;
        this.stageLabel = b.stageLabel;
        this.matchWindowFrom = b.matchWindowFrom;
        this.matchWindowTo = b.matchWindowTo;
        this.fxToleranceMinor = b.fxToleranceMinor;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 由段配置派生上下文骨架 (角色/标签/段id), 运行期再补窗口。 */
    public static Builder fromSegment(SegmentSpec spec) {
        return new Builder()
                .segmentId(spec.segmentId())
                .leftRole(spec.leftRole())
                .rightRole(spec.rightRole())
                .spineRole(spec.spineRole())
                .stageLabel(spec.stageLabel());
    }

    public String runId() { return runId; }
    public String scenarioCode() { return scenarioCode; }
    public String accountingPeriod() { return accountingPeriod; }
    public String segmentId() { return segmentId; }
    public SourceRole leftRole() { return leftRole; }
    public SourceRole rightRole() { return rightRole; }
    public SourceRole spineRole() { return spineRole; }
    public String stageLabel() { return stageLabel; }
    public Instant matchWindowFrom() { return matchWindowFrom; }
    public Instant matchWindowTo() { return matchWindowTo; }

    public long fxToleranceMinor() { return fxToleranceMinor; }

    public boolean hasWindow() {
        return matchWindowFrom != null && matchWindowTo != null;
    }

    public static final class Builder {
        private String runId;
        private String scenarioCode;
        private String accountingPeriod;
        private String segmentId;
        private SourceRole leftRole;
        private SourceRole rightRole;
        private SourceRole spineRole;
        private String stageLabel;
        private Instant matchWindowFrom;
        private Instant matchWindowTo;
        private long fxToleranceMinor;

        public Builder runId(String v) { this.runId = v; return this; }
        public Builder scenarioCode(String v) { this.scenarioCode = v; return this; }
        public Builder accountingPeriod(String v) { this.accountingPeriod = v; return this; }
        public Builder segmentId(String v) { this.segmentId = v; return this; }
        public Builder leftRole(SourceRole v) { this.leftRole = v; return this; }
        public Builder rightRole(SourceRole v) { this.rightRole = v; return this; }
        public Builder spineRole(SourceRole v) { this.spineRole = v; return this; }
        public Builder stageLabel(String v) { this.stageLabel = v; return this; }
        public Builder matchWindowFrom(Instant v) { this.matchWindowFrom = v; return this; }
        public Builder matchWindowTo(Instant v) { this.matchWindowTo = v; return this; }
        public Builder fxToleranceMinor(long v) { this.fxToleranceMinor = v; return this; }

        public EvaluationContext build() {
            return new EvaluationContext(this);
        }
    }
}
