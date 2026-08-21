package com.lrj.recon.batch.config;

import com.lrj.recon.batch.service.ScenarioDefinitionStore;
import com.lrj.recon.scenario.dsl.MarketingThreeWayDefinition;
import com.lrj.recon.scenario.dsl.BenefitCashThreeWayDefinition;
import com.lrj.recon.scenario.dsl.ScenarioDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * B4 Phase 3a · 启动期把内置场景的声明式定义 seed 进配置存储(幂等:仅当缺失时写入),
 * 让「场景=数据」在管理/校验层成立。<b>不改发起路径</b>(执行仍走既有 {@code marketingThreeWayJob}),
 * 故对现有测试零影响;通用执行引擎(Phase 3b)就绪后再让发起按 code 从配置装配。
 */
@Component
public class ScenarioDefinitionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioDefinitionSeeder.class);

    private final ScenarioDefinitionStore store;

    public ScenarioDefinitionSeeder(ScenarioDefinitionStore store) {
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (BuiltinScenario builtin : java.util.List.of(
                new BuiltinScenario(MarketingThreeWayDefinition.seed(), true),
                // 权益中台现金三方数据已可落 ODS；旧通用执行器尚未强制 tenant/window 过滤，先禁止误启动。
                new BuiltinScenario(BenefitCashThreeWayDefinition.seed(), false))) {
            ScenarioDefinition definition = builtin.definition();
            if (store.find(definition.code()).isPresent()) {
                continue; // 幂等:已存在(可能被管理台改过),不覆盖
            }
            store.save(definition, builtin.enabled());
            log.info("[scenario] seeded built-in definition code={} segments={} enabled={}",
                    definition.code(), definition.segments().size(), builtin.enabled());
        }
    }

    private record BuiltinScenario(ScenarioDefinition definition, boolean enabled) {}
}
