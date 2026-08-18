package com.lrj.recon.batch.job;

import com.lrj.recon.batch.persistence.JdbcRecordRejectStore;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.spi.RecordCursor;
import com.lrj.recon.core.spi.SourceAdapter;
import com.lrj.recon.core.spi.SourceReadContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ExecutionContext;

import java.util.List;

/**
 * Step1 loadStep 的 reader (设计 §6): 顺序遍历若干 {@link SourceReadContext} (M2 单段 = LEFT/marketing 与
 * RIGHT/accounting 两侧), 逐侧用 {@link SourceAdapter} 打开惰性前向游标, 逐条产出原始标准化记录。
 *
 * <p><b>畸形行不中断</b>: 源适配器把标准化失败的行收进 {@link RecordCursor#rejects()} 而不作为记录返回;
 * 每侧游标耗尽时把该侧 rejects 落 {@code recon_record_reject} ({@link JdbcRecordRejectStore}), 整流不中断。
 * 键抽取/分桶在下游 {@link StandardizeProcessor} 完成 (本 reader 只负责拉取)。
 */
public class SourceAdapterItemReader implements ItemStreamReader<ReconRecord> {

    private final SourceAdapter adapter;
    private final JdbcRecordRejectStore rejectStore;
    private final List<SourceReadContext> contexts;

    private int contextIndex;
    private RecordCursor cursor;
    private SourceReadContext current;

    public SourceAdapterItemReader(SourceAdapter adapter, JdbcRecordRejectStore rejectStore,
                                   List<SourceReadContext> contexts) {
        this.adapter = adapter;
        this.rejectStore = rejectStore;
        this.contexts = contexts;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        this.contextIndex = 0;
        this.cursor = null;
        this.current = null;
    }

    @Override
    public ReconRecord read() {
        while (true) {
            if (cursor == null) {
                if (contextIndex >= contexts.size()) {
                    return null; // 所有侧读完
                }
                current = contexts.get(contextIndex++);
                cursor = adapter.open(current);
            }
            ReconRecord record = cursor.next();
            if (record != null) {
                return record;
            }
            // 当前侧耗尽: 落该侧 rejects, 关闭游标, 切下一侧
            flushRejects();
            closeCursorQuietly();
        }
    }

    @Override
    public void close() {
        flushRejects();
        closeCursorQuietly();
    }

    private void flushRejects() {
        if (cursor != null && current != null) {
            rejectStore.saveAll(current.runId(), current.segmentId(), current.sourceRole(), cursor.rejects());
        }
    }

    private void closeCursorQuietly() {
        if (cursor != null) {
            try {
                cursor.close();
            } catch (RuntimeException e) {
                throw new ItemStreamException("failed closing source cursor for " + current, e);
            } finally {
                cursor = null;
            }
        }
    }
}
