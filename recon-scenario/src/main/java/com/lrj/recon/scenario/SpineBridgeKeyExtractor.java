package com.lrj.recon.scenario;

import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.SegmentSpec;
import com.lrj.recon.core.domain.service.Bucketing;
import com.lrj.recon.core.spi.KeyExtractor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桥接键抽取器 (M4, 设计 §4/ADR-4): 账务 spine 侧同时持有 SEG1 的营销发放ID 与 SEG2 的渠道流水号,
 * 本抽取器<b>据段 (segmentId)</b> 抽取该段的勾兑键与 1:N 聚合键。
 *
 * <h4>桥接双键与 spine 两读</h4>
 * spine (账务) 在 SEG1 作右侧、SEG2 作左侧, 两段用<b>不同的源描述符投影不同的键列</b> (SEG1 投 issue_id,
 * SEG2 投 channel_serial_no), 故适配器落到 {@link ReconRecord} 上的 {@code matchKey} 已是本段该侧的正确键
 * (营销侧只有营销发放ID、渠道侧只有渠道流水号, spine 两读各取其一)。本抽取器:
 * <ul>
 *   <li>{@link #extract}: 取 {@code record.matchKey()} (本段列)。<b>该侧无对应键 (列值 null) → 返回 null</b>,
 *       交 null 相位路由为单边组 (产 MISSING/EXTRA 或触发 BRIDGE_BROKEN), 绝不喂给拒 null 的 SortMergeJoiner;</li>
 *   <li>{@link #groupKey}: 取 {@code record.groupKey()} —— SEG1 = 发放单号 (1:N, 与 match_key 不同,
 *       放宽 refine); SEG2 = 渠道流水号 (与 match_key 相同, IDENTITY 特例)。桶键恒 = group_key。</li>
 * </ul>
 *
 * <h4>装配期 refine fail-fast (仅键<b>字段名</b>接线, 非数据函数性)</h4>
 * 每段以 {@link KeySpec} 声明 {@code (matchKeyField, groupKeyField)}; 构造期校验字段名非空 (无 group 字段则
 * 无法分桶、refine 无定义 → 拒绝装配)。运行期 {@link #extract} 再自证适配器落库的字段名与声明一致
 * (描述符与场景脱节即 fail-fast), 杜绝 spine 两读投错键列的静默错配。纯 Java 零框架。
 *
 * <p>⚠️ <b>局限 (KI-6)</b>: 上述校验只覆盖<b>键字段名接线</b> (声明非空 + 落库字段名一致), <b>不</b>校验<b>数据函数性</b>
 * ——"同一 match_key 跨两侧是否映射到同一 group_key"。那需跨记录全表 match→group 映射, 不在千万级热路径做。若脏数据
 * 违反函数性, 同一 match_key 的左右两侧会落<b>不同桶</b> → 产<b>假 BRIDGE_BROKEN / 假 EXTRA</b>, 且左右额独立入账使
 * 守恒仍闭合、抓不到。规避靠上游数据质量; 可选离线/装配期<b>抽样</b>预校验见
 * {@link Bucketing#assertRefineFunction} (需跨记录状态, 显式 opt-in, 不进热路径)。
 */
public final class SpineBridgeKeyExtractor implements KeyExtractor {

    public static final String ID = "spine-bridge";

    /**
     * 一段的键声明: {@code matchKeyField} = 勾兑键字段名 (营销发放ID / 渠道流水号),
     * {@code groupKeyField} = 1:N 聚合键字段名 (发放单号 / 渠道流水号)。
     * {@code matchKeyField == groupKeyField} 即 IDENTITY 特例 (SEG2)。
     */
    public record KeySpec(String segmentId, String matchKeyField, String groupKeyField) {
        public KeySpec {
            requireField("segmentId", segmentId);
            requireField("matchKeyField", matchKeyField);
            // 装配期 refine fail-fast: 分桶键 = group_key, 缺 group 字段则 refine 无定义、无法分桶。
            requireField("groupKeyField", groupKeyField);
        }

        /** IDENTITY refine 特例 (match == group)。 */
        public boolean identity() {
            return matchKeyField.equals(groupKeyField);
        }

        private static void requireField(String name, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "SpineBridgeKeyExtractor KeySpec." + name + " must not be blank (refine invariant needs它)");
            }
        }
    }

    private final Map<String, KeySpec> specsBySegment;

    public SpineBridgeKeyExtractor(List<KeySpec> specs) {
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("SpineBridgeKeyExtractor requires at least one segment KeySpec");
        }
        Map<String, KeySpec> m = new LinkedHashMap<>();
        for (KeySpec s : specs) {
            if (m.put(s.segmentId(), s) != null) {
                throw new IllegalArgumentException("duplicate KeySpec for segment " + s.segmentId());
            }
        }
        this.specsBySegment = Map.copyOf(m);
    }

    @Override
    public String extractorId() {
        return ID;
    }

    @Override
    public MatchKey extract(ReconRecord record, SegmentSpec segment, int bucketCount) {
        KeySpec spec = specFor(segment);

        GroupKey gk = record.groupKey();
        if (gk == null) {
            throw new IllegalStateException(
                    "spine-bridge segment " + spec.segmentId() + " requires non-null group_key for bucketing: " + record);
        }
        assertField("group_key", spec.segmentId(), spec.groupKeyField(), gk.fieldName());

        MatchKey mk = record.matchKey();
        if (mk == null) {
            // 该侧无对应键 (spine 缺该段键 / 记录该键列 null) → null 相位路由为单边组, 不进 join。
            return null;
        }
        assertField("match_key", spec.segmentId(), spec.matchKeyField(), mk.fieldName());

        // 桶键 = group_key (放宽 refine 下 match 可 != group); StandardizeProcessor 会以 group 再算同一桶。
        int bucket = Bucketing.bucketOf(gk.value(), bucketCount);
        return MatchKey.of(mk.fieldName(), mk.value(), bucket);
    }

    @Override
    public GroupKey groupKey(ReconRecord record, SegmentSpec segment) {
        specFor(segment); // 校验段已配置 (未配置即接线错误)
        return record.groupKey();
    }

    private KeySpec specFor(SegmentSpec segment) {
        KeySpec s = specsBySegment.get(segment.segmentId());
        if (s == null) {
            throw new IllegalStateException(
                    "SpineBridgeKeyExtractor not configured for segment " + segment.segmentId());
        }
        return s;
    }

    /** 接线自证: 适配器落库的键字段名须与本段声明一致, 否则 spine 两读投错了键列 → fail-fast。 */
    private static void assertField(String which, String segmentId, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "spine-bridge wiring mismatch on " + which + " for segment " + segmentId
                            + ": expected field '" + expected + "' but record carried '" + actual
                            + "' (source descriptor projected the wrong column?)");
        }
    }
}
