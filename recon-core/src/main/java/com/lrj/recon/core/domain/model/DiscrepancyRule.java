package com.lrj.recon.core.domain.model;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * 判差规则 (同时充当阶段二 Drools fact)。
 *
 * <p>MVP 的 {@link com.lrj.recon.core.domain.service.ExactEvaluator} 走精确比较 ({@code absToleranceMinor=0})。
 * {@code ratioToleranceBps} / {@code timingWindow} / {@code enabled} 为容差与开关留位, M4 起生效。
 */
public final class DiscrepancyRule {

    private final long absToleranceMinor;
    private final int ratioToleranceBps;
    private final Duration timingWindow;
    private final Set<DiscrepancyType> enabled;
    private final EvaluatorType evaluatorType;

    private DiscrepancyRule(Builder b) {
        this.absToleranceMinor = b.absToleranceMinor;
        this.ratioToleranceBps = b.ratioToleranceBps;
        this.timingWindow = b.timingWindow;
        this.enabled = b.enabled == null ? EnumSet.allOf(DiscrepancyType.class)
                : b.enabled.isEmpty() ? EnumSet.noneOf(DiscrepancyType.class) : EnumSet.copyOf(b.enabled);
        this.evaluatorType = b.evaluatorType == null ? EvaluatorType.EXACT : b.evaluatorType;
    }

    /** 精确匹配规则 (MVP 默认): 零容差, EXACT。 */
    public static DiscrepancyRule exact() {
        return new Builder().evaluatorType(EvaluatorType.EXACT).absToleranceMinor(0).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public long absToleranceMinor() { return absToleranceMinor; }
    public int ratioToleranceBps() { return ratioToleranceBps; }
    public Duration timingWindow() { return timingWindow; }
    public Set<DiscrepancyType> enabled() { return EnumSet.copyOf(enabled); }
    public EvaluatorType evaluatorType() { return evaluatorType; }

    public boolean isEnabled(DiscrepancyType type) {
        return enabled.contains(type);
    }

    public static final class Builder {
        private long absToleranceMinor;
        private int ratioToleranceBps;
        private Duration timingWindow;
        private Set<DiscrepancyType> enabled;
        private EvaluatorType evaluatorType;

        public Builder absToleranceMinor(long v) { this.absToleranceMinor = v; return this; }
        public Builder ratioToleranceBps(int v) { this.ratioToleranceBps = v; return this; }
        public Builder timingWindow(Duration v) { this.timingWindow = v; return this; }
        public Builder enabled(Set<DiscrepancyType> v) { this.enabled = v; return this; }
        public Builder evaluatorType(EvaluatorType v) { this.evaluatorType = v; return this; }

        public DiscrepancyRule build() {
            return new DiscrepancyRule(this);
        }
    }
}
