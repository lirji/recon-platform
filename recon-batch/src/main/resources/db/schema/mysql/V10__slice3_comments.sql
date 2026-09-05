ALTER TABLE recon_run MODIFY tenant_id VARCHAR(64) NOT NULL DEFAULT 'legacy' COMMENT '业务租户；权益 ODS 扫描的强制隔离键';
ALTER TABLE discrepancy
  MODIFY type VARCHAR(32) NOT NULL COMMENT '差异类型（含非金额权益分类）',
  MODIFY measure_kind VARCHAR(16) NOT NULL DEFAULT 'MONEY' COMMENT '度量类型：MONEY 或 QUANTITY',
  MODIFY expected_quantity BIGINT NULL COMMENT '非金额权益应发数量',
  MODIFY internal_quantity BIGINT NULL COMMENT '非金额权益内部发放数量',
  MODIFY provider_quantity BIGINT NULL COMMENT '非金额权益供应商履约数量',
  MODIFY expected_source_system VARCHAR(64) NULL COMMENT '应发事实来源系统',
  MODIFY marketing_source_request_id VARCHAR(128) NULL COMMENT '营销跨系统幂等请求号',
  MODIFY benefit_order_no VARCHAR(64) NULL COMMENT '权益订单号';
ALTER TABLE recon_ods_cash_expected
  MODIFY order_no VARCHAR(64) NULL COMMENT '权益订单号；应发阶段可为空',
  MODIFY expected_source_system VARCHAR(64) NULL COMMENT '应发事实来源系统',
  MODIFY marketing_source_request_id VARCHAR(128) NULL COMMENT '营销跨系统幂等请求号',
  MODIFY benefit_order_no VARCHAR(64) NULL COMMENT '权益订单号；应发阶段可为空';
ALTER TABLE recon_ods_cash_accounting
  MODIFY expected_source_system VARCHAR(64) NULL COMMENT '应发事实来源系统',
  MODIFY marketing_source_request_id VARCHAR(128) NULL COMMENT '营销跨系统幂等请求号',
  MODIFY benefit_order_no VARCHAR(64) NULL COMMENT '权益订单号';
ALTER TABLE recon_ods_cash_channel
  MODIFY expected_source_system VARCHAR(64) NULL COMMENT '应发事实来源系统',
  MODIFY marketing_source_request_id VARCHAR(128) NULL COMMENT '营销跨系统幂等请求号',
  MODIFY benefit_order_no VARCHAR(64) NULL COMMENT '权益订单号';
ALTER TABLE recon_ods_entitlement_expected
  MODIFY expected_source_system VARCHAR(64) NULL COMMENT '应发事实来源系统',
  MODIFY marketing_source_request_id VARCHAR(128) NULL COMMENT '营销跨系统幂等请求号',
  MODIFY benefit_order_no VARCHAR(64) NULL COMMENT '权益订单号；应发阶段可为空';
ALTER TABLE recon_ods_entitlement_internal
  MODIFY expected_source_system VARCHAR(64) NULL COMMENT '应发事实来源系统',
  MODIFY marketing_source_request_id VARCHAR(128) NULL COMMENT '营销跨系统幂等请求号',
  MODIFY benefit_order_no VARCHAR(64) NULL COMMENT '权益订单号';
ALTER TABLE recon_ods_entitlement_provider
  MODIFY expected_source_system VARCHAR(64) NULL COMMENT '应发事实来源系统',
  MODIFY marketing_source_request_id VARCHAR(128) NULL COMMENT '营销跨系统幂等请求号',
  MODIFY benefit_order_no VARCHAR(64) NULL COMMENT '权益订单号';
