-- ============================================================================
-- B4 · 配置驱动场景 (阶段二) · 场景定义存储
-- 可移植: MySQL 8 / PostgreSQL / H2(MySQL 兼容模式)。definition_json 存声明式 ScenarioDefinition 的 JSON,
-- 由组合根 Jackson 序列化; 装配期经 GenericScenarioAssembler 校验后才落库 (不静默存坏定义)。
-- 布尔用 SMALLINT (0/1, PG 无 TINYINT), TEXT 承载 JSON (与 V1 raw_payload/payload 同类型, 三方言通用)。
-- ============================================================================
CREATE TABLE recon_scenario_def (
  code            VARCHAR(64)  PRIMARY KEY,          -- 场景码 (发起 Run 时按 code 装配)
  version         INT          NOT NULL DEFAULT 1,   -- 定义版本 (乐观并发 / 审计留位)
  definition_json TEXT         NOT NULL,             -- 声明式 ScenarioDefinition 的 JSON
  enabled         SMALLINT     NOT NULL DEFAULT 1,   -- 0=停用 (不可发起), 1=启用
  created_at      TIMESTAMP    NOT NULL,
  updated_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_scenario_def_enabled ON recon_scenario_def (enabled);
