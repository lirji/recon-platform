ALTER TABLE remediation_command_outbox ADD COLUMN lease_owner VARCHAR(128);
ALTER TABLE remediation_command_outbox ADD COLUMN lease_until TIMESTAMP;
ALTER TABLE remediation_command_outbox ADD COLUMN last_error VARCHAR(512);

CREATE INDEX idx_remediation_command_lease
  ON remediation_command_outbox (status, lease_until, command_id);
