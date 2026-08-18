package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.spi.MatchStrategy.MatchSink;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 桶内 sort-merge 归并: 两路<b>已按 matchKey 升序</b>的记录流, 每个键簇产一个 {@link MatchGroup} 到 sink。
 *
 * <p>内存受<b>单键簇</b>约束 (排序由上游/DB 完成): {@code collectRun} 会物化当前 matchKey 的整簇记录,
 * 故为 O(最大键簇) 而非严格 O(1) —— 热点键会把整簇缓冲在堆上。左键 &lt; 右键 → LEFT_ONLY; 右键 &lt; 左键 → RIGHT_ONLY;
 * 相等 → BOTH。聚合 (signed 求和/计数/duplicate) 委托 {@link GroupAggregator}。
 */
public final class SortMergeJoiner {

    /** 归并两路已排序 Iterator。 */
    public void join(Iterator<ReconRecord> left, Iterator<ReconRecord> right, MatchSink sink) {
        Peeking l = new Peeking(left, "left");
        Peeking r = new Peeking(right, "right");

        while (l.hasNext() || r.hasNext()) {
            if (!r.hasNext()) {
                emit(sink, collectRun(l, l.peekKey()), List.of());
            } else if (!l.hasNext()) {
                emit(sink, List.of(), collectRun(r, r.peekKey()));
            } else {
                int cmp = l.peekKey().compareTo(r.peekKey());
                if (cmp < 0) {
                    emit(sink, collectRun(l, l.peekKey()), List.of());
                } else if (cmp > 0) {
                    emit(sink, List.of(), collectRun(r, r.peekKey()));
                } else {
                    MatchKey key = l.peekKey();
                    emit(sink, collectRun(l, key), collectRun(r, key));
                }
            }
        }
    }

    private void emit(MatchSink sink, List<ReconRecord> lefts, List<ReconRecord> rights) {
        ReconRecord any = !lefts.isEmpty() ? lefts.get(0) : rights.get(0);
        MatchKey matchKey = any.matchKey();
        GroupKey groupKey = any.groupKey();
        sink.emit(GroupAggregator.assemble(matchKey, groupKey, lefts, rights));
    }

    /** 收集 peeking 中所有与 key 相等的连续记录 (输入已排序, 故连续)。 */
    private List<ReconRecord> collectRun(Peeking it, MatchKey key) {
        List<ReconRecord> run = new ArrayList<>();
        while (it.hasNext() && it.peekKey().compareTo(key) == 0) {
            run.add(it.next());
        }
        return run;
    }

    /** 缓冲一条的前向迭代器, 并强制记录带 matchKey 且非降序。 */
    private static final class Peeking {
        private final Iterator<ReconRecord> delegate;
        private final String label;
        private ReconRecord buffer;
        private MatchKey prevKey;

        Peeking(Iterator<ReconRecord> delegate, String label) {
            this.delegate = delegate;
            this.label = label;
            advance();
        }

        boolean hasNext() {
            return buffer != null;
        }

        MatchKey peekKey() {
            return buffer.matchKey();
        }

        ReconRecord next() {
            if (buffer == null) {
                throw new NoSuchElementException();
            }
            ReconRecord out = buffer;
            advance();
            return out;
        }

        private void advance() {
            if (delegate.hasNext()) {
                ReconRecord r = delegate.next();
                if (r.matchKey() == null) {
                    throw new IllegalArgumentException(
                            label + " record has null matchKey; feed only keyed records to the joiner: " + r);
                }
                if (prevKey != null && prevKey.compareTo(r.matchKey()) > 0) {
                    throw new IllegalArgumentException(
                            label + " input not sorted ascending by matchKey: " + prevKey + " then " + r.matchKey());
                }
                prevKey = r.matchKey();
                buffer = r;
            } else {
                buffer = null;
            }
        }
    }
}
