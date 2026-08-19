package com.lrj.recon.scenario.dsl;

import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.scenario.MarketingThreeWayScenario;
import com.lrj.recon.scenario.SegmentDef;

import java.util.List;

/**
 * B4 · 内置 MARKETING_3WAY 的**声明式种子定义**:把今天硬编码的营销三方场景表达为 {@link ScenarioDefinition} 数据,
 * 供配置存储 seed。当通用执行引擎(Phase 3b)就绪后,这份 seed 即成为可在管理台编辑的普通场景数据。
 *
 * <p>源描述符沿用 {@link MarketingThreeWayScenario} 的默认列约定(从其装配产物取回),角色/键字段用其公开常量声明;
 * `GenericScenarioAssembler.assemble(seed())` 与硬编码场景逐字段等价(由 parity 测试锁定)。
 */
public final class MarketingThreeWayDefinition {

    private MarketingThreeWayDefinition() {
    }

    /** 用默认表名/EXACT 规则构造种子定义(等价 {@code MarketingThreeWayScenario.of(Config.defaults())})。 */
    public static ScenarioDefinition seed() {
        MarketingThreeWayScenario ref = MarketingThreeWayScenario.of(MarketingThreeWayScenario.Config.defaults());
        return new ScenarioDefinition(MarketingThreeWayScenario.SCENARIO_CODE, List.of(
                segment(ref.seg1(), MarketingThreeWayScenario.SEG1,
                        SourceRole.MARKETING, SourceRole.ACCOUNTING, SourceRole.ACCOUNTING, "SEG1",
                        MarketingThreeWayScenario.FIELD_MARKETING_ISSUE_ID, MarketingThreeWayScenario.FIELD_ORDER_NO),
                segment(ref.seg2(), MarketingThreeWayScenario.SEG2,
                        SourceRole.ACCOUNTING, SourceRole.CHANNEL, SourceRole.ACCOUNTING, "SEG2",
                        MarketingThreeWayScenario.FIELD_CHANNEL_SERIAL_NO, MarketingThreeWayScenario.FIELD_CHANNEL_SERIAL_NO)));
    }

    private static ScenarioDefinition.Segment segment(SegmentDef ref, String id,
                                                      SourceRole left, SourceRole right, SourceRole spine,
                                                      String stageLabel, String matchKeyField, String groupKeyField) {
        DiscrepancyRule r = ref.rule();
        // enabledTypes=null 表示全开(与 DiscrepancyRule.exact() 一致);仅承载 evaluatorType + 阈值。
        ScenarioDefinition.Rule rule = new ScenarioDefinition.Rule(
                r.evaluatorType(), r.absToleranceMinor(), r.ratioToleranceBps(), null);
        return new ScenarioDefinition.Segment(id, left, right, spine, stageLabel, matchKeyField, groupKeyField,
                new ScenarioDefinition.Source(ref.leftSource().sourceType(), ref.leftSource().params()),
                new ScenarioDefinition.Source(ref.rightSource().sourceType(), ref.rightSource().params()),
                rule);
    }
}
