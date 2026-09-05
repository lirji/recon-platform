package com.lrj.recon.batch.persistence;

import com.lrj.recon.batch.ods.BenefitOdsEvent;
import com.lrj.recon.batch.ods.BenefitOdsStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** 基于 JDBC 的权益 ODS 写适配器；表名只来自封闭 FactType 枚举，不接受外部输入。 */
@Repository
public class JdbcBenefitOdsStore implements BenefitOdsStore {
    private final JdbcTemplate jdbc;

    public JdbcBenefitOdsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 根据金额/数量口径分表写入，并保留三项跨系统关联键。 */
    @Override
    public void insert(BenefitOdsEvent event) {
        if (event.factType().monetary()) insertCash(event);
        else insertEntitlement(event);
    }

    private void insertCash(BenefitOdsEvent event) {
        String sql = "INSERT INTO " + event.factType().table() + " " + """
                (tenant_id,id,event_id,issue_id,order_no,channel_serial_no,ccy,amount_minor,entry_type,
                 biz_status,biz_time,posting_time,cell_id,shard_key,source_partition,source_offset,raw_ref,created_at,
                 expected_source_system,marketing_source_request_id,benefit_order_no)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        jdbc.update(sql, event.tenantId(), event.eventId(), event.eventId(), event.issueId(), event.orderNo(),
                event.channelSerialNo(), event.currency(), event.amountMinor(), event.entryType(),
                event.fulfillmentStatus(), Timestamp.from(event.occurredAt()), Timestamp.from(event.occurredAt()),
                event.cellId(), event.shardKey(), event.sourcePartition(), event.sourceOffset(), event.rawRef(),
                Timestamp.from(Instant.now()), event.expectedSourceSystem(), event.marketingSourceRequestId(),
                event.benefitOrderNo());
    }

    private void insertEntitlement(BenefitOdsEvent event) {
        String sql = "INSERT INTO " + event.factType().table() + " " + """
                (tenant_id,id,event_id,issue_id,sku_id,quantity,fulfillment_status,provider_ref,occurred_at,
                 cell_id,shard_key,source_partition,source_offset,raw_ref,created_at,
                 expected_source_system,marketing_source_request_id,benefit_order_no)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        jdbc.update(sql, event.tenantId(), event.eventId(), event.eventId(), event.issueId(), event.skuId(),
                event.quantity(), event.fulfillmentStatus(), event.providerRef(), Timestamp.from(event.occurredAt()),
                event.cellId(), event.shardKey(), event.sourcePartition(), event.sourceOffset(), event.rawRef(),
                Timestamp.from(Instant.now()), event.expectedSourceSystem(), event.marketingSourceRequestId(),
                event.benefitOrderNo());
    }
}
