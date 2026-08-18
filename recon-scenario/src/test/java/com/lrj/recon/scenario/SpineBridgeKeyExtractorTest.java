package com.lrj.recon.scenario;

import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Money;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.SegmentSpec;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.Bucketing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SpineBridgeKeyExtractor} 单测 (M4): 据段抽键 (SEG1 refine issue→order, SEG2 identity serial);
 * 该侧无键 → null (交 null 相位); 装配期 refine fail-fast; 接线字段名自证。
 */
class SpineBridgeKeyExtractorTest {

    private static final SegmentSpec SEG1 = new SegmentSpec(
            MarketingThreeWayScenario.SEG1, SourceRole.MARKETING, SourceRole.ACCOUNTING, SourceRole.ACCOUNTING,
            "SEG1", SpineBridgeKeyExtractor.ID, "group-sum", "exact", List.of());
    private static final SegmentSpec SEG2 = new SegmentSpec(
            MarketingThreeWayScenario.SEG2, SourceRole.ACCOUNTING, SourceRole.CHANNEL, SourceRole.ACCOUNTING,
            "SEG2", SpineBridgeKeyExtractor.ID, "group-sum", "exact", List.of());

    private final SpineBridgeKeyExtractor extractor = new SpineBridgeKeyExtractor(List.of(
            new SpineBridgeKeyExtractor.KeySpec(MarketingThreeWayScenario.SEG1,
                    MarketingThreeWayScenario.FIELD_MARKETING_ISSUE_ID, MarketingThreeWayScenario.FIELD_ORDER_NO),
            new SpineBridgeKeyExtractor.KeySpec(MarketingThreeWayScenario.SEG2,
                    MarketingThreeWayScenario.FIELD_CHANNEL_SERIAL_NO, MarketingThreeWayScenario.FIELD_CHANNEL_SERIAL_NO)));

    private static ReconRecord record(String matchField, String matchValue, String groupField, String groupValue) {
        MatchKey mk = matchValue == null ? null : MatchKey.of(matchField, matchValue, 0);
        return ReconRecord.builder()
                .recordId("r").runId("run").segmentId("seg").side(Side.LEFT).sourceRole(SourceRole.ACCOUNTING)
                .matchKey(mk)
                .groupKey(groupValue == null ? null : GroupKey.of(groupField, groupValue))
                .bucket(0).money(Money.of("USD", 100)).entryType(EntryType.ISSUE).rawRef("t:r")
                .build();
    }

    @Test
    void seg1ExtractsIssueIdAsMatchAndOrderNoAsGroup() {
        ReconRecord r = record(MarketingThreeWayScenario.FIELD_MARKETING_ISSUE_ID, "ISSUE-1",
                MarketingThreeWayScenario.FIELD_ORDER_NO, "ORDER-9");
        MatchKey mk = extractor.extract(r, SEG1, 8);
        assertThat(mk).isNotNull();
        assertThat(mk.value()).isEqualTo("ISSUE-1");
        // 桶键 = group_key (发放单号), 非 match_key
        assertThat(mk.bucket()).isEqualTo(Bucketing.bucketOf("ORDER-9", 8));
        assertThat(extractor.groupKey(r, SEG1).value()).isEqualTo("ORDER-9");
    }

    @Test
    void seg1TwoIssuesOfSameOrderLandInSameBucket() {
        ReconRecord i1 = record(MarketingThreeWayScenario.FIELD_MARKETING_ISSUE_ID, "ISSUE-1",
                MarketingThreeWayScenario.FIELD_ORDER_NO, "ORDER-9");
        ReconRecord i2 = record(MarketingThreeWayScenario.FIELD_MARKETING_ISSUE_ID, "ISSUE-2",
                MarketingThreeWayScenario.FIELD_ORDER_NO, "ORDER-9");
        // 1:N 同发放单不同 issue → 同桶 (refine: 桶键=group)
        assertThat(extractor.extract(i1, SEG1, 16).bucket())
                .isEqualTo(extractor.extract(i2, SEG1, 16).bucket());
    }

    @Test
    void seg2IdentityChannelSerial() {
        ReconRecord r = record(MarketingThreeWayScenario.FIELD_CHANNEL_SERIAL_NO, "CH-77",
                MarketingThreeWayScenario.FIELD_CHANNEL_SERIAL_NO, "CH-77");
        MatchKey mk = extractor.extract(r, SEG2, 8);
        assertThat(mk.value()).isEqualTo("CH-77");
        assertThat(mk.bucket()).isEqualTo(Bucketing.bucketOf("CH-77", 8));
    }

    @Test
    void missingKeyColumnYieldsNullMatchKeyForNullPhaseRouting() {
        // spine 缺该段键 / 该侧无对应键 → match_key null (group 仍非空可分桶)
        ReconRecord r = record(MarketingThreeWayScenario.FIELD_MARKETING_ISSUE_ID, null,
                MarketingThreeWayScenario.FIELD_ORDER_NO, "ORDER-5");
        assertThat(extractor.extract(r, SEG1, 8)).isNull();
        assertThat(extractor.groupKey(r, SEG1).value()).isEqualTo("ORDER-5");
    }

    @Test
    void nullGroupKeyFailsFast() {
        ReconRecord r = record(MarketingThreeWayScenario.FIELD_MARKETING_ISSUE_ID, "ISSUE-1", "orderNo", null);
        assertThatThrownBy(() -> extractor.extract(r, SEG1, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("group_key");
    }

    @Test
    void wiringMismatchFailsFastWhenDescriptorProjectedWrongColumn() {
        // 适配器落库的 match 字段名与本段声明不符 (spine 投错键列) → fail-fast
        ReconRecord r = record("someOtherField", "X", MarketingThreeWayScenario.FIELD_ORDER_NO, "ORDER-9");
        assertThatThrownBy(() -> extractor.extract(r, SEG1, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wiring mismatch");
    }

    @Test
    void unconfiguredSegmentFailsFast() {
        SegmentSpec unknown = new SegmentSpec("SEG_X", SourceRole.MARKETING, SourceRole.ACCOUNTING, null,
                "SEGX", SpineBridgeKeyExtractor.ID, "group-sum", "exact", List.of());
        ReconRecord r = record(MarketingThreeWayScenario.FIELD_MARKETING_ISSUE_ID, "ISSUE-1",
                MarketingThreeWayScenario.FIELD_ORDER_NO, "ORDER-9");
        assertThatThrownBy(() -> extractor.extract(r, unknown, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void assemblyRefineFailFastWhenGroupFieldBlank() {
        // 装配期 refine fail-fast: 无 group 字段 → 无法分桶, refine 无定义
        assertThatThrownBy(() -> new SpineBridgeKeyExtractor.KeySpec("SEG_BAD", "issueId", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpineBridgeKeyExtractor.KeySpec("SEG_BAD", "issueId", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
