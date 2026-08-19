package com.lrj.recon.batch.web;

import com.lrj.recon.batch.service.NotFoundException;
import com.lrj.recon.batch.service.ScenarioDefinitionCodec;
import com.lrj.recon.batch.service.ScenarioDefinitionStore;
import com.lrj.recon.scenario.dsl.ScenarioDefinition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B4 · 场景定义管理 API(配置驱动平台)。读(list/get)= {@code recon.read};写(PUT)= {@code recon.launch}
 * (由 CasdoorSecurityConfig 的显式 matcher 约束)。写入前经 {@link ScenarioDefinitionCodec} 装配校验,坏定义 400。
 */
@RestController
@RequestMapping("/recon/scenarios")
public class ScenarioAdminController {

    private final ScenarioDefinitionStore store;
    private final ScenarioDefinitionCodec codec;

    public ScenarioAdminController(ScenarioDefinitionStore store, ScenarioDefinitionCodec codec) {
        this.store = store;
        this.codec = codec;
    }

    @GetMapping
    public List<ScenarioSummary> list() {
        return store.list().stream().map(ScenarioSummary::of).toList();
    }

    @GetMapping("/{code}")
    public ScenarioView get(@PathVariable("code") String code) {
        ScenarioDefinitionStore.Stored s = store.find(code)
                .orElseThrow(() -> new NotFoundException("scenario definition not found: " + code));
        return ScenarioView.of(s);
    }

    /** upsert 场景定义。{@code enabled} 控制能否发起。code 须与路径一致;非法定义 400(装配校验)。 */
    @PutMapping("/{code}")
    public ScenarioView save(@PathVariable("code") String code,
                             @RequestParam(name = "enabled", defaultValue = "true") boolean enabled,
                             @RequestBody ScenarioDefinition definition) {
        if (!code.equals(definition.code())) {
            throw new IllegalArgumentException("path code '" + code + "' does not match body code '"
                    + definition.code() + "'");
        }
        codec.validate(definition); // 装配 fail-fast(非法 → IllegalArgumentException → 400)
        store.save(definition, enabled);
        return get(code);
    }

    /** 列表摘要:金额无关,只报形态。 */
    public record ScenarioSummary(String code, int version, boolean enabled, int segmentCount) {
        static ScenarioSummary of(ScenarioDefinitionStore.Stored s) {
            return new ScenarioSummary(s.definition().code(), s.version(), s.enabled(),
                    s.definition().segments().size());
        }
    }

    /** 详情:含完整声明式定义(Jackson 序列化回 JSON)。 */
    public record ScenarioView(String code, int version, boolean enabled, ScenarioDefinition definition) {
        static ScenarioView of(ScenarioDefinitionStore.Stored s) {
            return new ScenarioView(s.definition().code(), s.version(), s.enabled(), s.definition());
        }
    }
}
