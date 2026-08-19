package com.lrj.recon.batch.service;

import com.lrj.recon.scenario.MarketingThreeWayScenario;
import com.lrj.recon.scenario.dsl.AssembledScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B4 Phase 3b · 配置→场景桥:按 code 从存储装配;不存在 404,停用 fail-fast。
 */
@SpringBootTest
class ConfigScenarioServiceTest {

    @Autowired
    ConfigScenarioService service;

    @Autowired
    ScenarioDefinitionStore store;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM recon_scenario_def");
    }

    @Test
    void assembles_enabled_definition_by_code() {
        store.save(ScenarioDefinitionCodecTest.sample("S_OK"), true);
        AssembledScenario asm = service.assemble("S_OK");
        assertThat(asm.code()).isEqualTo("S_OK");
        assertThat(asm.segments()).hasSize(1);
        assertThat(service.isRunnable("S_OK")).isTrue();
    }

    @Test
    void missing_code_is_not_found() {
        assertThatThrownBy(() -> service.assemble("NOPE")).isInstanceOf(NotFoundException.class);
        assertThat(service.isRunnable("NOPE")).isFalse();
    }

    @Test
    void disabled_definition_fails_fast_and_is_not_runnable() {
        store.save(ScenarioDefinitionCodecTest.sample("S_OFF"), false);
        assertThatThrownBy(() -> service.assemble("S_OFF")).isInstanceOf(IllegalStateException.class);
        assertThat(service.isRunnable("S_OFF")).isFalse();
    }

    @Test
    void assembles_seeded_marketing_definition() {
        store.save(com.lrj.recon.scenario.dsl.MarketingThreeWayDefinition.seed(), true);
        AssembledScenario asm = service.assemble(MarketingThreeWayScenario.SCENARIO_CODE);
        assertThat(asm.segments()).hasSize(2);
    }
}
