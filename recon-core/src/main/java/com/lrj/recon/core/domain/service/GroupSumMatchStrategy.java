package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.spi.MatchStrategy;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * MVP 匹配策略: 桶内 sort-merge 流式归并, 组内 signed 求和产 {@link com.lrj.recon.core.domain.model.MatchGroup}。
 *
 * <p>把 SPI 的 {@link MatchInput} 拉游标适配为已排序 Iterator, 委托 {@link SortMergeJoiner} 完成归并与聚合。
 * 常量内存, 只驻当前键簇的聚合量。
 */
public final class GroupSumMatchStrategy implements MatchStrategy {

    public static final String STRATEGY_ID = "group-sum";

    private final SortMergeJoiner joiner = new SortMergeJoiner();

    @Override
    public String strategyId() {
        return STRATEGY_ID;
    }

    @Override
    public void join(MatchInput left, MatchInput right, MatchSink sink) {
        joiner.join(asIterator(left), asIterator(right), sink);
    }

    private static Iterator<ReconRecord> asIterator(MatchInput input) {
        return new Iterator<>() {
            private boolean staged;
            private boolean hasMore;

            private void stage() {
                if (!staged) {
                    hasMore = input.advance();
                    staged = true;
                }
            }

            @Override
            public boolean hasNext() {
                stage();
                return hasMore;
            }

            @Override
            public ReconRecord next() {
                stage();
                if (!hasMore) {
                    throw new NoSuchElementException();
                }
                staged = false;
                return input.current();
            }
        };
    }
}
