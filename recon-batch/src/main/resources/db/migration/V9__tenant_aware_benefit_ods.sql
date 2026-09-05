-- Slice 3 expand-only migration. Existing runs are assigned to the explicit legacy tenant;
-- new benefit runs always persist their real tenant before ODS scanning is enabled.
ALTER TABLE recon_run ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'legacy';
CREATE INDEX idx_recon_run_tenant_period
  ON recon_run (tenant_id, scenario_code, accounting_period, sequence_no);

-- 非金额权益仍复用统一差异列表，但用 measure_kind/数量列显式区分，绝不伪造币种。
ALTER TABLE discrepancy ADD COLUMN measure_kind VARCHAR(16) NOT NULL DEFAULT 'MONEY';
ALTER TABLE discrepancy ADD COLUMN expected_quantity BIGINT;
ALTER TABLE discrepancy ADD COLUMN internal_quantity BIGINT;
ALTER TABLE discrepancy ADD COLUMN provider_quantity BIGINT;
ALTER TABLE discrepancy ADD COLUMN expected_source_system VARCHAR(64);
ALTER TABLE discrepancy ADD COLUMN marketing_source_request_id VARCHAR(128);
ALTER TABLE discrepancy ADD COLUMN benefit_order_no VARCHAR(64);

-- Correlation fields stay on ODS facts so the read model never infers business identity from SKU/time proximity.
ALTER TABLE recon_ods_cash_expected ADD COLUMN expected_source_system VARCHAR(64);
ALTER TABLE recon_ods_cash_expected ADD COLUMN marketing_source_request_id VARCHAR(128);
ALTER TABLE recon_ods_cash_expected ADD COLUMN benefit_order_no VARCHAR(64);
ALTER TABLE recon_ods_cash_accounting ADD COLUMN expected_source_system VARCHAR(64);
ALTER TABLE recon_ods_cash_accounting ADD COLUMN marketing_source_request_id VARCHAR(128);
ALTER TABLE recon_ods_cash_accounting ADD COLUMN benefit_order_no VARCHAR(64);
ALTER TABLE recon_ods_cash_channel ADD COLUMN expected_source_system VARCHAR(64);
ALTER TABLE recon_ods_cash_channel ADD COLUMN marketing_source_request_id VARCHAR(128);
ALTER TABLE recon_ods_cash_channel ADD COLUMN benefit_order_no VARCHAR(64);

ALTER TABLE recon_ods_entitlement_expected ADD COLUMN expected_source_system VARCHAR(64);
ALTER TABLE recon_ods_entitlement_expected ADD COLUMN marketing_source_request_id VARCHAR(128);
ALTER TABLE recon_ods_entitlement_expected ADD COLUMN benefit_order_no VARCHAR(64);
ALTER TABLE recon_ods_entitlement_internal ADD COLUMN expected_source_system VARCHAR(64);
ALTER TABLE recon_ods_entitlement_internal ADD COLUMN marketing_source_request_id VARCHAR(128);
ALTER TABLE recon_ods_entitlement_internal ADD COLUMN benefit_order_no VARCHAR(64);
ALTER TABLE recon_ods_entitlement_provider ADD COLUMN expected_source_system VARCHAR(64);
ALTER TABLE recon_ods_entitlement_provider ADD COLUMN marketing_source_request_id VARCHAR(128);
ALTER TABLE recon_ods_entitlement_provider ADD COLUMN benefit_order_no VARCHAR(64);

CREATE INDEX idx_ods_cash_expected_source
  ON recon_ods_cash_expected (tenant_id, marketing_source_request_id, issue_id);
CREATE INDEX idx_ods_ent_expected_source
  ON recon_ods_entitlement_expected (tenant_id, marketing_source_request_id, issue_id);
CREATE INDEX idx_ods_cash_accounting_order
  ON recon_ods_cash_accounting (tenant_id, benefit_order_no, issue_id);
CREATE INDEX idx_ods_cash_channel_order
  ON recon_ods_cash_channel (tenant_id, benefit_order_no, issue_id);
CREATE INDEX idx_ods_ent_internal_order
  ON recon_ods_entitlement_internal (tenant_id, benefit_order_no, issue_id);
CREATE INDEX idx_ods_ent_provider_order
  ON recon_ods_entitlement_provider (tenant_id, benefit_order_no, issue_id);
