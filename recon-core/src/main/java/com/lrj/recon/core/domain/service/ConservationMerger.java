package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.ConservationPartial;
import com.lrj.recon.core.domain.model.ReconReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 汇总步 (设计 §6 Step3 / §8): 把各 partition 落下的 {@link ConservationPartial} 局部结果按
 * {@code segmentId} 分组, 跨 bucket 用 {@link ConservationAccumulator#acceptPartial} 合并同 (segment,currency)
 * 子项, 复算出最终 {@link ReconReport} (双向 residual 判 balanced)。
 *
 * <p>因合并与 M2 双遍 {@link ConservationChecker} 共用 {@link ConservationAccumulator} 的求和/口径逻辑,
 * 且 {@code addExact} 满足结合律, 跨 bucket 汇总结果与"整段一次累计"<b>逐字段等价</b> —— 单遍守恒 == 双遍守恒。
 *
 * <p>纯函数, 零框架。跨币不相加 (currency 是 {@link ConservationAccumulator} 的分桶键)。
 */
public final class ConservationMerger {

    /** 合并一个 Run 的全部局部结果, 每个 (segment, currency) 桶产一份最终报表。 */
    public List<ReconReport> merge(String runId, Iterable<ConservationPartial> partials) {
        // 按 segmentId 分组 (保持首见顺序), 每段一个独立累加器 (跨该段所有 bucket 求和)。
        Map<String, ConservationAccumulator> bySegment = new LinkedHashMap<>();
        for (ConservationPartial p : partials) {
            bySegment.computeIfAbsent(p.segmentId(), s -> new ConservationAccumulator()).acceptPartial(p);
        }
        List<ReconReport> reports = new ArrayList<>();
        for (Map.Entry<String, ConservationAccumulator> e : bySegment.entrySet()) {
            reports.addAll(e.getValue().toReports(runId, e.getKey()));
        }
        return reports;
    }
}
