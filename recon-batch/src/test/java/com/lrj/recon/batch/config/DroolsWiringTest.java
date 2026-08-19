package com.lrj.recon.batch.config;

import com.lrj.recon.core.domain.model.EvaluatorType;
import com.lrj.recon.core.spi.DiscrepancyEvaluator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 装配端到端: {@code recon.rules.drools.enabled=true} 时 Drools bean 激活、DRL 在启动期编译成功
 * (编译失败则本 context 加载即失败 —— 证明 fail-fast), 且 {@link EvaluatorResolver} 对 DROOLS 返回该判差器。
 */
@SpringBootTest(properties = "recon.rules.drools.enabled=true")
class DroolsWiringTest {

    @Autowired
    EvaluatorResolver evaluatorResolver;

    @Test
    void drools_evaluator_is_wired_when_enabled() {
        DiscrepancyEvaluator drools = evaluatorResolver.resolve(EvaluatorType.DROOLS);
        assertThat(drools).isNotNull();
        assertThat(drools.evaluatorId()).isEqualTo("drools");
    }
}
