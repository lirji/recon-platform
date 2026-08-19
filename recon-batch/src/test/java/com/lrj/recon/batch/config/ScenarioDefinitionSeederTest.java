package com.lrj.recon.batch.config;

import com.lrj.recon.batch.service.ScenarioDefinitionStore;
import com.lrj.recon.scenario.MarketingThreeWayScenario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4 Phase 3a · 场景种子。显式清表后调 seeder,避免 Spring 测试上下文缓存下的启动 seed 被其它用例删除导致的顺序敏感。
 */
@SpringBootTest
class ScenarioDefinitionSeederTest {

    @Autowired
    ScenarioDefinitionSeeder seeder;

    @Autowired
    ScenarioDefinitionStore store;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seeds_builtin_marketing_definition_when_absent() {
        jdbc.update("DELETE FROM recon_scenario_def");
        seeder.run(null);

        ScenarioDefinitionStore.Stored s = store.find(MarketingThreeWayScenario.SCENARIO_CODE).orElseThrow();
        assertThat(s.enabled()).isTrue();
        assertThat(s.definition().segments()).hasSize(2);
    }

    @Test
    void seed_is_idempotent_and_does_not_overwrite() {
        jdbc.update("DELETE FROM recon_scenario_def");
        seeder.run(null);
        int versionAfterFirst = store.find(MarketingThreeWayScenario.SCENARIO_CODE).orElseThrow().version();

        seeder.run(null); // 再次运行不应覆盖(不 bump version)

        assertThat(store.find(MarketingThreeWayScenario.SCENARIO_CODE).orElseThrow().version())
                .isEqualTo(versionAfterFirst);
    }
}
