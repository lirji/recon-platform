-- Expand-only bridge for benefit-center reconciliation. Existing MARKETING_3WAY tables remain untouched
-- until tenant-aware readers are deployed; every new table is tenant-native from its first write.
CREATE TABLE recon_ods_cash_expected (
  tenant_id VARCHAR(64) NOT NULL,
  id VARCHAR(64) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  issue_id VARCHAR(64) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  channel_serial_no VARCHAR(128),
  ccy CHAR(3) NOT NULL,
  amount_minor BIGINT NOT NULL,
  entry_type VARCHAR(16) NOT NULL,
  biz_status VARCHAR(32),
  biz_time TIMESTAMP NOT NULL,
  posting_time TIMESTAMP,
  cell_id VARCHAR(32),
  shard_key VARCHAR(128),
  source_partition INT,
  source_offset BIGINT,
  raw_ref VARCHAR(256) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT uk_ods_cash_expected_event UNIQUE (tenant_id, event_id)
);

CREATE TABLE recon_ods_cash_accounting (
  tenant_id VARCHAR(64) NOT NULL, id VARCHAR(64) NOT NULL, event_id VARCHAR(128) NOT NULL,
  issue_id VARCHAR(64) NOT NULL, order_no VARCHAR(64) NOT NULL, channel_serial_no VARCHAR(128),
  ccy CHAR(3) NOT NULL, amount_minor BIGINT NOT NULL, entry_type VARCHAR(16) NOT NULL,
  biz_status VARCHAR(32), biz_time TIMESTAMP NOT NULL, posting_time TIMESTAMP,
  cell_id VARCHAR(32), shard_key VARCHAR(128), source_partition INT, source_offset BIGINT,
  raw_ref VARCHAR(256) NOT NULL, created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT uk_ods_cash_accounting_event UNIQUE (tenant_id, event_id)
);

CREATE TABLE recon_ods_cash_channel (
  tenant_id VARCHAR(64) NOT NULL, id VARCHAR(64) NOT NULL, event_id VARCHAR(128) NOT NULL,
  issue_id VARCHAR(64) NOT NULL, order_no VARCHAR(64) NOT NULL, channel_serial_no VARCHAR(128),
  ccy CHAR(3) NOT NULL, amount_minor BIGINT NOT NULL, entry_type VARCHAR(16) NOT NULL,
  biz_status VARCHAR(32), biz_time TIMESTAMP NOT NULL, posting_time TIMESTAMP,
  cell_id VARCHAR(32), shard_key VARCHAR(128), source_partition INT, source_offset BIGINT,
  raw_ref VARCHAR(256) NOT NULL, created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT uk_ods_cash_channel_event UNIQUE (tenant_id, event_id)
);

CREATE TABLE recon_ods_entitlement_expected (
  tenant_id VARCHAR(64) NOT NULL,
  id VARCHAR(64) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  issue_id VARCHAR(64) NOT NULL,
  sku_id VARCHAR(128) NOT NULL,
  quantity BIGINT NOT NULL,
  fulfillment_status VARCHAR(32) NOT NULL,
  provider_ref VARCHAR(256),
  occurred_at TIMESTAMP NOT NULL,
  cell_id VARCHAR(32),
  shard_key VARCHAR(128),
  source_partition INT,
  source_offset BIGINT,
  raw_ref VARCHAR(256) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT uk_ods_ent_expected_event UNIQUE (tenant_id, event_id),
  CONSTRAINT ck_ods_ent_expected_qty CHECK (quantity > 0)
);

CREATE TABLE recon_ods_entitlement_internal (
  tenant_id VARCHAR(64) NOT NULL, id VARCHAR(64) NOT NULL, event_id VARCHAR(128) NOT NULL,
  issue_id VARCHAR(64) NOT NULL, sku_id VARCHAR(128) NOT NULL, quantity BIGINT NOT NULL,
  fulfillment_status VARCHAR(32) NOT NULL, provider_ref VARCHAR(256), occurred_at TIMESTAMP NOT NULL,
  cell_id VARCHAR(32), shard_key VARCHAR(128), source_partition INT, source_offset BIGINT,
  raw_ref VARCHAR(256) NOT NULL, created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT uk_ods_ent_internal_event UNIQUE (tenant_id, event_id),
  CONSTRAINT ck_ods_ent_internal_qty CHECK (quantity > 0)
);

CREATE TABLE recon_ods_entitlement_provider (
  tenant_id VARCHAR(64) NOT NULL, id VARCHAR(64) NOT NULL, event_id VARCHAR(128) NOT NULL,
  issue_id VARCHAR(64) NOT NULL, sku_id VARCHAR(128) NOT NULL, quantity BIGINT NOT NULL,
  fulfillment_status VARCHAR(32) NOT NULL, provider_ref VARCHAR(256), occurred_at TIMESTAMP NOT NULL,
  cell_id VARCHAR(32), shard_key VARCHAR(128), source_partition INT, source_offset BIGINT,
  raw_ref VARCHAR(256) NOT NULL, created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT uk_ods_ent_provider_event UNIQUE (tenant_id, event_id),
  CONSTRAINT ck_ods_ent_provider_qty CHECK (quantity > 0)
);

CREATE TABLE ods_message_inbox (
  tenant_id VARCHAR(64) NOT NULL,
  consumer_group VARCHAR(128) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  received_at TIMESTAMP NOT NULL,
  processed_at TIMESTAMP,
  PRIMARY KEY (tenant_id, consumer_group, event_id)
);

CREATE TABLE remediation_suggestion (
  tenant_id VARCHAR(64) NOT NULL,
  suggestion_id VARCHAR(64) NOT NULL,
  scenario_code VARCHAR(64) NOT NULL,
  discrepancy_ref VARCHAR(128) NOT NULL,
  award_item_no VARCHAR(64) NOT NULL,
  original_operation_no VARCHAR(64),
  action_type VARCHAR(32) NOT NULL,
  reason VARCHAR(512) NOT NULL,
  status VARCHAR(24) NOT NULL,
  approval_ref VARCHAR(256),
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (tenant_id, suggestion_id),
  CONSTRAINT uk_remediation_disc_action UNIQUE (tenant_id, discrepancy_ref, action_type)
);

CREATE TABLE remediation_command_outbox (
  tenant_id VARCHAR(64) NOT NULL,
  command_id VARCHAR(64) NOT NULL,
  suggestion_id VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(192) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  payload TEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP,
  published_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (tenant_id, command_id),
  CONSTRAINT uk_remediation_command_idem UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_ods_cash_expected_window ON recon_ods_cash_expected (tenant_id, biz_time, id);
CREATE INDEX idx_ods_cash_accounting_window ON recon_ods_cash_accounting (tenant_id, biz_time, id);
CREATE INDEX idx_ods_cash_channel_window ON recon_ods_cash_channel (tenant_id, biz_time, id);
CREATE INDEX idx_ods_ent_expected_window ON recon_ods_entitlement_expected (tenant_id, occurred_at, id);
CREATE INDEX idx_ods_ent_internal_window ON recon_ods_entitlement_internal (tenant_id, occurred_at, id);
CREATE INDEX idx_ods_ent_provider_window ON recon_ods_entitlement_provider (tenant_id, occurred_at, id);
CREATE INDEX idx_remediation_command_due ON remediation_command_outbox (tenant_id, status, next_attempt_at, command_id);
