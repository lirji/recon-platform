package com.lrj.recon.scenario.dsl;

import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EvaluatorType;
import com.lrj.recon.core.domain.model.SourceRole;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * B4 · 声明式对账场景定义(DSL 模型,纯数据)。
 *
 * <p>把今天硬编码在 {@code MarketingThreeWayScenario.of(...)} 里的「拼装」表达为**可序列化的数据**:
 * 一个场景 = 有序若干 {@link Segment}(责任链顺序执行),每段声明角色/桥接 spine/键字段/左右源/判差规则。
 * {@link GenericScenarioAssembler} 通用地把它装配成 {@code SegmentDef} + {@code SpineBridgeKeyExtractor}
 * (与手工装配逐字段等价,parity 测试锁定)。JSON/YAML 解析与存储在组合根(recon-batch),本模块零框架。
 *
 * <p>安全关键不变量(fingerprint/守恒/refine)仍在 recon-core;DSL 只描述装配,不触判差算法。
 */
public record ScenarioDefinition(String code, List<Segment> segments) {

    public ScenarioDefinition {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("ScenarioDefinition.code must not be blank");
        }
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("ScenarioDefinition.segments must not be empty");
        }
        segments = List.copyOf(segments);
    }

    /** 责任链一段的声明。matchKeyField==groupKeyField 即 IDENTITY refine 特例。 */
    public record Segment(
            String id,
            SourceRole leftRole,
            SourceRole rightRole,
            SourceRole spineRole,
            String stageLabel,
            String matchKeyField,
            String groupKeyField,
            Source left,
            Source right,
            Rule rule) {

        public Segment {
            requireText("id", id);
            Objects.requireNonNull(leftRole, "leftRole");
            Objects.requireNonNull(rightRole, "rightRole");
            requireText("stageLabel", stageLabel);
            requireText("matchKeyField", matchKeyField);
            requireText("groupKeyField", groupKeyField);
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            rule = rule == null ? Rule.exact() : rule;
        }
    }

    /** 格式无关的源投影声明(sourceType=db/csv-file… + 列映射参数),对应 {@code SourceDescriptor}。 */
    public record Source(String sourceType, Map<String, String> params) {
        public Source {
            requireText("sourceType", sourceType);
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** 判差规则声明,可 {@link #toDiscrepancyRule()} 成 recon-core 的 {@link DiscrepancyRule}。 */
    public record Rule(EvaluatorType evaluatorType, long absToleranceMinor, int ratioToleranceBps,
                       Set<DiscrepancyType> enabledTypes) {

        public Rule {
            evaluatorType = evaluatorType == null ? EvaluatorType.EXACT : evaluatorType;
            // Set.copyOf (非 EnumSet.copyOf): 后者对空集合抛异常, 而空 enabledTypes (全部禁用) 是合法输入。
            enabledTypes = enabledTypes == null ? null : Set.copyOf(enabledTypes);
        }

        public static Rule exact() {
            return new Rule(EvaluatorType.EXACT, 0, 0, null);
        }

        public DiscrepancyRule toDiscrepancyRule() {
            DiscrepancyRule.Builder b = DiscrepancyRule.builder()
                    .evaluatorType(evaluatorType)
                    .absToleranceMinor(absToleranceMinor)
                    .ratioToleranceBps(ratioToleranceBps);
            if (enabledTypes != null) {
                b.enabled(enabledTypes);
            }
            return b.build();
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ScenarioDefinition." + name + " must not be blank");
        }
    }
}
