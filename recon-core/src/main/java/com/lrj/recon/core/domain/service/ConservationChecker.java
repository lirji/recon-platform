package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.ReconReport;

import java.util.List;

/**
 * 构造性双向守恒 (设计 §8) —— <b>M2 双遍基线口径</b>: 整段分类结果一次性喂给
 * {@link ConservationAccumulator}, 每个 (segment, currency) 桶产一份 {@link ReconReport}。
 *
 * <p>M3 起真实作业改走<b>单遍</b>: 匹配判差时每 partition 用独立 {@link ConservationAccumulator} 流式累计,
 * 落 {@link com.lrj.recon.core.domain.model.ConservationPartial} 局部结果, 再由 {@link ConservationMerger}
 * 跨 bucket 合并。因单遍/汇总/双遍三者<b>共用同一累计引擎</b> {@link ConservationAccumulator}, 结果逐字段等价;
 * 本类保留作为<b>等价基线</b>与领域单测入口 (纯函数, 零框架)。
 *
 * <p>⚠️ residual≡0 是构造性恒等式, 只抓"桶路由被改坏 / {@link MoneyMath} 溢出", <b>不</b>证明
 * {@link DiscrepancyClassifier} 判定正确 (分类正确性由各差异桶数值断言 + 分类器自身测试保证)。
 */
public final class ConservationChecker {

    /** 对一段的分类结果做守恒, 每个 (segment, currency) 桶产一份 {@link ReconReport}。 */
    public List<ReconReport> check(String runId, String segmentId, Iterable<ClassifiedGroup> classifiedGroups) {
        ConservationAccumulator acc = new ConservationAccumulator();
        for (ClassifiedGroup cg : classifiedGroups) {
            acc.accept(cg);
        }
        return acc.toReports(runId, segmentId);
    }

    /** 便捷: 只有一个 (segment, currency) 桶时返回该桶 (多桶会抛错, 调用方应改用 {@link #check})。 */
    public ReconReport checkSingle(String runId, String segmentId, Iterable<ClassifiedGroup> classifiedGroups) {
        List<ReconReport> reports = check(runId, segmentId, classifiedGroups);
        if (reports.size() != 1) {
            throw new IllegalStateException("expected exactly one currency bucket, got " + reports.size());
        }
        return reports.get(0);
    }
}
