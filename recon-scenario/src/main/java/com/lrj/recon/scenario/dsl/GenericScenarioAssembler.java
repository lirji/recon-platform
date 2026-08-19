package com.lrj.recon.scenario.dsl;

import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.SegmentSpec;
import com.lrj.recon.core.domain.service.GroupSumMatchStrategy;
import com.lrj.recon.core.spi.SourceDescriptor;
import com.lrj.recon.scenario.SegmentDef;
import com.lrj.recon.scenario.SpineBridgeKeyExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * B4 · 通用场景装配器: 把声明式 {@link ScenarioDefinition} 装配成 {@link AssembledScenario}
 * ({@code SegmentDef} 蓝图 + {@link SpineBridgeKeyExtractor}),通用地复现今天 {@code MarketingThreeWayScenario.of(...)}
 * 手工拼出的同一批 {@code SegmentSpec}/描述符/{@code KeySpec}。<b>不改代码即接新场景</b>的核心装配口。
 *
 * <p>装配期沿用既有 fail-fast:{@code SpineBridgeKeyExtractor.KeySpec} 构造校验键字段名非空(refine 结构前提)。
 * 判差器 id 由 {@code rule.evaluatorType} 派生(exact/tolerance/drools),运行期由组合根 EvaluatorResolver 解析。
 * 纯 Java 零框架,只依赖 recon-core + recon-scenario 自身蓝图类型。
 */
public final class GenericScenarioAssembler {

    private GenericScenarioAssembler() {
    }

    public static AssembledScenario assemble(ScenarioDefinition def) {
        Objects.requireNonNull(def, "def");

        List<SpineBridgeKeyExtractor.KeySpec> keySpecs = new ArrayList<>();
        for (ScenarioDefinition.Segment s : def.segments()) {
            keySpecs.add(new SpineBridgeKeyExtractor.KeySpec(s.id(), s.matchKeyField(), s.groupKeyField()));
        }
        SpineBridgeKeyExtractor extractor = new SpineBridgeKeyExtractor(keySpecs);

        List<SegmentDef> segments = new ArrayList<>();
        for (ScenarioDefinition.Segment s : def.segments()) {
            DiscrepancyRule rule = s.rule().toDiscrepancyRule();
            SegmentSpec spec = new SegmentSpec(
                    s.id(), s.leftRole(), s.rightRole(), s.spineRole(), s.stageLabel(),
                    SpineBridgeKeyExtractor.ID, GroupSumMatchStrategy.STRATEGY_ID, evaluatorId(rule), List.of());
            SourceDescriptor left = new SourceDescriptor(s.left().sourceType(), s.left().params());
            SourceDescriptor right = new SourceDescriptor(s.right().sourceType(), s.right().params());
            segments.add(new SegmentDef(spec, left, right, rule));
        }
        return new AssembledScenario(def.code(), segments, extractor);
    }

    /** 与 MarketingThreeWayScenario 同口径: 枚举名小写 (EXACT→exact / TOLERANCE→tolerance / DROOLS→drools)。 */
    private static String evaluatorId(DiscrepancyRule rule) {
        return rule.evaluatorType().name().toLowerCase(Locale.ROOT);
    }
}
