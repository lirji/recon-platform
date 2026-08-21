package com.lrj.recon.batch.persistence;

import com.lrj.recon.batch.ods.BenefitOdsEvent;
import com.lrj.recon.batch.ods.BenefitOdsStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class JdbcBenefitOdsStore implements BenefitOdsStore {
    private final JdbcTemplate jdbc;

    public JdbcBenefitOdsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(BenefitOdsEvent event) {
        if (event.factType().monetary()) insertCash(event);
        else insertEntitlement(event);
    }

    private void insertCash(BenefitOdsEvent event) {
        String sql = "INSERT INTO " + event.factType().table() + " " + """
                (tenant_id,id,event_id,issue_id,order_no,channel_serial_no,ccy,amount_minor,entry_type,
                 biz_status,biz_time,posting_time,cell_id,shard_key,source_partition,source_offset,raw_ref,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        jdbc.update(sql, event.tenantId(), event.eventId(), event.eventId(), event.issueId(), event.orderNo(),
                event.channelSerialNo(), event.currency(), event.amountMinor(), event.entryType(),
                event.fulfillmentStatus(), Timestamp.from(event.occurredAt()), Timestamp.from(event.occurredAt()),
                event.cellId(), event.shardKey(), event.sourcePartition(), event.sourceOffset(), event.rawRef(),
                Timestamp.from(Instant.now()));
    }

    private void insertEntitlement(BenefitOdsEvent event) {
        String sql = "INSERT INTO " + event.factType().table() + " " + """
                (tenant_id,id,event_id,issue_id,sku_id,quantity,fulfillment_status,provider_ref,occurred_at,
                 cell_id,shard_key,source_partition,source_offset,raw_ref,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        jdbc.update(sql, event.tenantId(), event.eventId(), event.eventId(), event.issueId(), event.skuId(),
                event.quantity(), event.fulfillmentStatus(), event.providerRef(), Timestamp.from(event.occurredAt()),
                event.cellId(), event.shardKey(), event.sourcePartition(), event.sourceOffset(), event.rawRef(),
                Timestamp.from(Instant.now()));
    }
}
