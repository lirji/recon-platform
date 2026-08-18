package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.domain.model.MatchGroup;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;

/**
 * Step2 matchEvaluateStep 的 reader (设计 §6): 包 {@link SegmentGroupCursor}, <b>以"整组 = 一 item"发射</b>,
 * 同一 match_key/group 组绝不被 chunk 边界切断。M2 单线程 (无 partition, partition 留 M3)。
 */
public class GroupReader implements ItemStreamReader<MatchGroup> {

    private final ReconRecordRepository records;
    private final String runId;
    private final String segmentId;

    private SegmentGroupCursor cursor;

    public GroupReader(ReconRecordRepository records, String runId, String segmentId) {
        this.records = records;
        this.runId = runId;
        this.segmentId = segmentId;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        this.cursor = new SegmentGroupCursor(records, runId, segmentId);
    }

    @Override
    public MatchGroup read() {
        return cursor == null ? null : cursor.next();
    }

    @Override
    public void close() {
        if (cursor != null) {
            cursor.close();
            cursor = null;
        }
    }
}
