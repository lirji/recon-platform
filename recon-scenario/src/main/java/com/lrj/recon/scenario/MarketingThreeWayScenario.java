package com.lrj.recon.scenario;

import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.SegmentSpec;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.GroupSumMatchStrategy;
import com.lrj.recon.core.spi.SourceDescriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 营销发钱三方对账场景装配 (M4, 设计 §1/§6/ADR-1/ADR-4): 责任链<b>两段顺序执行</b>
 * {@code SEG1 营销↔账务 → SEG2 账务↔渠道}, 账务 (ACCOUNTING) 是两段共同的桥接 spine。
 *
 * <ul>
 *   <li><b>SEG1_MKT_ACCT</b>: left=MARKETING, right=ACCOUNTING, spine=ACCOUNTING。
 *       match_key = 营销发放ID, group_key = <b>发放单号</b> (1:N, match != group, <b>放宽 refine</b>);
 *       账务缺某发放ID → BRIDGE_BROKEN(stage=SEG1)。</li>
 *   <li><b>SEG2_ACCT_CHANNEL</b>: left=ACCOUNTING, right=CHANNEL, spine=ACCOUNTING。
 *       match_key = group_key = 渠道流水号 (IDENTITY, 渠道侧只有流水号);
 *       账务缺某渠道流水号 → BRIDGE_BROKEN(stage=SEG2)。</li>
 * </ul>
 * 两段各自独立跑 load→matchEvaluate→report、各自独立守恒产差 (无单一三方合并视图, 归阶段二 roll-up)。
 * 共享一个 {@link SpineBridgeKeyExtractor} (据段抽键); spine 账务在两段用<b>不同描述符投影不同键列</b>
 * (SEG1 投 issue_id, SEG2 投 channel_serial_no)。纯 Java 零框架, 只依赖 recon-core。
 */
public final class MarketingThreeWayScenario {

    public static final String SCENARIO_CODE = "MARKETING_3WAY";
    public static final String SEG1 = "SEG1_MKT_ACCT";
    public static final String SEG2 = "SEG2_ACCT_CHANNEL";

    // 键字段名 (领域字段, 与源列解耦; 见 Config 的列映射)
    public static final String FIELD_MARKETING_ISSUE_ID = "marketingIssueId";
    public static final String FIELD_ORDER_NO = "orderNo";
    public static final String FIELD_CHANNEL_SERIAL_NO = "channelSerialNo";

    private final List<SegmentDef> segments;
    private final SpineBridgeKeyExtractor extractor;

    private MarketingThreeWayScenario(List<SegmentDef> segments, SpineBridgeKeyExtractor extractor) {
        this.segments = List.copyOf(segments);
        this.extractor = extractor;
    }

    /** 顺序执行的两段 (SEG1 → SEG2)。 */
    public List<SegmentDef> segments() {
        return segments;
    }

    /** 共享桥接抽取器 (据段抽键)。 */
    public SpineBridgeKeyExtractor extractor() {
        return extractor;
    }

    public SegmentDef seg1() {
        return segments.get(0);
    }

    public SegmentDef seg2() {
        return segments.get(1);
    }

    /**
     * 用默认列约定 + 精确判差装配三方场景。表名可由 {@link Config} 覆盖; 判差规则默认 EXACT (可传 TOLERANCE 规则)。
     */
    public static MarketingThreeWayScenario of(Config cfg) {
        Objects.requireNonNull(cfg, "cfg");

        return of(new SourceConfig(
                // SEG1 营销侧: match=issue_id, group=order_no (放宽 refine)
                marketingLikeDescriptor(cfg.marketingTable(), "issue_id", FIELD_MARKETING_ISSUE_ID,
                        "order_no", FIELD_ORDER_NO),
                // SEG1 账务侧 (spine 投 issue_id): match=issue_id, group=order_no
                marketingLikeDescriptor(cfg.accountingTable(), "issue_id", FIELD_MARKETING_ISSUE_ID,
                        "order_no", FIELD_ORDER_NO),
                // SEG2 账务侧 (spine 投 channel_serial_no): match=group=channel_serial_no
                marketingLikeDescriptor(cfg.accountingTable(), "channel_serial_no", FIELD_CHANNEL_SERIAL_NO,
                        "channel_serial_no", FIELD_CHANNEL_SERIAL_NO),
                // SEG2 渠道侧: match=group=channel_serial_no
                marketingLikeDescriptor(cfg.channelTable(), "channel_serial_no", FIELD_CHANNEL_SERIAL_NO,
                        "channel_serial_no", FIELD_CHANNEL_SERIAL_NO),
                cfg.seg1Rule(), cfg.seg2Rule()));
    }

    /**
     * 用四个已投影的数据源描述符装配场景。数据源格式由组合根决定，因此 scenario 模块无需依赖 DB/CSV 实现；
     * 账务 spine 显式传入两份描述符，以便 SEG1/SEG2 从同一源投影不同键列。
     */
    public static MarketingThreeWayScenario of(SourceConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");

        // 装配期 refine 校验在 KeySpec 构造内 (字段非空 = 可分桶): SEG1 refine (issue→order), SEG2 identity (serial)。
        SpineBridgeKeyExtractor extractor = new SpineBridgeKeyExtractor(List.of(
                new SpineBridgeKeyExtractor.KeySpec(SEG1, FIELD_MARKETING_ISSUE_ID, FIELD_ORDER_NO),
                new SpineBridgeKeyExtractor.KeySpec(SEG2, FIELD_CHANNEL_SERIAL_NO, FIELD_CHANNEL_SERIAL_NO)));

        SegmentSpec seg1Spec = new SegmentSpec(
                SEG1, SourceRole.MARKETING, SourceRole.ACCOUNTING, SourceRole.ACCOUNTING, "SEG1",
                SpineBridgeKeyExtractor.ID, GroupSumMatchStrategy.STRATEGY_ID,
                evaluatorId(cfg.seg1Rule()), List.of());
        SegmentDef seg1 = new SegmentDef(seg1Spec,
                cfg.marketingSeg1(), cfg.accountingSeg1(),
                cfg.seg1Rule());

        SegmentSpec seg2Spec = new SegmentSpec(
                SEG2, SourceRole.ACCOUNTING, SourceRole.CHANNEL, SourceRole.ACCOUNTING, "SEG2",
                SpineBridgeKeyExtractor.ID, GroupSumMatchStrategy.STRATEGY_ID,
                evaluatorId(cfg.seg2Rule()), List.of());
        SegmentDef seg2 = new SegmentDef(seg2Spec,
                cfg.accountingSeg2(), cfg.channelSeg2(),
                cfg.seg2Rule());

        return new MarketingThreeWayScenario(List.of(seg1, seg2), extractor);
    }

    private static String evaluatorId(DiscrepancyRule rule) {
        // 直接用枚举名 (EXACT/TOLERANCE/DROOLS → exact/tolerance/drools), 与各 Evaluator.EVALUATOR_ID 对齐,
        // 不再把 DROOLS 误标成 "exact" (运行期判差器仍由 EvaluatorFactory 按 evaluatorType 解析, DROOLS 会 fail-fast)。
        return rule.evaluatorType().name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 构造 db 源描述符 (列约定: id 主键 + 标准化列)。matchKeyColumn/groupKeyColumn 按段/侧投影不同键列
     * (spine 两读的关键)。列名固定约定, 表名由场景配置传入。
     */
    private static SourceDescriptor marketingLikeDescriptor(String table,
                                                            String matchKeyColumn, String matchKeyField,
                                                            String groupKeyColumn, String groupKeyField) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("table", table);
        p.put("idColumn", "id");
        p.put("matchKeyColumn", matchKeyColumn);
        p.put("matchKeyField", matchKeyField);
        p.put("groupKeyColumn", groupKeyColumn);
        p.put("groupKeyField", groupKeyField);
        p.put("currencyColumn", "ccy");
        p.put("amountColumn", "amount_minor");
        p.put("entryTypeColumn", "entry_type");
        p.put("bizStatusColumn", "biz_status");
        p.put("bizTimeColumn", "biz_time");
        p.put("postingTimeColumn", "posting_time");
        return new SourceDescriptor("db", p);
    }

    /**
     * 场景配置: 三张源表名 + 两段判差规则。规则默认 EXACT; 传 TOLERANCE 规则即让该段走容差判差。
     */
    public record Config(
            String marketingTable,
            String accountingTable,
            String channelTable,
            DiscrepancyRule seg1Rule,
            DiscrepancyRule seg2Rule) {

        public Config {
            requireTable("marketingTable", marketingTable);
            requireTable("accountingTable", accountingTable);
            requireTable("channelTable", channelTable);
            seg1Rule = seg1Rule == null ? DiscrepancyRule.exact() : seg1Rule;
            seg2Rule = seg2Rule == null ? DiscrepancyRule.exact() : seg2Rule;
        }

        /** 默认表名 (对齐集成测试建的 recon_src_* 表) + 双段 EXACT。 */
        public static Config defaults() {
            return new Config("recon_src_marketing", "recon_src_accounting", "recon_src_channel",
                    DiscrepancyRule.exact(), DiscrepancyRule.exact());
        }

        private static void requireTable(String name, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("MarketingThreeWayScenario.Config." + name + " must not be blank");
            }
        }
    }

    /** 格式无关的数据源投影配置；由 recon-batch 组合根构造 DB 或 CSV 描述符。 */
    public record SourceConfig(
            SourceDescriptor marketingSeg1,
            SourceDescriptor accountingSeg1,
            SourceDescriptor accountingSeg2,
            SourceDescriptor channelSeg2,
            DiscrepancyRule seg1Rule,
            DiscrepancyRule seg2Rule) {

        public SourceConfig {
            requireDescriptor("marketingSeg1", marketingSeg1);
            requireDescriptor("accountingSeg1", accountingSeg1);
            requireDescriptor("accountingSeg2", accountingSeg2);
            requireDescriptor("channelSeg2", channelSeg2);
            seg1Rule = seg1Rule == null ? DiscrepancyRule.exact() : seg1Rule;
            seg2Rule = seg2Rule == null ? DiscrepancyRule.exact() : seg2Rule;
        }

        private static void requireDescriptor(String name, SourceDescriptor descriptor) {
            if (descriptor == null || descriptor.sourceType() == null || descriptor.sourceType().isBlank()) {
                throw new IllegalArgumentException("MarketingThreeWayScenario.SourceConfig." + name
                        + " must have a sourceType");
            }
        }
    }
}
