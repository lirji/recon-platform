package com.lrj.recon.batch.job;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.service.ClassifiedGroup;

/**
 * matchEvaluate 的<b>逐组载荷</b> (M3): 一个匹配组 + 其判差结果 ({@code discrepancy == null} 为干净匹配)。
 *
 * <p>M2 的 processor 对干净匹配返回 {@code null} (被 Spring Batch 过滤掉), 守恒靠 report Step 二次全量重放。
 * M3 单遍守恒下, writer 需要看到<b>每一个</b>组 (匹配与否) 才能流式累计守恒, 故 processor 对所有组都发
 * {@link EvaluatedGroup}, 由 {@link MatchEvaluateWriter} 决定: 累计守恒 (全部组) + 仅对有差组 upsert discrepancy。
 */
public record EvaluatedGroup(MatchGroup group, Discrepancy discrepancy) {

    public boolean hasDiscrepancy() {
        return discrepancy != null;
    }

    /** 转成守恒累计输入 (type==null 即干净匹配)。 */
    public ClassifiedGroup toClassified() {
        return discrepancy == null ? ClassifiedGroup.matched(group) : ClassifiedGroup.of(group, discrepancy.type());
    }
}
