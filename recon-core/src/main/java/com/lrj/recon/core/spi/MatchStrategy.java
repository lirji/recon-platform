package com.lrj.recon.core.spi;

import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.ReconRecord;

/**
 * 插件 2b) 匹配策略: 桶内 sort-merge + 1:N 聚合, 常量内存。两路输入已按 matchKey 升序 (由 DB/上游排序)。
 *
 * <p>MVP 实现: {@link com.lrj.recon.core.domain.service.GroupSumMatchStrategy}。
 */
public interface MatchStrategy {

    String strategyId();

    /** 归并左右两路 (已按 matchKey 升序), 每个键簇产一个 {@link MatchGroup} 到 sink。 */
    void join(MatchInput left, MatchInput right, MatchSink sink);

    /** 已排序的前向输入游标。 */
    interface MatchInput {
        /** 前进到下一条; 无更多返回 false。 */
        boolean advance();

        /** 当前记录 (advance 返回 true 后有效)。 */
        ReconRecord current();

        /** 当前记录的匹配键。 */
        MatchKey currentKey();
    }

    /** 匹配组接收器。 */
    interface MatchSink {
        void emit(MatchGroup group);
    }
}
