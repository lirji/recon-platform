package com.lrj.recon.core.spi;

/**
 * 阶段二 Drools 判差器 —— <b>仅接口占位</b>。
 *
 * <p>MVP 不提供实现; 装配层 (EvaluatorFactory) 遇 DROOLS 必须抛 {@link UnsupportedOperationException}
 * fail-fast, 绝不静默跳过判差 (设计红线)。
 */
public interface DroolsEvaluator extends DiscrepancyEvaluator {
}
