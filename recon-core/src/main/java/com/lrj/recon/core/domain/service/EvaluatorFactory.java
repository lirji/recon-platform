package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.EvaluatorType;
import com.lrj.recon.core.spi.DiscrepancyEvaluator;

/**
 * 判差器装配。MVP 只产 {@link ExactEvaluator}。
 *
 * <p>设计红线 (ADR-8): 遇 {@link EvaluatorType#DROOLS} 抛 {@link UnsupportedOperationException}
 * <b>fail-fast, 绝不静默跳过判差</b>; {@link EvaluatorType#TOLERANCE} 归 M4, 同样 fail-fast 而非返回 null。
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
            case TOLERANCE -> throw new UnsupportedOperationException(
                    "ToleranceEvaluator not implemented in M0 (planned for M4)");
            case DROOLS -> throw new UnsupportedOperationException(
                    "DroolsEvaluator is interface-only; wiring DROOLS must fail-fast, never silently skip evaluation");
        };
    }
}
