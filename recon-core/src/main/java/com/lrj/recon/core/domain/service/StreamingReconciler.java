package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B8 · 流式对账内核(近实时,walking-skeleton)。逐条 {@link #accept} 记录做<b>增量按 match_key 累计</b>
 * (不全量 load),窗口结束时 {@link #flush} 复用<b>与批处理完全相同</b>的 {@link GroupAggregator} + {@link DiscrepancyClassifier}
 * 产出差异 —— 故流式结果与批处理逐条一致(同一分类内核,无第二套判差逻辑)。
 *
 * <p><b>诚实边界(基础设施属集成点,不在本仓库)</b>:真正的分布式流式运行时(Kafka 源 topic + Flink 作业 + exactly-once
 * 状态后端 + 事件时间/水位线/迟到处理)是可插拔集成层——由外部驱动 {@code accept()}(消费 topic)与按窗口 {@code flush()};
 * 本类只落地「流式=增量累计 + 同一分类内核」的领域核心(纯 Java、零框架、可测),证明批/流共享判差不变量。
 * 与批处理红线一致:{@link MatchGroup} 只持流式聚合(sum/count/presence),不物化记录列表。
 *
 * <p><b>null match_key</b>:与批一致逐条路由为单边组(用记录级鉴别量 rawRef 分桶,绝不并入他键),不进 SortMergeJoiner。
 */
public final class StreamingReconciler {

    private static final class Holder {
        private final MatchKey matchKey;      // null = null-key 单边组
        private final GroupKey groupKey;
        private final List<ReconRecord> lefts = new ArrayList<>();
        private final List<ReconRecord> rights = new ArrayList<>();

        Holder(MatchKey matchKey, GroupKey groupKey) {
            this.matchKey = matchKey;
            this.groupKey = groupKey;
        }
    }

    private final Map<String, Holder> byKey = new LinkedHashMap<>();
    private final DiscrepancyClassifier classifier = new DiscrepancyClassifier();

    /** 增量接收一条标准化记录(左/右侧),按 match_key 累计聚合状态。 */
    public void accept(ReconRecord record) {
        String key = record.matchKey() != null
                ? "K:" + record.matchKey().value()
                : "N:" + record.rawRef();   // null 键逐条独立成组(记录级鉴别),不并入他键
        Holder h = byKey.computeIfAbsent(key, k -> new Holder(record.matchKey(), record.groupKey()));
        if (record.side() == Side.LEFT) {
            h.lefts.add(record);
        } else {
            h.rights.add(record);
        }
    }

    /** 当前累计的组数(观测/背压用)。 */
    public int pendingGroups() {
        return byKey.size();
    }

    /**
     * 窗口结束:对累计的每组用批处理同一内核(GroupAggregator + DiscrepancyClassifier)判差,返回差异并清空状态。
     * 干净匹配组不进结果(与批一致)。
     */
    public List<Discrepancy> flush(EvaluationContext ctx) {
        List<Discrepancy> out = new ArrayList<>();
        for (Holder h : byKey.values()) {
            MatchGroup group = GroupAggregator.assemble(h.matchKey, h.groupKey, h.lefts, h.rights);
            Discrepancy d = classifier.classify(group, ctx);
            if (d != null) {
                out.add(d);
            }
        }
        byKey.clear();
        return out;
    }
}
