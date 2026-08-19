-- ============================================================================
-- V5 · 表/列注释 (MySQL 方言)。由 Flyway {vendor}=mysql 目录装配 (仅 MySQL 库执行)。
--
-- 为何单列方言: MySQL 无 COMMENT ON 语法, 列注释只能经 ALTER ... MODIFY COLUMN 重述完整列定义后附
-- COMMENT。本迁移各列的 类型/可空/默认值 均与 V1/V4 建表 + V3 collation 完全一致 —— 只加注释, 不改结构。
--   ⚠ recon_record.match_key 必须保留 V3 pin 的 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin, 勿在此回退。
--   注释文本刻意不含 ASCII 单引号 (三方言共用同一批文案; PG/H2 见 db/schema/{postgresql,h2}/V5 的 COMMENT ON 版)。
-- 语义来源: 设计 §5 + V1/V4 源码行内注解。
-- ============================================================================

ALTER TABLE recon_run
  COMMENT = '对账执行实例: 唯一键(场景+账期+序号) + cutoff + T~T+1 匹配窗口 + revision 乐观锁',
  MODIFY run_id            VARCHAR(64)  NOT NULL COMMENT '运行主键 (UUID)',
  MODIFY scenario_code     VARCHAR(64)  NOT NULL COMMENT '场景码',
  MODIFY accounting_period VARCHAR(16)  NOT NULL COMMENT '日账期 YYYY-MM-DD (A5)',
  MODIFY sequence_no       INT          NOT NULL COMMENT '同场景同账期内的重跑序号 (串行分配, 修补⑦)',
  MODIFY cutoff_time       TIMESTAMP    NOT NULL COMMENT '截止时间',
  MODIFY match_window_from TIMESTAMP    NOT NULL COMMENT '匹配窗口起 T',
  MODIFY match_window_to   TIMESTAMP    NOT NULL COMMENT '匹配窗口止 T+1',
  MODIFY bucket_count      INT          NOT NULL COMMENT '分桶数 (1..4096)',
  MODIFY status            VARCHAR(24)  NOT NULL COMMENT '运行状态 (RUNNING/COMPLETED/FAILED 等)',
  MODIFY revision          BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  MODIFY created_at        TIMESTAMP    NOT NULL COMMENT '创建时间',
  MODIFY updated_at        TIMESTAMP    NOT NULL COMMENT '更新时间',
  MODIFY started_at        TIMESTAMP    NULL     COMMENT '开始时间',
  MODIFY finished_at       TIMESTAMP    NULL     COMMENT '结束时间';

ALTER TABLE recon_run_seq
  COMMENT = '序号分配计数器: 按(场景,账期)串行化 sequence_no 分配 (修补⑦)',
  MODIFY scenario_code     VARCHAR(64) NOT NULL COMMENT '场景码',
  MODIFY accounting_period VARCHAR(16) NOT NULL COMMENT '日账期 YYYY-MM-DD',
  MODIFY next_seq          INT         NOT NULL COMMENT '下一个可用序号';

ALTER TABLE recon_record
  COMMENT = 'staging 标准化统一模型: 流式 sort-merge 勾兑入库源, 排序介质 idx_merge',
  MODIFY record_id           VARCHAR(512)   NOT NULL COMMENT '全局唯一键 run:segment:side:raw_ref (同源行被多 segment/side 读取不撞 PK)',
  MODIFY run_id              VARCHAR(64)    NOT NULL COMMENT '所属 run',
  MODIFY segment_id          VARCHAR(32)    NOT NULL COMMENT '段: SEG1_MKT_ACCT | SEG2_ACCT_CHANNEL',
  MODIFY side                VARCHAR(8)     NOT NULL COMMENT '侧: LEFT | RIGHT',
  MODIFY source_role         VARCHAR(16)    NOT NULL COMMENT '来源角色: MARKETING | ACCOUNTING | CHANNEL',
  MODIFY match_key           VARCHAR(128)   CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT 'sort-merge 勾兑键(可空); collation 由 V3 pin utf8mb4_bin 与 Java 码元序对齐; 尾随空白已标准化 trim',
  MODIFY group_key           VARCHAR(128)   NOT NULL COMMENT '分桶键(bucket 由它算); 不变式: match_key 必为其细分',
  MODIFY bucket              INT            NOT NULL COMMENT 'floorMod(hash(group_key), bucket_count)',
  MODIFY currency            CHAR(3)        NOT NULL COMMENT '币种 ISO 4217 三字母',
  MODIFY signed_amount_minor BIGINT         NOT NULL COMMENT '带符号金额(分, 红蓝字), 禁 double',
  MODIFY base_amount_minor   BIGINT         NULL     COMMENT '本位币金额 (留位·只读·MVP 不比较)',
  MODIFY fx_rate             DECIMAL(20,10) NULL     COMMENT '汇率 (留位·只读)',
  MODIFY fx_rate_time        TIMESTAMP      NULL     COMMENT '汇率时间 (留位·只读)',
  MODIFY fx_rate_source      VARCHAR(32)    NULL     COMMENT '汇率来源 (留位·只读)',
  MODIFY entry_type          VARCHAR(16)    NOT NULL COMMENT '记账方向: ISSUE | REFUND | REVERSAL',
  MODIFY biz_status          VARCHAR(32)    NULL     COMMENT '业务状态',
  MODIFY biz_time            TIMESTAMP      NOT NULL COMMENT '业务时间',
  MODIFY posting_time        TIMESTAMP      NULL     COMMENT '记账时间',
  MODIFY claimed_run_id      VARCHAR(64)    NULL     COMMENT 'TIMING 跨 Run 认领的 run_id (修补⑧)',
  MODIFY raw_ref             VARCHAR(256)   NOT NULL COMMENT '血缘: file:line 或 table:pk',
  MODIFY created_at          TIMESTAMP      NOT NULL COMMENT '创建时间';

ALTER TABLE recon_record_reject
  COMMENT = '载入期业务畸形行 reject (逐行拒绝后继续); 不可恢复的语法/编码错误另记并终止当前文件',
  MODIFY id          VARCHAR(64)  NOT NULL COMMENT '主键',
  MODIFY run_id      VARCHAR(64)  NULL     COMMENT '所属 run (可空)',
  MODIFY segment_id  VARCHAR(32)  NULL     COMMENT '段',
  MODIFY source_role VARCHAR(16)  NULL     COMMENT '来源角色',
  MODIFY raw_ref     VARCHAR(256) NULL     COMMENT '血缘 file:line 或 table:pk',
  MODIFY reason      VARCHAR(128) NULL     COMMENT '拒绝原因',
  MODIFY raw_payload TEXT         NULL     COMMENT '原始行内容',
  MODIFY created_at  TIMESTAMP    NOT NULL COMMENT '创建时间';

ALTER TABLE discrepancy
  COMMENT = '机器判差结果: fingerprint 幂等(uk_disc 让空键类型也幂等); 重跑清 machine_result=1 的行 (修补②/ADR-7)',
  MODIFY discrepancy_id        VARCHAR(64)  NOT NULL COMMENT 'run 内由 runId+fingerprint 派生的稳定 UUID',
  MODIFY run_id                VARCHAR(64)  NOT NULL COMMENT '所属 run',
  MODIFY segment_id            VARCHAR(32)  NOT NULL COMMENT '段',
  MODIFY type                  VARCHAR(24)  NOT NULL COMMENT '差异类型 (MISSING/EXTRA/AMOUNT_MISMATCH/BRIDGE_BROKEN 等)',
  MODIFY bridge_break_stage    VARCHAR(8)   NULL     COMMENT '桥断阶段 SEG1|SEG2 (仅 BRIDGE_BROKEN, 优先级高于 MISSING)',
  MODIFY fingerprint           CHAR(64)     NOT NULL COMMENT 'SHA-256 canonical 业务身份 (null 键→∅)',
  MODIFY group_key             VARCHAR(128) NULL     COMMENT '分组键',
  MODIFY match_key             VARCHAR(128) NULL     COMMENT '勾兑键',
  MODIFY currency              CHAR(3)      NULL     COMMENT '币种',
  MODIFY expected_amount_minor BIGINT       NOT NULL DEFAULT 0 COMMENT '期望额(分)',
  MODIFY actual_amount_minor   BIGINT       NOT NULL DEFAULT 0 COMMENT '实际额(分)',
  MODIFY delta_amount_minor    BIGINT       NOT NULL DEFAULT 0 COMMENT '差额(分)',
  MODIFY left_raw_ref          VARCHAR(256) NULL     COMMENT '左侧血缘',
  MODIFY right_raw_ref         VARCHAR(256) NULL     COMMENT '右侧血缘',
  MODIFY machine_result        SMALLINT     NOT NULL DEFAULT 1 COMMENT '1=机器产物(重跑可清), 0=非机器',
  MODIFY created_at            TIMESTAMP    NOT NULL COMMENT '创建时间',
  MODIFY updated_at            TIMESTAMP    NOT NULL COMMENT '更新时间';

ALTER TABLE discrepancy_disposition
  COMMENT = '人工处置: 永不被重跑删除 (ADR-7); 一差一处置 (uk_disp)',
  MODIFY id                VARCHAR(64)  NOT NULL COMMENT '主键',
  MODIFY fingerprint       CHAR(64)     NOT NULL COMMENT '对应 discrepancy 的业务身份',
  MODIFY scenario_code     VARCHAR(64)  NOT NULL COMMENT '场景码',
  MODIFY accounting_period VARCHAR(16)  NOT NULL COMMENT '日账期',
  MODIFY segment_id        VARCHAR(32)  NOT NULL COMMENT '段',
  MODIFY status            VARCHAR(16)  NOT NULL COMMENT '处置状态: RESOLVED|CLOSED|SUPPRESSED|REOPENED',
  MODIFY operator          VARCHAR(64)  NOT NULL COMMENT '处置人',
  MODIFY note              VARCHAR(512) NULL     COMMENT '备注',
  MODIFY last_seen_run_id  VARCHAR(64)  NULL     COMMENT '最近一次出现该差异的 run',
  MODIFY version           INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  MODIFY created_at        TIMESTAMP    NOT NULL COMMENT '创建时间',
  MODIFY updated_at        TIMESTAMP    NOT NULL COMMENT '更新时间';

ALTER TABLE reversal_suggestion
  COMMENT = '冲正建议: 幂等键唯一, 永不被重跑删除, MVP 无资金动作 (ADR-7)',
  MODIFY id                     VARCHAR(64)  NOT NULL COMMENT '主键',
  MODIFY fingerprint            CHAR(64)     NOT NULL COMMENT '对应 discrepancy 的业务身份',
  MODIFY run_id                 VARCHAR(64)  NOT NULL COMMENT '所属 run',
  MODIFY group_key              VARCHAR(128) NULL     COMMENT '分组键',
  MODIFY suggested_amount_minor BIGINT       NOT NULL COMMENT '建议冲正额(分)',
  MODIFY currency               CHAR(3)      NOT NULL COMMENT '币种',
  MODIFY status                 VARCHAR(16)  NOT NULL COMMENT '状态: SUGGESTED|CONFIRMED|DISCARDED',
  MODIFY idempotency_key        VARCHAR(128) NOT NULL COMMENT '幂等键 (uk_rev)',
  MODIFY operator               VARCHAR(64)  NULL     COMMENT '操作人',
  MODIFY created_at             TIMESTAMP    NOT NULL COMMENT '创建时间';

ALTER TABLE discrepancy_action
  COMMENT = '处置/处理动作审计 + 外部幂等 (uk_action)',
  MODIFY id              VARCHAR(64)  NOT NULL COMMENT '主键',
  MODIFY fingerprint     CHAR(64)     NOT NULL COMMENT '对应差异的业务身份',
  MODIFY action_type     VARCHAR(24)  NOT NULL COMMENT '动作类型: LEDGER|REVERSAL_SUGGESTION|MANUAL_RESOLVE|MANUAL_CLOSE',
  MODIFY idempotency_key VARCHAR(128) NOT NULL COMMENT '幂等键 (uk_action)',
  MODIFY payload         TEXT         NULL     COMMENT '动作载荷',
  MODIFY operator        VARCHAR(64)  NOT NULL COMMENT '操作人',
  MODIFY created_at      TIMESTAMP    NOT NULL COMMENT '创建时间';

ALTER TABLE alert_outbox
  COMMENT = '告警发件箱 (修补⑤/ADR-10): chunk 内只写 outbox, relay 以 REQUIRES_NEW 短事务外发置 SENT/FAILED',
  MODIFY id              VARCHAR(64)  NOT NULL COMMENT '主键',
  MODIFY run_id          VARCHAR(64)  NOT NULL COMMENT '所属 run',
  MODIFY fingerprint     CHAR(64)     NOT NULL COMMENT '关联差异的业务身份',
  MODIFY payload         TEXT         NOT NULL COMMENT '告警载荷',
  MODIFY status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING|SENT|FAILED',
  MODIFY attempt         INT          NOT NULL DEFAULT 0 COMMENT '外发重试次数',
  MODIFY idempotency_key VARCHAR(128) NOT NULL COMMENT '幂等键 (uk_outbox)',
  MODIFY created_at      TIMESTAMP    NOT NULL COMMENT '创建时间',
  MODIFY sent_at         TIMESTAMP    NULL     COMMENT '发送成功时间';

ALTER TABLE recon_report
  COMMENT = '勾稽报表: 按(segment,currency)双向守恒; left_residual/right_residual by-construction ≡ 0',
  MODIFY report_id                VARCHAR(64) NOT NULL COMMENT '主键',
  MODIFY run_id                   VARCHAR(64) NOT NULL COMMENT '所属 run',
  MODIFY segment_id               VARCHAR(32) NOT NULL COMMENT '段',
  MODIFY currency                 CHAR(3)     NOT NULL COMMENT '币种 (跨币分桶不相加)',
  MODIFY expected_total_minor     BIGINT      NOT NULL COMMENT '左侧期望总额(分)',
  MODIFY matched_amount_minor     BIGINT      NOT NULL COMMENT '干净匹配左额(分)',
  MODIFY amount_mismatch_minor    BIGINT      NOT NULL DEFAULT 0 COMMENT '金额不符桶(分)',
  MODIFY missing_minor            BIGINT      NOT NULL DEFAULT 0 COMMENT '缺失桶(分)',
  MODIFY duplicate_minor          BIGINT      NOT NULL DEFAULT 0 COMMENT '重复桶(分)',
  MODIFY extra_minor              BIGINT      NOT NULL DEFAULT 0 COMMENT '多余桶(分)',
  MODIFY timing_minor             BIGINT      NOT NULL DEFAULT 0 COMMENT '时间性差异桶(分)',
  MODIFY status_mismatch_minor    BIGINT      NOT NULL DEFAULT 0 COMMENT '状态不符桶(分)',
  MODIFY currency_mismatch_minor  BIGINT      NOT NULL DEFAULT 0 COMMENT '币种不符桶(分)',
  MODIFY group_sum_mismatch_minor BIGINT      NOT NULL DEFAULT 0 COMMENT '分组合计不符桶(分)',
  MODIFY bridge_broken_minor      BIGINT      NOT NULL DEFAULT 0 COMMENT '桥断桶(分)',
  MODIFY right_side_total_minor   BIGINT      NOT NULL DEFAULT 0 COMMENT '右侧总额(分)',
  MODIFY left_residual_minor      BIGINT      NOT NULL DEFAULT 0 COMMENT '左残差 (by-construction ≡ 0)',
  MODIFY right_residual_minor     BIGINT      NOT NULL DEFAULT 0 COMMENT '右残差 (by-construction ≡ 0)',
  MODIFY balanced                 SMALLINT    NOT NULL COMMENT '1=守恒, 0=不守恒',
  MODIFY created_at               TIMESTAMP   NOT NULL COMMENT '创建时间';

ALTER TABLE recon_report_partial
  COMMENT = 'M3 单遍守恒: 每 partition(bucket) 流式累计的局部结果; 汇总步跨 bucket 合并复算 recon_report (机器产物, 可重跑清)',
  MODIFY id                            VARCHAR(64) NOT NULL COMMENT '主键',
  MODIFY run_id                        VARCHAR(64) NOT NULL COMMENT '所属 run',
  MODIFY segment_id                    VARCHAR(32) NOT NULL COMMENT '段',
  MODIFY bucket                        INT         NOT NULL COMMENT '分桶号',
  MODIFY sub_index                     INT         NOT NULL DEFAULT -1 COMMENT '二级 sub-bucket 分片号 (未拆 = -1)',
  MODIFY currency                      CHAR(3)     NOT NULL COMMENT '币种',
  MODIFY expected_total_minor          BIGINT      NOT NULL COMMENT '左侧期望总额(分)',
  MODIFY right_side_total_minor        BIGINT      NOT NULL COMMENT '右侧总额(分)',
  MODIFY matched_left_minor            BIGINT      NOT NULL COMMENT '干净匹配左额(分)',
  MODIFY matched_right_minor           BIGINT      NOT NULL COMMENT '干净匹配右额 + 有差组右额(分)',
  MODIFY missing_minor                 BIGINT      NOT NULL COMMENT '缺失(分)',
  MODIFY extra_minor                   BIGINT      NOT NULL COMMENT '多余(分)',
  MODIFY amount_mismatch_left_minor    BIGINT      NOT NULL COMMENT '金额不符左额(分)',
  MODIFY status_left_minor             BIGINT      NOT NULL COMMENT '状态不符左额(分)',
  MODIFY timing_left_minor             BIGINT      NOT NULL COMMENT '时间性差异左额(分)',
  MODIFY group_sum_left_minor          BIGINT      NOT NULL COMMENT '分组合计不符左额(分)',
  MODIFY duplicate_left_minor          BIGINT      NOT NULL COMMENT '重复左额(分)',
  MODIFY bridge_broken_left_minor      BIGINT      NOT NULL COMMENT '桥断左额(分)',
  MODIFY bridge_broken_right_minor     BIGINT      NOT NULL COMMENT '桥断右额(分)',
  MODIFY currency_mismatch_left_minor  BIGINT      NOT NULL COMMENT '币种不符左额(分)',
  MODIFY currency_mismatch_right_minor BIGINT      NOT NULL COMMENT '币种不符右额(分)',
  MODIFY created_at                    TIMESTAMP   NOT NULL COMMENT '创建时间';

ALTER TABLE recon_scenario_def
  COMMENT = 'B4 配置驱动场景定义: definition_json 经 GenericScenarioAssembler 校验后落库',
  MODIFY code            VARCHAR(64) NOT NULL COMMENT '场景码 (发起 Run 按 code 装配)',
  MODIFY version         INT         NOT NULL DEFAULT 1 COMMENT '定义版本 (乐观并发/审计)',
  MODIFY definition_json TEXT        NOT NULL COMMENT '声明式 ScenarioDefinition 的 JSON',
  MODIFY enabled         SMALLINT    NOT NULL DEFAULT 1 COMMENT '0=停用(不可发起), 1=启用',
  MODIFY created_at      TIMESTAMP   NOT NULL COMMENT '创建时间',
  MODIFY updated_at      TIMESTAMP   NOT NULL COMMENT '更新时间';
