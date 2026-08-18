package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.domain.model.ReconRecord;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * Step1 loadStep 的 writer (设计 §6/§7): 每 chunk 一次 {@link ReconRecordRepository#batchInsert} 批插 staging,
 * 落在 Spring Batch 的 chunk 事务里 (断点续跑 checkpoint)。
 */
public class StagingRecordWriter implements ItemWriter<ReconRecord> {

    private final ReconRecordRepository records;

    public StagingRecordWriter(ReconRecordRepository records) {
        this.records = records;
    }

    @Override
    public void write(Chunk<? extends ReconRecord> chunk) {
        List<ReconRecord> items = new ArrayList<>(chunk.getItems());
        records.batchInsert(items);
    }
}
