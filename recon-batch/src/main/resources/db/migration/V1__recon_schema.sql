-- ============================================================================
-- 通用自动对账系统 · 阶段一 MVP · 领域 schema (设计 §5)
-- 目标可移植: MySQL 8 / PostgreSQL / H2(MySQL 兼容模式, 测试)。
-- 可移植取舍 (相对设计原稿的等价替换):
--   * 索引一律独立 CREATE INDEX (不用 MySQL 专属的 inline KEY(...));
--   * 布尔/标志位用 SMALLINT (PG 无 TINYINT), 语义 0/1 不变;
--   * 金额一律 BIGINT (分, signed), 禁 double;
--   * currency 用 CHAR(3) 且值恒为 3 字符 (无右填充歧义) + CHECK 长度=3。
-- ============================================================================

-- 执行实例: 唯一键 + cutoff + T~T+1 窗口 + revision 乐观锁
CREATE TABLE recon_run (
  run_id            VARCHAR(64) PRIMARY KEY,
  scenario_code     VARCHAR(64)  NOT NULL,
  accounting_period VARCHAR(16)  NOT NULL,           -- 日账期 'YYYY-MM-DD' (A5)
  sequence_no       INT          NOT NULL,
  cutoff_time       TIMESTAMP    NOT NULL,
  match_window_from TIMESTAMP    NOT NULL,           -- T
  match_window_to   TIMESTAMP    NOT NULL,           -- T+1
  bucket_count      INT          NOT NULL,
  status            VARCHAR(24)  NOT NULL,
  revision          BIGINT       NOT NULL DEFAULT 0,
  created_at        TIMESTAMP    NOT NULL,
  updated_at        TIMESTAMP    NOT NULL,
  started_at        TIMESTAMP    NULL,
  finished_at       TIMESTAMP    NULL,
  CONSTRAINT uk_run UNIQUE (scenario_code, accounting_period, sequence_no)
);

-- 序号分配计数器 (修补⑦: 串行化 seq 分配)
CREATE TABLE recon_run_seq (
  scenario_code     VARCHAR(64) NOT NULL,
  accounting_period VARCHAR(16) NOT NULL,
  next_seq          INT         NOT NULL,
  PRIMARY KEY (scenario_code, accounting_period)
);

-- staging: 标准化统一模型, 排序介质 = idx_merge
CREATE TABLE recon_record (
  -- 全局唯一键 = run:segment:side:table:pk (同一源行被多个 (segment,side) 读取时不撞 PK, 如 spine 账务两读);
  -- 血缘 raw_ref 仍是 table:pk。加长到 512 以容纳组合键: run_id(64)+segment_id(32)+side(8)+raw_ref(256)+3 分隔符 = 上界 363。
  record_id           VARCHAR(512) PRIMARY KEY,
  run_id              VARCHAR(64)  NOT NULL,
  segment_id          VARCHAR(32)  NOT NULL,          -- SEG1_MKT_ACCT | SEG2_ACCT_CHANNEL
  side                VARCHAR(8)   NOT NULL,           -- LEFT | RIGHT
  source_role         VARCHAR(16)  NOT NULL,          -- MARKETING | ACCOUNTING | CHANNEL
  -- 遗留②: match_key 是 sort-merge 勾兑键, 其 DB 排序序<b>必须与 Java MatchKey.compareTo (UTF-16 码元序) 对齐</b>,
  -- 否则 per-bucket 游标 ORDER BY 与 Java 归并发散 → 假 MISSING/EXTRA。列的 collation 在<b>方言迁移 V3</b>
  -- (db/schema/{vendor}) 里 pin: MySQL=utf8mb4_bin, PG=COLLATE "C", H2 默认按 Unicode 码点。
  -- 此处基表用可移植 VARCHAR(128) (H2 不支持列级 CHARACTER SET/COLLATE 语法, 故不内联在 V1)。
  -- 诚实边界 (不过度承诺, #2/隐患①):
  --   * <b>尾随空格</b>: MySQL utf8mb4_bin 是 PAD SPACE, 'K1' 与 'K1 ' 视为相等, 与 Java/PG(no-pad) 发散 ——
  --     已在标准化处 (StandardizeProcessor + KeyNormalizer) 对入库键统一 trim 尾随空白消除 PAD SPACE 差异;
  --   * <b>大小写/排序序</b>: pin 二进制/码点序后, 大小写敏感且与 Java 对齐 (仅 BMP 内);
  --   * <b>星平面字符</b>: UTF-16 码元序 vs 码点序对 surrogate pair 仍可能微差 (MVP 勾兑键为 ASCII/BMP 业务号, 不受影响)。
  match_key           VARCHAR(128) NULL,
  group_key           VARCHAR(128) NOT NULL,          -- 修补①: bucket 由它算
  bucket              INT          NOT NULL,          -- floorMod(hash(group_key), bucket_count)
  currency            CHAR(3)      NOT NULL,
  signed_amount_minor BIGINT       NOT NULL,          -- 带符号 (红蓝字), 禁 double
  base_amount_minor   BIGINT       NULL,              -- 【留位·只读·MVP 不比较】
  fx_rate             DECIMAL(20,10) NULL,            -- 【留位·只读】
  fx_rate_time        TIMESTAMP    NULL,              -- 【留位·只读】
  fx_rate_source      VARCHAR(32)  NULL,              -- 【留位·只读】
  entry_type          VARCHAR(16)  NOT NULL,          -- ISSUE | REFUND | REVERSAL
  biz_status          VARCHAR(32)  NULL,
  biz_time            TIMESTAMP    NOT NULL,
  posting_time        TIMESTAMP    NULL,
  claimed_run_id      VARCHAR(64)  NULL,              -- 修补⑧: TIMING 跨 Run 认领
  raw_ref             VARCHAR(256) NOT NULL,          -- 血缘 file:line / table:pk
  created_at          TIMESTAMP    NOT NULL,
  CONSTRAINT ck_ccy CHECK (CHAR_LENGTH(currency) = 3)
);
CREATE INDEX idx_merge ON recon_record (run_id, segment_id, side, bucket, match_key);
CREATE INDEX idx_group ON recon_record (run_id, segment_id, group_key);

CREATE TABLE recon_record_reject (
  id          VARCHAR(64) PRIMARY KEY,
  run_id      VARCHAR(64)  NULL,
  segment_id  VARCHAR(32)  NULL,
  source_role VARCHAR(16)  NULL,
  raw_ref     VARCHAR(256) NULL,
  reason      VARCHAR(128) NULL,
  raw_payload TEXT         NULL,
  created_at  TIMESTAMP    NOT NULL
);

-- 机器判差: fingerprint 幂等 (修补②)
CREATE TABLE discrepancy (
  discrepancy_id        VARCHAR(64) PRIMARY KEY,
  run_id                VARCHAR(64)  NOT NULL,
  segment_id            VARCHAR(32)  NOT NULL,
  type                  VARCHAR(24)  NOT NULL,
  bridge_break_stage    VARCHAR(8)   NULL,            -- SEG1|SEG2
  fingerprint           CHAR(64)     NOT NULL,        -- SHA-256(canonical, null->'∅')
  group_key             VARCHAR(128) NULL,
  match_key             VARCHAR(128) NULL,
  currency              CHAR(3)      NULL,
  expected_amount_minor BIGINT       NOT NULL DEFAULT 0,
  actual_amount_minor   BIGINT       NOT NULL DEFAULT 0,
  delta_amount_minor    BIGINT       NOT NULL DEFAULT 0,
  left_raw_ref          VARCHAR(256) NULL,
  right_raw_ref         VARCHAR(256) NULL,
  machine_result        SMALLINT     NOT NULL DEFAULT 1,
  created_at            TIMESTAMP    NOT NULL,
  updated_at            TIMESTAMP    NOT NULL,
  CONSTRAINT uk_disc UNIQUE (run_id, fingerprint)     -- 空键类型也幂等
);
CREATE INDEX idx_disc ON discrepancy (run_id, type);

-- 人工处置: 永不被重跑删除 (ADR-7)
CREATE TABLE discrepancy_disposition (
  id                VARCHAR(64) PRIMARY KEY,
  fingerprint       CHAR(64)     NOT NULL,
  scenario_code     VARCHAR(64)  NOT NULL,
  accounting_period VARCHAR(16)  NOT NULL,
  segment_id        VARCHAR(32)  NOT NULL,
  status            VARCHAR(16)  NOT NULL,            -- RESOLVED|CLOSED|SUPPRESSED|REOPENED
  operator          VARCHAR(64)  NOT NULL,
  note              VARCHAR(512) NULL,
  last_seen_run_id  VARCHAR(64)  NULL,
  version           INT          NOT NULL DEFAULT 0, -- 乐观锁
  created_at        TIMESTAMP    NOT NULL,
  updated_at        TIMESTAMP    NOT NULL,
  CONSTRAINT uk_disp UNIQUE (fingerprint)             -- 一差一处置
);

-- 冲正建议: 幂等键唯一, 永不被重跑删除, 无资金动作 (ADR-7)
CREATE TABLE reversal_suggestion (
  id                     VARCHAR(64) PRIMARY KEY,
  fingerprint            CHAR(64)     NOT NULL,
  run_id                 VARCHAR(64)  NOT NULL,
  group_key              VARCHAR(128) NULL,
  suggested_amount_minor BIGINT       NOT NULL,
  currency               CHAR(3)      NOT NULL,
  status                 VARCHAR(16)  NOT NULL,       -- SUGGESTED|CONFIRMED|DISCARDED
  idempotency_key        VARCHAR(128) NOT NULL,
  operator               VARCHAR(64)  NULL,
  created_at             TIMESTAMP    NOT NULL,
  CONSTRAINT uk_rev UNIQUE (idempotency_key)
);

-- 处置/处理动作审计 + 外部幂等
CREATE TABLE discrepancy_action (
  id              VARCHAR(64) PRIMARY KEY,
  fingerprint     CHAR(64)     NOT NULL,
  action_type     VARCHAR(24)  NOT NULL,             -- LEDGER|REVERSAL_SUGGESTION|MANUAL_RESOLVE|MANUAL_CLOSE
  idempotency_key VARCHAR(128) NOT NULL,
  payload         TEXT         NULL,
  operator        VARCHAR(64)  NOT NULL,
  created_at      TIMESTAMP    NOT NULL,
  CONSTRAINT uk_action UNIQUE (idempotency_key)
);

-- 告警发件箱 (修补⑤ / ADR-10)
CREATE TABLE alert_outbox (
  id              VARCHAR(64) PRIMARY KEY,
  run_id          VARCHAR(64)  NOT NULL,
  fingerprint     CHAR(64)     NOT NULL,
  payload         TEXT         NOT NULL,
  status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',   -- PENDING|SENT|FAILED
  attempt         INT          NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(128) NOT NULL,
  created_at      TIMESTAMP    NOT NULL,
  sent_at         TIMESTAMP    NULL,
  CONSTRAINT uk_outbox UNIQUE (idempotency_key)
);

-- 勾稽报表: 按 (segment, currency) 双向守恒
CREATE TABLE recon_report (
  report_id                VARCHAR(64) PRIMARY KEY,
  run_id                   VARCHAR(64) NOT NULL,
  segment_id               VARCHAR(32) NOT NULL,
  currency                 CHAR(3)     NOT NULL,
  expected_total_minor     BIGINT      NOT NULL,
  matched_amount_minor     BIGINT      NOT NULL,
  amount_mismatch_minor    BIGINT      NOT NULL DEFAULT 0,
  missing_minor            BIGINT      NOT NULL DEFAULT 0,
  duplicate_minor          BIGINT      NOT NULL DEFAULT 0,
  extra_minor              BIGINT      NOT NULL DEFAULT 0,
  timing_minor             BIGINT      NOT NULL DEFAULT 0,
  status_mismatch_minor    BIGINT      NOT NULL DEFAULT 0,
  currency_mismatch_minor  BIGINT      NOT NULL DEFAULT 0,
  group_sum_mismatch_minor BIGINT      NOT NULL DEFAULT 0,
  bridge_broken_minor      BIGINT      NOT NULL DEFAULT 0,
  right_side_total_minor   BIGINT      NOT NULL DEFAULT 0,
  left_residual_minor      BIGINT      NOT NULL DEFAULT 0,
  right_residual_minor     BIGINT      NOT NULL DEFAULT 0,
  balanced                 SMALLINT    NOT NULL,
  created_at               TIMESTAMP   NOT NULL,
  CONSTRAINT uk_report UNIQUE (run_id, segment_id, currency)
);

-- M3 单遍守恒: 每 partition (一个 bucket) 流式累计的<b>局部守恒结果</b> (设计 §6/§8)。
-- 汇总步跨 bucket 按 (segment, currency) 合并同名子项复算最终 recon_report。列 = ConservationChecker 内部桶子项
-- (原始值, 不含派生 residual/balanced); 金额一律 BIGINT 分。幂等键 = (run_id, segment_id, bucket, currency),
-- 重跑/断点续跑同键覆盖。属机器产物, 重跑随 staging/机器判差一同分批清 (不碰人工表)。
CREATE TABLE recon_report_partial (
  id                            VARCHAR(64) PRIMARY KEY,
  run_id                        VARCHAR(64) NOT NULL,
  segment_id                    VARCHAR(32) NOT NULL,
  bucket                        INT         NOT NULL,
  sub_index                     INT         NOT NULL DEFAULT -1,   -- 二级 sub-bucket 分片号 (未拆 = -1)
  currency                      CHAR(3)     NOT NULL,
  expected_total_minor          BIGINT      NOT NULL,
  right_side_total_minor        BIGINT      NOT NULL,
  matched_left_minor            BIGINT      NOT NULL,
  matched_right_minor           BIGINT      NOT NULL,
  missing_minor                 BIGINT      NOT NULL,
  extra_minor                   BIGINT      NOT NULL,
  amount_mismatch_left_minor    BIGINT      NOT NULL,
  status_left_minor             BIGINT      NOT NULL,
  timing_left_minor             BIGINT      NOT NULL,
  group_sum_left_minor          BIGINT      NOT NULL,
  duplicate_left_minor          BIGINT      NOT NULL,
  bridge_broken_left_minor      BIGINT      NOT NULL,
  bridge_broken_right_minor     BIGINT      NOT NULL,
  currency_mismatch_left_minor  BIGINT      NOT NULL,
  currency_mismatch_right_minor BIGINT      NOT NULL,
  created_at                    TIMESTAMP   NOT NULL,
  CONSTRAINT uk_partial UNIQUE (run_id, segment_id, bucket, sub_index, currency)
);
CREATE INDEX idx_partial_run ON recon_report_partial (run_id);
