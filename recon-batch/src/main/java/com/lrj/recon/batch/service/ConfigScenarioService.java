package com.lrj.recon.batch.service;

import com.lrj.recon.scenario.dsl.AssembledScenario;
import com.lrj.recon.scenario.dsl.GenericScenarioAssembler;
import org.springframework.stereotype.Service;

/**
 * B4 Phase 3b · 配置→场景桥。按 code 从 {@link ScenarioDefinitionStore} 取声明式定义并经
 * {@link GenericScenarioAssembler} 装配成 {@link AssembledScenario}(段蓝图 + 桥接抽取器),供通用执行引擎消费。
 *
 * <p>校验语义:code 不存在 → {@link NotFoundException}(REST 层映射 404);已停用 → {@link IllegalStateException}
 * (不可发起);结构非法在装配期 fail-fast。这是「发起按 code 从配置装配」的读侧入口(通用执行引擎 Phase 3b 落地后接线)。
 */
@Service
public class ConfigScenarioService {

    private final ScenarioDefinitionStore store;

    public ConfigScenarioService(ScenarioDefinitionStore store) {
        this.store = store;
    }

    /** 取启用的场景装配蓝图;不存在 404,停用 fail-fast。 */
    public AssembledScenario assemble(String code) {
        ScenarioDefinitionStore.Stored stored = store.find(code)
                .orElseThrow(() -> new NotFoundException("scenario definition not found: " + code));
        if (!stored.enabled()) {
            throw new IllegalStateException("scenario definition is disabled: " + code);
        }
        return GenericScenarioAssembler.assemble(stored.definition());
    }

    /** 该 code 是否存在且启用(可发起)。 */
    public boolean isRunnable(String code) {
        return store.find(code).map(ScenarioDefinitionStore.Stored::enabled).orElse(false);
    }
}
