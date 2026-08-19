package com.lrj.recon.batch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.recon.scenario.dsl.GenericScenarioAssembler;
import com.lrj.recon.scenario.dsl.ScenarioDefinition;
import org.springframework.stereotype.Component;

/**
 * B4 · 场景定义 JSON 编解码 (组合根)。把声明式 {@link ScenarioDefinition} 与 JSON 互转,并在
 * 反序列化后经 {@link GenericScenarioAssembler} <b>装配校验</b> —— 结构不合法 (键字段空 / 重复段 / refine 无定义)
 * 即 fail-fast, 绝不让坏定义静默入库或发起 Run。序列化留在组合根 (recon-scenario 模块零框架, 不依赖 Jackson)。
 */
@Component
public class ScenarioDefinitionCodec {

    private final ObjectMapper objectMapper;

    public ScenarioDefinitionCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(ScenarioDefinition def) {
        try {
            return objectMapper.writeValueAsString(def);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize ScenarioDefinition " + def.code(), e);
        }
    }

    /** 反序列化并装配校验; 无效定义抛 {@link IllegalArgumentException} (含底层校验原因)。 */
    public ScenarioDefinition fromJson(String json) {
        ScenarioDefinition def;
        try {
            def = objectMapper.readValue(json, ScenarioDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to parse ScenarioDefinition JSON", e);
        }
        validate(def);
        return def;
    }

    /** 装配校验: 跑一遍通用装配 (KeySpec refine / 段唯一性等在此 fail-fast)。 */
    public void validate(ScenarioDefinition def) {
        GenericScenarioAssembler.assemble(def);
    }
}
