package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Presence;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.service.SortMergeJoiner;
import com.lrj.recon.core.spi.MatchStrategy;
import com.lrj.recon.core.spi.RecordCursor;

import java.util.ArrayList;
import java.util.List;

/**
 * 段级流式 sort-merge 游标 (设计 §6, "整组 = 一 item"): 两条<b>全桶</b>惰性游标 (LEFT/RIGHT, DB 已按
 * {@code match_key} 升序、null 键排最后) 归并, 每 {@link #next()} 产出<b>一个完整键簇的</b> {@link MatchGroup}。
 * 供 Step2 {@link GroupReader} 与 Step3 reportStep 共用同一套归并/聚合逻辑。
 *
 * <h4>隐患① 处理 (跨方言 NULL + 拒 null 键)</h4>
 * M0 {@link SortMergeJoiner} 拒绝 null match_key (会抛异常)。本游标据 DB 的可移植排序
 * {@code ORDER BY (match_key IS NULL), match_key} 把非空键簇排在前、null 键排在最后:
 * <ol>
 *   <li><b>keyed 相位</b>: 两侧头部都是非空键时正常 sort-merge, 每簇交给一次性 {@link SortMergeJoiner}
 *       (复用 M0 GroupAggregator 的 signed 求和/duplicate/presence 逻辑) → 保证 join 输入<b>无 null 键</b>;</li>
 *   <li><b>null 相位</b>: 头部键为 null 的记录<b>不进 join</b>, 逐条路由为单边组 (LEFT_ONLY→MISSING /
 *       RIGHT_ONLY→EXTRA, 无 spine 时), 手工装配 {@link MatchGroup} (匹配 M0 聚合语义)。</li>
 * </ol>
 * 常量内存: 只驻当前键簇 (排序由 DB 完成)。用完必须 {@link #close()}。
 */
public final class SegmentGroupCursor implements AutoCloseable {

    private final RecordCursor left;
    private final RecordCursor right;
    private final PeekCursor lp;
    private final PeekCursor rp;
    private final SortMergeJoiner joiner = new SortMergeJoiner();

    /**
     * 全桶归并 (M2 单线程): 段级两侧游标, null 键排最后 (可移植 NULL 排序)。
     *
     * <p>#3: 先开 LEFT, 再 try 开 RIGHT; RIGHT 打开抛异常时先关掉已开的 LEFT 再 rethrow, 不泄漏流式游标连接
     * (不能用构造器 {@code this(...)} 链, 否则参数在 try 外求值无法兜住 LEFT)。
     */
    public SegmentGroupCursor(ReconRecordRepository records, String runId, String segmentId) {
        RecordCursor l = records.cursorBySegmentSide(runId, segmentId, Side.LEFT);
        RecordCursor r;
        try {
            r = records.cursorBySegmentSide(runId, segmentId, Side.RIGHT);
        } catch (RuntimeException e) {
            l.close();
            throw e;
        }
        this.left = l;
        this.right = r;
        this.lp = new PeekCursor(l);
        this.rp = new PeekCursor(r);
    }

    /**
     * 通用归并 (M3 分桶并行的每 partition 用<b>单桶</b>两侧游标 {@code cursor(run,seg,side,bucket)}):
     * 直接注入已排序的 LEFT/RIGHT 游标, 归并/聚合/null 兜底逻辑与全桶路径完全一致。单桶游标同样用可移植的
     * {@code ORDER BY (match_key IS NULL), match_key} (null 键排最后)——refine 不变式<b>允许</b> null match_key
     * (M4 spine 缺记录侧), 由下方 null 相位统一路由出 join, 绝不喂给拒 null 的 {@link com.lrj.recon.core.domain.service.SortMergeJoiner}。
     */
    public SegmentGroupCursor(RecordCursor left, RecordCursor right) {
        this.left = left;
        this.right = right;
        this.lp = new PeekCursor(left);
        this.rp = new PeekCursor(right);
    }

    /** 下一个完整键簇的 {@link MatchGroup}; 两侧都读完返回 {@code null}。 */
    public MatchGroup next() {
        boolean leftKeyed = lp.hasNext() && lp.peek().matchKey() != null;
        boolean rightKeyed = rp.hasNext() && rp.peek().matchKey() != null;

        if (leftKeyed || rightKeyed) {
            if (leftKeyed && rightKeyed) {
                int cmp = lp.peek().matchKey().compareTo(rp.peek().matchKey());
                if (cmp < 0) {
                    return keyedGroup(collectRun(lp, lp.peek().matchKey()), List.of());
                } else if (cmp > 0) {
                    return keyedGroup(List.of(), collectRun(rp, rp.peek().matchKey()));
                }
                MatchKey key = lp.peek().matchKey();
                return keyedGroup(collectRun(lp, key), collectRun(rp, key));
            } else if (leftKeyed) {
                return keyedGroup(collectRun(lp, lp.peek().matchKey()), List.of());
            } else {
                return keyedGroup(List.of(), collectRun(rp, rp.peek().matchKey()));
            }
        }

        // null 相位: 剩余记录 (若有) 皆 null 键, 逐条路由为单边组, 绝不进 join。
        if (lp.hasNext()) {
            return nullKeySingle(lp.next(), Side.LEFT);
        }
        if (rp.hasNext()) {
            return nullKeySingle(rp.next(), Side.RIGHT);
        }
        return null;
    }

    /** 用一次性 sort-merge 装配单键簇的 MatchGroup (两侧同键或仅一侧), 复用 M0 GroupAggregator。 */
    private MatchGroup keyedGroup(List<ReconRecord> lefts, List<ReconRecord> rights) {
        CaptureSink sink = new CaptureSink();
        joiner.join(lefts.iterator(), rights.iterator(), sink);
        return sink.require();
    }

    /** 收集 peek 游标中所有与 key 相等的连续非空键记录 (输入已排序, 故连续)。 */
    private static List<ReconRecord> collectRun(PeekCursor pc, MatchKey key) {
        List<ReconRecord> run = new ArrayList<>();
        while (pc.hasNext() && pc.peek().matchKey() != null && pc.peek().matchKey().compareTo(key) == 0) {
            run.add(pc.next());
        }
        return run;
    }

    /** null match_key 记录 → 单边组 (手工装配, 匹配 M0 聚合语义): 无法 join, 天然缺对手侧。 */
    private static MatchGroup nullKeySingle(ReconRecord r, Side side) {
        MatchGroup.Builder b = MatchGroup.builder()
                .matchKey(null)
                .groupKey(r.groupKey())
                .duplicate(false);
        if (side == Side.LEFT) {
            b.presence(Presence.LEFT_ONLY).countLeft(1).countRight(0)
                    .sumSignedLeftMinor(r.signedAmountMinor())
                    .leftCurrency(r.currency())
                    .leftSampleRawRef(r.rawRef())
                    .leftBizStatus(r.bizStatus())
                    .leftPostingTime(r.postingTime());
        } else {
            b.presence(Presence.RIGHT_ONLY).countLeft(0).countRight(1)
                    .sumSignedRightMinor(r.signedAmountMinor())
                    .rightCurrency(r.currency())
                    .rightSampleRawRef(r.rawRef())
                    .rightBizStatus(r.bizStatus())
                    .rightPostingTime(r.postingTime());
        }
        return b.build();
    }

    @Override
    public void close() {
        try {
            left.close();
        } finally {
            right.close();
        }
    }

    /** 缓冲一条的前向游标包装 (惰性 peek)。 */
    private static final class PeekCursor {
        private final RecordCursor cursor;
        private ReconRecord buffer;
        private boolean loaded;

        PeekCursor(RecordCursor cursor) {
            this.cursor = cursor;
        }

        ReconRecord peek() {
            if (!loaded) {
                buffer = cursor.next();
                loaded = true;
            }
            return buffer;
        }

        boolean hasNext() {
            return peek() != null;
        }

        ReconRecord next() {
            ReconRecord r = peek();
            buffer = null;
            loaded = false;
            return r;
        }
    }

    /** 捕获一次性 join 恰好发射的单个组 (单键簇 → 单组)。 */
    private static final class CaptureSink implements MatchStrategy.MatchSink {
        private MatchGroup group;

        @Override
        public void emit(MatchGroup g) {
            if (group != null) {
                throw new IllegalStateException("single key cluster must emit exactly one group");
            }
            group = g;
        }

        MatchGroup require() {
            if (group == null) {
                throw new IllegalStateException("key cluster emitted no group");
            }
            return group;
        }
    }
}
