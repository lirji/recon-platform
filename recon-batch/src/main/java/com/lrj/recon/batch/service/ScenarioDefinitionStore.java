package com.lrj.recon.batch.service;

import com.lrj.recon.scenario.dsl.ScenarioDefinition;

import java.util.List;
import java.util.Optional;

/**
 * B4 · 场景定义存储端口 (组合根只读+写)。发起 Run 时按 {@code code} 取定义装配;管理台 CRUD 定义。
 * JDBC 实现留在 {@code recon-batch.persistence};校验 (装配 fail-fast) 由 {@link ScenarioDefinitionCodec} 承担。
 */
public interface ScenarioDefinitionStore {

    /** upsert by code。存前必须已通过 {@link ScenarioDefinitionCodec#validate} 校验。enabled 控制能否发起。 */
    void save(ScenarioDefinition definition, boolean enabled);

    /** 按 code 取 (含停用的);不存在返回 empty。 */
    Optional<Stored> find(String code);

    /** 列出全部 (含停用),按 code 升序,用于管理台。 */
    List<Stored> list();

    /** 一条存储记录 = 定义 + 版本 + 启用状态。 */
    record Stored(ScenarioDefinition definition, int version, boolean enabled) {
    }
}
