package com.lrj.recon.scenario;

import com.lrj.recon.core.domain.model.SourceRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarketingThreeWayScenario} 装配单测 (M4): 两段顺序 + 角色/spine + 桥接抽取器 + 描述符投影键列。
 */
class MarketingThreeWayScenarioTest {

    private final MarketingThreeWayScenario scenario =
            MarketingThreeWayScenario.of(MarketingThreeWayScenario.Config.defaults());

    @Test
    void assemblesTwoSegmentsInOrderWithSpine() {
        List<SegmentDef> segs = scenario.segments();
        assertThat(segs).hasSize(2);

        SegmentDef seg1 = segs.get(0);
        assertThat(seg1.segmentId()).isEqualTo(MarketingThreeWayScenario.SEG1);
        assertThat(seg1.spec().leftRole()).isEqualTo(SourceRole.MARKETING);
        assertThat(seg1.spec().rightRole()).isEqualTo(SourceRole.ACCOUNTING);
        assertThat(seg1.spec().spineRole()).isEqualTo(SourceRole.ACCOUNTING);
        assertThat(seg1.spec().stageLabel()).isEqualTo("SEG1");

        SegmentDef seg2 = segs.get(1);
        assertThat(seg2.segmentId()).isEqualTo(MarketingThreeWayScenario.SEG2);
        assertThat(seg2.spec().leftRole()).isEqualTo(SourceRole.ACCOUNTING);
        assertThat(seg2.spec().rightRole()).isEqualTo(SourceRole.CHANNEL);
        assertThat(seg2.spec().spineRole()).isEqualTo(SourceRole.ACCOUNTING);
        assertThat(seg2.spec().stageLabel()).isEqualTo("SEG2");
    }

    @Test
    void sharedExtractorConfiguredForBothSegments() {
        assertThat(scenario.extractor().extractorId()).isEqualTo(SpineBridgeKeyExtractor.ID);
        assertThat(scenario.seg1().spec().extractorId()).isEqualTo(SpineBridgeKeyExtractor.ID);
        assertThat(scenario.seg2().spec().extractorId()).isEqualTo(SpineBridgeKeyExtractor.ID);
    }

    @Test
    void spineAccountingProjectsDifferentKeyColumnsPerSegment() {
        // SEG1 账务侧 (right) 投 issue_id; SEG2 账务侧 (left) 投 channel_serial_no —— spine 两读的关键。
        assertThat(scenario.seg1().rightSource().params()).containsEntry("matchKeyColumn", "issue_id");
        assertThat(scenario.seg1().rightSource().params()).containsEntry("groupKeyColumn", "order_no");
        assertThat(scenario.seg2().leftSource().params()).containsEntry("matchKeyColumn", "channel_serial_no");
        assertThat(scenario.seg2().leftSource().params()).containsEntry("groupKeyColumn", "channel_serial_no");
    }

    @Test
    void customTableNamesAreHonored() {
        MarketingThreeWayScenario custom = MarketingThreeWayScenario.of(new MarketingThreeWayScenario.Config(
                "mkt_t", "acct_t", "chan_t", null, null));
        assertThat(custom.seg1().leftSource().params()).containsEntry("table", "mkt_t");
        assertThat(custom.seg2().rightSource().params()).containsEntry("table", "chan_t");
    }
}
