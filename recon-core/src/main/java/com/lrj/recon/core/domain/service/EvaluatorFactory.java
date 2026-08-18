package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.EvaluatorType;
import com.lrj.recon.core.spi.DiscrepancyEvaluator;

/**
 * 判差器装配。M4 起产 {@link ExactEvaluator} / {@link ToleranceEvaluator}。
 *
 * <p>设计红线 (ADR-8): 遇 {@link EvaluatorType#DROOLS} 抛 {@link UnsupportedOperationException}
 * <b>fail-fast, 绝不静默跳过判差</b> (阶段二才上 Drools)。容差阈值不在此传入 —— {@link ToleranceEvaluator}
 * 与 {@link ExactEvaluator} 一样在 evaluate 期从 {@link com.lrj.recon.core.domain.model.DiscrepancyRule} 读阈值。
 */
public final class EvaluatorFactory {

    private EvaluatorFactory() {
    }

    public static DiscrepancyEvaluator create(EvaluatorType type) {
        if (type == null) {
            throw new IllegalArgumentException("evaluatorType must not be null");
        }
        return switch (type) {
            case EXACT -> new ExactEvaluator();
            case TOLERANCE -> new ToleranceEvaluator();
            case DROOLS -> throw new UnsupportedOperationException(
                    "DroolsEvaluator is interface-only; wiring DROOLS must fail-fast, never silently skip evaluation");
        };
    }
}
