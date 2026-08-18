package com.lrj.recon.batch.config;

import com.lrj.recon.batch.job.IdentityKeyExtractor;
import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.SegmentSpec;
import com.lrj.recon.core.domain.service.ExactEvaluator;
import com.lrj.recon.core.domain.service.GroupSumMatchStrategy;
import com.lrj.recon.core.spi.KeyExtractor;
import com.lrj.recon.core.spi.SourceDescriptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M2 单段装配 (marketing↔accounting): 构造 {@link SegmentPlan} + {@link KeyExtractor}, 并在装配期
 * <b>fail-fast 校验 refine 不变式</b> (修补①): M2 仅支持 IDENTITY refine (match_key == group_key),
 * 若装配的抽取器不保证该关系则拒绝启动。两段桥接责任链 (SEG1+SEG2) + SpineBridgeKeyExtractor 归 M4。
 *
 * <p>源表/列可经 {@code recon.m2.*} 覆盖 (默认对齐集成测试建的 {@code recon_src_marketing/accounting} 表)。
 */
@Configuration
public class ReconM2Config {

    @Bean
    public KeyExtractor identityKeyExtractor() {
        return new IdentityKeyExtractor();
    }

    @Bean
    public SegmentPlan segmentPlan(
            KeyExtractor identityKeyExtractor,
            @Value("${recon.m2.segment-id:SEG1_MKT_ACCT}") String segmentId,
            @Value("${recon.m2.stage-label:SEG1}") String stageLabel,
            @Value("${recon.m2.left-table:recon_src_marketing}") String leftTable,
            @Value("${recon.m2.right-table:recon_src_accounting}") String rightTable) {

        SegmentSpec spec = new SegmentSpec(
                segmentId,
                com.lrj.recon.core.domain.model.SourceRole.MARKETING,
                com.lrj.recon.core.domain.model.SourceRole.ACCOUNTING,
                null,                       // M2 单段无 spine (BRIDGE_BROKEN 归 M4)
                stageLabel,
                IdentityKeyExtractor.ID,
                GroupSumMatchStrategy.STRATEGY_ID,
                ExactEvaluator.EVALUATOR_ID,
                List.of());

        SegmentPlan plan = new SegmentPlan(
                spec,
                dbSource(leftTable),
                dbSource(rightTable),
                identityKeyExtractor,
                DiscrepancyRule.exact());

        validateRefineInvariant(plan); // 修补①: 启动期不满足 refine 即 fail-fast
        return plan;
    }

    /**
     * 修补① 启动校验: M2 只支持 IDENTITY refine (match_key == group_key)。若抽取器不是身份抽取器
     * (无法保证 match_key == group_key), 则装配失败 —— 防止 GROUP_SUM 聚合与 join 落入不同桶。
     */
    private static void validateRefineInvariant(SegmentPlan plan) {
        if (!IdentityKeyExtractor.ID.equals(plan.extractor().extractorId())) {
            throw new IllegalStateException(
                    "M2 requires IDENTITY refine (match_key == group_key); extractor '"
                            + plan.extractor().extractorId() + "' does not guarantee it. "
                            + "Non-identity refine (coarse group_key) is planned for M4.");
        }
    }

    /** 构造 db 源描述符 (列约定与集成测试建表一致); 不映射 matchKeyColumn → 由 StandardizeProcessor 抽键。 */
    private static SourceDescriptor dbSource(String table) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("table", table);
        params.put("idColumn", "id");
        params.put("groupKeyColumn", "issue_id");
        params.put("groupKeyField", "issueId");
        params.put("currencyColumn", "ccy");
        params.put("amountColumn", "amount_minor");
        params.put("entryTypeColumn", "entry_type");
        params.put("bizStatusColumn", "biz_status");
        params.put("bizTimeColumn", "biz_time");
        params.put("postingTimeColumn", "posting_time");
        return new SourceDescriptor("db", params);
    }
}
