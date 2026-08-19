package com.lrj.recon.batch.config;

import com.lrj.recon.core.domain.model.EvaluatorType;
import com.lrj.recon.core.domain.service.EvaluatorFactory;
import com.lrj.recon.core.spi.DiscrepancyEvaluator;
import com.lrj.recon.core.spi.DroolsEvaluator;

/**
 * 组合根判差器装配口 (B2)。EXACT/TOLERANCE 走纯 core {@link EvaluatorFactory};DROOLS 路由到注入的
 * {@link DroolsEvaluator} bean (封装在 recon-rules-drools, 组合根不直接 import org.kie/org.drools)。
 *
 * <p><b>fail-fast 红线</b>: 配 DROOLS 但未启用 Drools bean → 抛 {@link UnsupportedOperationException},
 * 绝不静默回退 Exact / 静默跳过判差。要回退只能显式改配置 {@code evaluator-type=EXACT}。
 */
public final class EvaluatorResolver {

    private final DroolsEvaluator droolsEvaluator; // 可为 null (未启用 recon.rules.drools.enabled)

    public EvaluatorResolver(DroolsEvaluator droolsEvaluator) {
        this.droolsEvaluator = droolsEvaluator;
    }

    public DiscrepancyEvaluator resolve(EvaluatorType type) {
        if (type == EvaluatorType.DROOLS) {
            if (droolsEvaluator == null) {
                throw new UnsupportedOperationException(
                        "evaluator-type=DROOLS requires the Drools rule engine; enable it with"
                        + " recon.rules.drools.enabled=true. Never silently fall back to EXACT.");
            }
            return droolsEvaluator;
        }
        // EXACT / TOLERANCE: 纯 core 装配 (DROOLS 在 core 侧仍 fail-fast, 由本类拦截)。
        return EvaluatorFactory.create(type);
    }
}
