package com.lrj.recon.scenario.dsl;

import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EvaluatorType;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.spi.SourceDescriptor;
import com.lrj.recon.scenario.MarketingThreeWayScenario;
import com.lrj.recon.scenario.SegmentDef;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static com.lrj.recon.scenario.MarketingThreeWayScenario.FIELD_CHANNEL_SERIAL_NO;
import static com.lrj.recon.scenario.MarketingThreeWayScenario.FIELD_MARKETING_ISSUE_ID;
import static com.lrj.recon.scenario.MarketingThreeWayScenario.FIELD_ORDER_NO;
import static com.lrj.recon.scenario.MarketingThreeWayScenario.SEG1;
import static com.lrj.recon.scenario.MarketingThreeWayScenario.SEG2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B4 Phase 1 核心 parity: 用声明式 {@link ScenarioDefinition} 描述 MARKETING_3WAY,经 {@link GenericScenarioAssembler}
 * 通用装配后,逐字段等于硬编码 {@code MarketingThreeWayScenario} 的产物。证明「场景=数据 + 通用装配 ≡ 硬编码」,
 * 不改判差/守恒/refine。
 */
class GenericScenarioAssemblerTest {

    private static ScenarioDefinition.Source src(SourceDescriptor d) {
        return new ScenarioDefinition.Source(d.sourceType(), d.params());
    }

    /** 用硬编码场景的源描述符 + 独立声明的角色/键字段/规则,重建定义。 */
    private static ScenarioDefinition marketingDefinition(MarketingThreeWayScenario ref) {
        SegmentDef s1 = ref.seg1();
        SegmentDef s2 = ref.seg2();
        return new ScenarioDefinition("MARKETING_3WAY", List.of(
                new ScenarioDefinition.Segment(SEG1, SourceRole.MARKETING, SourceRole.ACCOUNTING, SourceRole.ACCOUNTING,
                        "SEG1", FIELD_MARKETING_ISSUE_ID, FIELD_ORDER_NO,
                        src(s1.leftSource()), src(s1.rightSource()), ScenarioDefinition.Rule.exact()),
                new ScenarioDefinition.Segment(SEG2, SourceRole.ACCOUNTING, SourceRole.CHANNEL, SourceRole.ACCOUNTING,
                        "SEG2", FIELD_CHANNEL_SERIAL_NO, FIELD_CHANNEL_SERIAL_NO,
                        src(s2.leftSource()), src(s2.rightSource()), ScenarioDefinition.Rule.exact())));
    }

    @Test
    void assembles_marketing_three_way_identically_to_hardcoded() {
        MarketingThreeWayScenario ref = MarketingThreeWayScenario.of(MarketingThreeWayScenario.Config.defaults());
        AssembledScenario asm = GenericScenarioAssembler.assemble(marketingDefinition(ref));

        assertThat(asm.code()).isEqualTo(MarketingThreeWayScenario.SCENARIO_CODE);
        assertThat(asm.segments()).hasSize(2);
        assertSegmentParity(asm.segment(0), ref.seg1());
        assertSegmentParity(asm.segment(1), ref.seg2());
        // 抽取器构造成功即通过 KeySpec 装配期 refine 校验; extractorId 已在 SegmentSpec parity 中覆盖。
        assertThat(asm.extractor().extractorId()).isEqualTo(ref.extractor().extractorId());
    }

    private static void assertSegmentParity(SegmentDef asm, SegmentDef ref) {
        // SegmentSpec 是 record: roles/stage/extractorId/strategyId/evaluatorId/handlerIds 全字段等价。
        assertThat(asm.spec()).isEqualTo(ref.spec());
        // SourceDescriptor 是 record: sourceType + 列映射参数等价。
        assertThat(asm.leftSource()).isEqualTo(ref.leftSource());
        assertThat(asm.rightSource()).isEqualTo(ref.rightSource());
        // DiscrepancyRule 无 equals, 逐字段比。
        assertThat(asm.rule().evaluatorType()).isEqualTo(ref.rule().evaluatorType());
        assertThat(asm.rule().absToleranceMinor()).isEqualTo(ref.rule().absToleranceMinor());
        assertThat(asm.rule().ratioToleranceBps()).isEqualTo(ref.rule().ratioToleranceBps());
        assertThat(asm.rule().enabled()).isEqualTo(ref.rule().enabled());
    }

    @Test
    void seed_definition_assembles_to_hardcoded_scenario() {
        MarketingThreeWayScenario ref = MarketingThreeWayScenario.of(MarketingThreeWayScenario.Config.defaults());
        AssembledScenario asm = GenericScenarioAssembler.assemble(MarketingThreeWayDefinition.seed());
        assertThat(asm.code()).isEqualTo(MarketingThreeWayScenario.SCENARIO_CODE);
        assertThat(asm.segments()).hasSize(2);
        assertSegmentParity(asm.segment(0), ref.seg1());
        assertSegmentParity(asm.segment(1), ref.seg2());
    }

    @Test
    void maps_tolerance_rule_and_evaluator_id() {
        MarketingThreeWayScenario ref = MarketingThreeWayScenario.of(MarketingThreeWayScenario.Config.defaults());
        ScenarioDefinition base = marketingDefinition(ref);
        ScenarioDefinition.Segment seg1 = base.segments().get(0);
        ScenarioDefinition.Segment tolerant = new ScenarioDefinition.Segment(
                seg1.id(), seg1.leftRole(), seg1.rightRole(), seg1.spineRole(), seg1.stageLabel(),
                seg1.matchKeyField(), seg1.groupKeyField(), seg1.left(), seg1.right(),
                new ScenarioDefinition.Rule(EvaluatorType.TOLERANCE, 200, 50, null));
        ScenarioDefinition def = new ScenarioDefinition("MARKETING_3WAY", List.of(tolerant, base.segments().get(1)));

        SegmentDef asm = GenericScenarioAssembler.assemble(def).segment(0);
        assertThat(asm.rule().evaluatorType()).isEqualTo(EvaluatorType.TOLERANCE);
        assertThat(asm.rule().absToleranceMinor()).isEqualTo(200);
        assertThat(asm.rule().ratioToleranceBps()).isEqualTo(50);
        assertThat(asm.spec().evaluatorId()).isEqualTo("tolerance");
    }

    @Test
    void enabled_type_subset_is_honored() {
        MarketingThreeWayScenario ref = MarketingThreeWayScenario.of(MarketingThreeWayScenario.Config.defaults());
        ScenarioDefinition base = marketingDefinition(ref);
        ScenarioDefinition.Segment s = base.segments().get(0);
        EnumSet<DiscrepancyType> onlyAmount = EnumSet.of(DiscrepancyType.AMOUNT_MISMATCH);
        ScenarioDefinition.Segment restricted = new ScenarioDefinition.Segment(
                s.id(), s.leftRole(), s.rightRole(), s.spineRole(), s.stageLabel(),
                s.matchKeyField(), s.groupKeyField(), s.left(), s.right(),
                new ScenarioDefinition.Rule(EvaluatorType.EXACT, 0, 0, onlyAmount));
        ScenarioDefinition def = new ScenarioDefinition("X", List.of(restricted));

        SegmentDef asm = GenericScenarioAssembler.assemble(def).segment(0);
        assertThat(asm.rule().enabled()).containsExactly(DiscrepancyType.AMOUNT_MISMATCH);
    }

    @Test
    void blank_group_key_field_fails_fast_via_keyspec() {
        assertThatThrownBy(() -> new ScenarioDefinition.Segment(
                "SEG", SourceRole.MARKETING, SourceRole.ACCOUNTING, SourceRole.ACCOUNTING, "SEG1",
                "issue", "  ", new ScenarioDefinition.Source("db", null),
                new ScenarioDefinition.Source("db", null), ScenarioDefinition.Rule.exact()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void empty_segments_fails_fast() {
        assertThatThrownBy(() -> new ScenarioDefinition("X", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
